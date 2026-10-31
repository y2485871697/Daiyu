package app.daiyu.android

import app.daiyu.android.ui.navigation.PageBackStack
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import app.daiyu.android.voice.providers.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.ui.haptics.miuix.TextButton
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import app.daiyu.android.ui.screens.settings.SettingsScaffold
import app.daiyu.android.ui.screens.settings.*
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.voice.ActivityVoiceAudio
import app.daiyu.android.voice.duplex.DoubaoDuplexProtocol
import app.daiyu.android.voice.providers.DoubaoAsrProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun VoiceSettingsPanel(
    runtime: ControllerVoiceRuntime,
    remote: ActivityRemoteVoiceHost,
    accounts: app.daiyu.android.voice.account.VoiceAccountResources,
    modelSource: SettingsModelSource,
    installPreviewRevoke: (() -> Unit) -> (() -> Unit),
    isCurrent: () -> Boolean,
    invalidate: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { VoiceSettingsPreferences(context) }
    var section by rememberSaveable { mutableStateOf("RECOGNITION") }
    val profile = if (section == "CONVERSATION") VoiceProfile.CONVERSATION else VoiceProfile.READ_ALOUD
    var child by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingSection by rememberSaveable { mutableStateOf(false) }
    val sections = listOf("RECOGNITION" to "语音识别", "READ_ALOUD" to "朗读",
        "CONVERSATION" to "语音对话", "REALTIME" to "豆包实时通话", "PERSONAL" to "我的声音")
    var accountPage by remember { mutableStateOf<VoiceConfiguration?>(null) }
    val saved = key(profile) { runtime.profileConfiguration(profile).collectAsState().value }
    val voices by runtime.personal.state.collectAsState()
    val personalAccounts by runtime.accountConfigurations.collectAsState()
    val accountLoadState by runtime.loadingState.collectAsState()
    val accountVoices by accounts.voices.state.collectAsState()
    val scope = rememberCoroutineScope()
    val drafts = remember { mutableMapOf<VoiceProfile, VoiceConfiguration>() }
    var draft by remember { mutableStateOf(saved ?: VoiceConfiguration()) }
    var cloud by remember { mutableStateOf(preferences.cloudEnabled(profile, draft.engine != "SYSTEM")) }
    val draftBases = remember { mutableMapOf<VoiceProfile, VoiceConfiguration>() }
    var realtimeDirty by remember { mutableStateOf(false) }
    var realtimeCatalog by remember { mutableStateOf(realtimeSpeechVoices) }
    var realtimeRefreshing by remember { mutableStateOf(false) }
    var realtimeCatalogStatus by remember { mutableIntStateOf(0) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var accountReadError by remember { mutableStateOf(false) }
    var sampleName by remember { mutableStateOf("") }
    var sampleText by remember { mutableStateOf("你好，这是我的声音。") }
    var accountKeyDraft by remember { mutableStateOf("") }
    var confirmedMimo by remember { mutableStateOf<VoiceConfiguration?>(null) }
    var mimoBaseRevision by remember { mutableStateOf<String?>(null) }
    var selectedSample by remember { mutableStateOf<VoiceSamplePickerRequest.Selection<android.net.Uri>?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var inputEnabled by remember { mutableStateOf(preferences.enabled("input")) }
    var conversationEnabled by remember { mutableStateOf(preferences.enabled("conversation")) }
    var realtimeEnabled by remember { mutableStateOf(preferences.enabled("realtime")) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val pageCurrent by rememberUpdatedState(isCurrent)
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val picker = remember(runtime) { VoiceSamplePickerRequest<android.net.Uri>() }
    var pickerPending by remember { mutableStateOf(false) }
    var resultRevision by remember { mutableIntStateOf(0) }
    fun current() = alive.get() && pageCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun invalidatePicker() { picker.invalidate(); pickerPending = picker.pending; selectedSample = null }
    fun changeDraft(value: VoiceConfiguration) { invalidatePicker(); draft = value }
    fun stopAudio(): Boolean = try {
        invalidate(); runtime.invalidateSpeech(); remote.invalidate()
        check(ActivityVoiceAudio.gate.releaseHealthy())
        true
    } catch (_: Exception) {
        notice = "音频停止未确认，未更改设置。"
        false
    }
    fun mimoConnection(): VoiceConfiguration? {
        val live = runtime.profileConfiguration(profile).value
        return confirmedMimo?.takeIf { mimoBaseRevision == live?.revision } ?: personalAccounts["MIMO"] ?: live?.takeIf { it.engine == "MIMO" }
    }
    fun recognitionConfiguration() = (saved ?: VoiceConfiguration()).copy(
        dictation = draft.dictation, asrKey = draft.asrKey, asrResource = draft.asrResource)
    fun realtimeConfiguration() = (saved ?: VoiceConfiguration()).copy(
        duplexKey = draft.duplexKey, duplexProvider = draft.duplexProvider, duplexVoice = draft.duplexVoice, instructions = draft.instructions)
    fun save(value: VoiceConfiguration, speechOnly: Boolean = false, onSaved: () -> Unit = {}) {
        if (!current() || busy) return
        val next = if (speechOnly) (saved ?: VoiceConfiguration()).withSpeech(value) else value
        if (next.engine != "SYSTEM" && next.voice.isBlank()) { notice = "请选择音色后保存。"; return }
        val target = profile
        if (!stopAudio()) return
        busy = true
        scope.launch {
            try {
                runtime.save(next, target)
                preferences.rememberCloud(target, next)
                if (speechOnly) preferences.setCloudEnabled(target, next.engine != "SYSTEM")
                if (alive.get()) { onSaved(); notice = "" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (alive.get()) {
                notice = "未保存，请检查模型、接口地址、音色和密钥。"
            } }
            finally { busy = false }
        }
    }
    fun switchFeature(feature: String, enabled: Boolean, update: (Boolean) -> Unit) {
        if (!current() || busy) return
        try { if (stopAudio()) { preferences.setEnabled(feature, enabled); update(enabled) } }
        catch (_: Exception) { notice = "设置未保存" }
    }
    fun leavePage(after: () -> Unit) {
        if (!current() || busy || !stopAudio()) return
        invalidatePicker()
        val pending = when {
            profile == VoiceProfile.READ_ALOUD && realtimeDirty -> realtimeConfiguration()
            else -> null
        }
        if (pending == null) { after(); return }
        val target = profile
        busy = true
        scope.launch {
            try {
                runtime.save(pending, target)
                realtimeDirty = false
                if (current()) after()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "设置未保存，请重试后返回。" }
            finally { busy = false }
        }
    }
    fun leaveChild() = leavePage { child = null; notice = "" }
    DisposableEffect(picker) { onDispose { alive.set(false); picker.close() } }
    LaunchedEffect(profile, saved?.revision) {
        invalidatePicker()
        val previous = draftBases[profile]
        val pending = drafts.remove(profile)
        draft = when {
            pending != null -> if (previous != null && saved != null) mergeVoiceSettingsDraft(previous, pending, saved) else pending
            previous != null && saved != null -> mergeVoiceSettingsDraft(previous, draft, saved)
            else -> saved ?: VoiceConfiguration()
        }
        saved?.let { draftBases[profile] = it }
        cloud = preferences.cloudEnabled(profile, draft.engine != "SYSTEM")
    }
    LaunchedEffect(personalAccounts, lifecycleState, child, accountLoadState, busy, accountReadError) {
        val opened = accountPage
        if (opened != null && current()) {
            val updated = personalAccounts[opened.engine]
            if (updated != null && updated != opened && runtime.accountCurrent(updated)) accountPage = updated
        } else if (opened == null && child == "DOUBAO_VOICES" && !busy && !accountReadError && current()) {
            personalAccounts["DOUBAO"]?.takeIf(runtime::accountCurrent)?.let {
                accountPage = it; child = null
            }
        }
    }
    LaunchedEffect(draft.instructions, realtimeDirty) {
        if (section == "REALTIME" && realtimeDirty && !busy) {
            kotlinx.coroutines.delay(300)
            if (current() && !busy) {
                val pending = realtimeConfiguration()
                save(pending, onSaved = { if (draft.instructions == pending.instructions) realtimeDirty = false })
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        picker.deliver(uri); pickerPending = picker.pending; resultRevision++
    }
    LaunchedEffect(resultRevision, lifecycleState, saved?.revision) {
        selectedSample = picker.take(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            pageCurrent() && child == "MIMO", mimoConnection()) ?: selectedSample
        pickerPending = picker.pending
    }
    PageBackStack(when {
        accountPage != null -> "account:${accountPage!!.engine}"
        child != null -> "child:$child"
        else -> null
    }, if (child == "DOUBAO_ACCOUNT") "child:DOUBAO_VOICES" else null) {
        BackHandler(enabled = accountPage == null, previewEnabled = !choosingSection) {
            if (choosingSection) choosingSection = false
            else if (child == "DOUBAO_ACCOUNT") leavePage { child = "DOUBAO_VOICES"; notice = "" }
            else if (child != null) leaveChild()
            else leavePage { picker.close(); onBack() }
        }
        val accountConfiguration = accountPage ?: personalAccounts["DOUBAO"]
            ?.takeIf { child == "DOUBAO_VOICES" && !busy && !accountReadError && current() && runtime.accountCurrent(it) }
        if (accountConfiguration != null) {
            key(profile, accountConfiguration.revision) {
                ProductionVoiceAccountPage(runtime, accounts, profile, accountConfiguration,
                    isCurrent = isCurrent, installPreviewRevoke = installPreviewRevoke, onBack = { accountPage = null }, standalone = true,
                    onAccountSaved = { updated ->
                        if (isCurrent() && accountPage == accountConfiguration && runtime.accountCurrent(updated)) accountPage = updated
                    })
            }
            return@PageBackStack
        }
        if (child == "OFFLINE") {
            OfflineSpeechSettingsScreen(context, onBack = ::leaveChild, ownerCurrent = ::current)
            return@PageBackStack
        }
        if (child == "DOUBAO_VOICES") {
            if (accountLoadState == ControllerVoiceRuntime.LoadState.LOADED && !busy && !accountReadError) {
                VoicePersonalEmptyPage(isCurrent = ::current,
                    onConfigure = { if (current() && !busy && stopAudio()) { child = "DOUBAO_ACCOUNT" } },
                    onBack = ::leaveChild)
            } else {
                VoicePersonalAccountStatePage(
                    if (accountReadError) ControllerVoiceRuntime.LoadState.FAILED
                    else if (busy) ControllerVoiceRuntime.LoadState.LOADING else accountLoadState,
                    enabled = current() && !busy,
                    onRetry = {
                        if (current() && !busy) {
                            busy = true; accountReadError = false
                            scope.launch {
                                try { runtime.load() }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { if (alive.get()) accountReadError = true }
                                finally { busy = false }
                            }
                        }
                    }, onBack = ::leaveChild)
            }
            return@PageBackStack
        }
        if (child == "DOUBAO_ACCOUNT") {
            SettingsScaffoldPage("声音复刻账户", { leavePage { child = "DOUBAO_VOICES"; notice = "" } }) {
                item { VoiceSettingsCard {
                    SettingsTextField("声音复刻 API Key", accountKeyDraft, { if (!busy) accountKeyDraft = it },
                        password = true, enabled = !busy, compact = true)
                    VoiceAccountSaveButton("保存账户", "voice-account-save",
                        enabled = !busy && accountKeyDraft.isNotBlank(), onClick = {
                        if (current() && !busy && stopAudio()) {
                            val value = VoiceConfiguration(engine = "DOUBAO", provider = "personal-doubao",
                                endpoint = "https://openspeech.bytedance.com", key = accountKeyDraft.trim(),
                                model = "seed-tts-2.0", voice = DoubaoVoices.DEFAULT_ID)
                            busy = true
                            scope.launch {
                                try {
                                    runtime.saveAccount(value)
                                    if (current()) { accountPage = runtime.accountConfigurations.value["DOUBAO"]; child = null; accountKeyDraft = "" }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { notice = "账户未保存，请检查密钥后重试。" }
                                finally { busy = false }
                            }
                        }
                    })
                    SettingsRow("打开豆包控制台", onClick = { if (current()) uriHandler.openUri("https://console.volcengine.com/speech/app") })
                }; SettingsNote("声音复刻 API Key 与音色名额须属于同一项目；不会改动朗读或实时通话的账户。") }
                if (notice.isNotBlank()) item { SettingsNote(notice, error = true) }
            }
            return@PageBackStack
        }
        val pageTitle = when (child) { "ASR" -> "识别方式"; "MIMO" -> "MiMo 语音"; else -> "语音" }
        SettingsScaffold(title = pageTitle, onBack = {
            if (child != null) leaveChild() else leavePage { picker.close(); onBack() }
        }, modifier = Modifier.testTag("voice-settings")) { padding, scrollBehavior, sidePadding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .padding(horizontal = sidePadding).nestedScroll(scrollBehavior.nestedScrollConnection)) {
                if (child == null) Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SettingsCard(Modifier.testTag("voice-section-selector-card")) {
                        Row(Modifier.fillMaxWidth().testTag("voice-section-select").clickable {
                            if (current() && !busy) choosingSection = !choosingSection
                        }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(sections.first { it.first == section }.second, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
                            DaiyuIcon(DaiyuIcons.ChevronDown, contentDescription = "选择语音分组",
                                modifier = Modifier.rotate(if (choosingSection) 180f else 0f))
                        }
                        SettingsExpandableContent(expanded = choosingSection) {
                            Column(Modifier.heightIn(max = 220.dp).padding(bottom = 8.dp).verticalScroll(rememberScrollState())) {
                                sections.forEach { entry ->
                                    Text(entry.second, style = MiuixTheme.textStyles.body2,
                                        modifier = Modifier.fillMaxWidth().testTag("voice-section-option:${entry.first}").clickable {
                                            if (choosingSection) leavePage {
                                                dialog = null; notice = ""
                                                val target = if (entry.first == "CONVERSATION") VoiceProfile.CONVERSATION else VoiceProfile.READ_ALOUD
                                                if (target != profile) drafts[profile] = draft
                                                section = entry.first; choosingSection = false
                                            }
                                        }.padding(horizontal = 12.dp, vertical = 10.dp))
                                }
                            }
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("page:语音设置"),
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (child == "ASR") {
                        item { Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center) { LinuxSettingsTheme { SettingsSegmentedTabs(
                            labels = listOf("本机识别", "豆包识别"),
                            selectedIndex = if (draft.dictation == "DOUBAO") 1 else 0,
                            enabled = !busy,
                            modifier = Modifier.widthIn(max = 240.dp).padding(vertical = 8.dp).testTag("voice-asr-method-card"),
                            tabTag = { if (it == 0) "voice-asr-method:system" else "voice-asr-method:doubao" },
                            onSelect = { index ->
                                if (current() && !busy) {
                                    val method = if (index == 0) "SYSTEM" else "DOUBAO"
                                    changeDraft(draft.copy(dictation = method))
                                    save((saved ?: VoiceConfiguration()).copy(dictation = method))
                                }
                            },
                        ) } } }
                        if (draft.dictation == "DOUBAO") {
                            item { SettingsCard(Modifier.testTag("voice-asr-account-card")) {
                                SettingsSectionTitle("连接豆包账户")
                                SettingsTextField("ASR API Key", draft.asrKey, { if (!busy) changeDraft(draft.copy(asrKey = it)) },
                                    password = true, enabled = !busy, compact = true)
                                TextButton("保存连接设置", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    .fillMaxWidth().testTag("voice-save"),
                                    minHeight = 48.dp, cornerRadius = 24.dp,
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    enabled = !busy && draft.asrKey.isNotBlank(),
                                    onClick = { if (current()) save(recognitionConfiguration().copy(asrKey = draft.asrKey.trim())) })
                                SettingsRow("高级：更换识别服务", tag = "voice-asr-advanced",
                                    showChevron = true, onClick = if (busy) null else ({ if (current()) dialog = "ASR_ADVANCED" }))
                                SettingsRow("打开豆包控制台", tag = "voice-asr-console", showChevron = true,
                                    onClick = { if (current()) uriHandler.openUri("https://console.volcengine.com/speech/app") })
                            } }
                        }
                    } else if (child == "MIMO") {
                        item { VoiceSettingsCard {
                            SettingsRow("MiMo 提供商", summary = mimoConnection()?.let { modelSource.providerName(it.provider) } ?: "请选择提供商",
                                showChevron = true, onClick = if (busy) null else ({ dialog = "MIMO_MODEL" }))
                            SettingsTextField("声音名称", sampleName, { if (!busy) { sampleName = it.take(80); selectedSample = null } }, enabled = !busy)
                            SettingsRow(if (selectedSample == null) "选择 MP3 / WAV 录音" else "录音已选择 · 重新选择",
                                tag = "voice-sample-import", showChevron = true, onClick = if (busy || pickerPending) null else ({
                                    val selected = mimoConnection()
                                    if (current() && sampleName.isNotBlank() && selected != null && picker.begin(selected, sampleName)) {
                                        pickerPending = true
                                        try { importer.launch("audio/*") }
                                        catch (_: Exception) { picker.deliver(null); pickerPending = picker.pending; resultRevision++; notice = "未能打开录音选择器" }
                                    } else notice = "请先选择 MiMo 提供商。"
                                }))
                            TextButton("保存参考声音", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                .fillMaxWidth().testTag("voice-sample-save"),
                                minHeight = 48.dp, cornerRadius = 24.dp,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                enabled = !busy && selectedSample != null, onClick = {
                                val selection = selectedSample
                                if (current() && !busy && selection != null && selection.configuration == mimoConnection()) {
                                    busy = true
                                    scope.launch {
                                        try { runtime.personal.import(context, selection.value, selection.name, selection.configuration.referenceOwner()); selectedSample = null; notice = "参考声音已保存在本机" }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { notice = "录音未保存，请检查名称及 MP3 / WAV 文件。" }
                                        finally { busy = false }
                                    }
                                }
                            })
                        } }
                        item { VoiceSettingsCard(Modifier.testTag("voice-mimo-preview-card")) {
                            SettingsTextField("试听文字", sampleText, { sampleText = it.take(300) }, multiline = true)
                        } }
                        item { SettingsNote("支持 MP3 / WAV，参考录音不超过 7.5 MB。") }
                        items(voices.size, key = { voices[it].id }) { index ->
                            val voice = voices[index]
                            VoiceSettingsCard {
                                SettingsRow(voice.name, summary = "${voice.durationMs / 1000.0} 秒 · " +
                                    if (mimoConnection()?.referenceOwner() == voice.providerId) modelSource.providerName(mimoConnection()!!.provider).orEmpty() else "所属提供商不可用")
                                val connection = mimoConnection()
                                val preview = connection?.takeIf { it.referenceOwner() == voice.providerId }
                                    ?.let { runCatching { runtime.preparePersonalPreview(it, voice.id) }.getOrNull() }
                                VoiceSettingsPreview(runtime, profile, !busy && preview != null && sampleText.isNotBlank(),
                                    ::current, installPreviewRevoke, sampleText, configuration = preview?.configuration,
                                    personalPreview = preview, embedded = true)
                                SettingsRow("用于朗读", showChevron = true, onClick = if (busy || mimoConnection()?.referenceOwner() != voice.providerId) null else ({
                                    mimoConnection()?.let { save(it.copy(voice = voice.id, model = app.daiyu.android.voice.personal.MimoPersonalVoices.MODEL), speechOnly = true) }
                                }))
                                SettingsRow("删除", showChevron = true, onClick = if (busy) null else ({ removing = voice.id }))
                            }
                        }
                    } else when (section) {
                        "RECOGNITION" -> {
                            item { SettingsCard(Modifier.testTag("voice-recognition-input-card")) {
                                SettingsSwitchRow("启用语音转文字", inputEnabled, busy = busy, tag = "voice-input-enable",
                                    summary = "点击聊天输入框的语音按钮，把说话变成文字。") { switchFeature("input", it) { inputEnabled = it } }
                            } }
                            item { OfflineSpeechPackCard(context, ::current) }
                            item { SettingsCard(Modifier.testTag("voice-recognition-method-card")) { SettingsRow("识别方式", tag = "voice-recognition-method",
                                summary = if (saved?.dictation == "DOUBAO") "豆包识别 · 需要联网" else "本机识别 · 无需账户",
                                showChevron = true, onClick = if (busy) null else ({ if (current() && stopAudio()) { child = "ASR" } })) } }
                        }
                        "READ_ALOUD", "CONVERSATION" -> {
                            if (section == "CONVERSATION") item { VoiceSettingsCard {
                                SettingsSwitchRow("启用语音对话", conversationEnabled, busy = busy) { switchFeature("conversation", it) { conversationEnabled = it } }
                            } }
                            if (section == "CONVERSATION" && draft.dictation != "DOUBAO") item { OfflineSpeechPackCard(context, ::current, conversationStyle = true) }
                            if (section == "CONVERSATION") item { SettingsCard(Modifier.testTag("voice-recognition-method-card")) { SettingsRow("识别方式", tag = "voice-recognition-method",
                                summary = if (saved?.dictation == "DOUBAO") "豆包识别 · 需要联网" else "本机识别 · 无需账户",
                                showChevron = true, onClick = if (busy) null else ({ if (current() && stopAudio()) { child = "ASR" } })) } }
                            item { VoiceSettingsCard {
                                SettingsSwitchRow("云端朗读", cloud, busy = busy, tag = "voice-cloud-enable", summary = "关闭时使用系统语音。") { enabled ->
                                    if (current() && !busy && stopAudio()) {
                                        if (!enabled) {
                                            preferences.rememberCloud(profile, draft)
                                            save(draft.copy(engine = "SYSTEM", voice = ""), speechOnly = true,
                                                onSaved = { cloud = false })
                                        } else {
                                            val engine = preferences.cloudEngine(profile)
                                            val voice = preferences.cloudVoice(profile, draft.referenceOwner(), draft.historyModel)
                                            if (engine != null && voice.isNotBlank()) {
                                                save(draft.copy(engine = engine, voice = voice), speechOnly = true,
                                                    onSaved = { cloud = true })
                                            } else {
                                                preferences.setCloudEnabled(profile, true); cloud = true
                                            }
                                        }
                                    }
                                }
                            } }
                            if (cloud) item { VoiceSettingsCard {
                                SettingsRow("朗读模型", summary = draft.model.takeIf { it.isNotBlank() }?.let { draft.modelLabel.ifBlank { "${modelSource.providerName(draft.provider).orEmpty()} · $it" } } ?: "选择朗读模型",
                                    tag = "voice-model-open", showChevron = true, onClick = if (busy) null else ({ dialog = "MODEL" }))
                                val catalog = SpeechEngine.entries.firstOrNull { it.name == draft.engine }?.let { SpeechVoices.catalog(it, draft.model) }.orEmpty()
                                SettingsRow("音色", summary = catalog.firstOrNull { it.id == draft.voice }?.name ?: draft.voice.ifBlank { "选择音色" },
                                    tag = "voice-voice-open", showChevron = true, onClick = if (busy || draft.model.isBlank() || (catalog.isEmpty() && draft.engine != "FISH")) null
                                        else ({ dialog = if (draft.engine == "FISH") "VOICE_MANUAL" else "VOICE" }))
                            } }
                            item { VoiceSettingsPreview(runtime, profile,
                                !busy && ((saved == null && !cloud && draft.engine == "SYSTEM") || (saved?.withSpeech(draft) == saved && (!cloud || draft.engine != "SYSTEM"))),
                                ::current, installPreviewRevoke, configuration = saved,
                                onInitialize = if (saved == null && !cloud && draft.engine == "SYSTEM") ({ save(draft) }) else null) }
                        }
                        "REALTIME" -> item { VoiceSettingsCard {
                            SettingsSwitchRow("启用豆包实时通话", realtimeEnabled, busy = busy) { switchFeature("realtime", it) { realtimeEnabled = it } }
                            SettingsRow("提供商", summary = modelSource.providerName(draft.duplexProvider) ?: "选择豆包语音提供商",
                                showChevron = true, onClick = if (busy) null else ({ dialog = "DUPLEX_PROVIDER" }))
                            SettingsRow("选择音色", summary = realtimeCatalog.firstOrNull { it.id == draft.duplexVoice }?.name
                                ?: accountVoices.firstOrNull { it.id == draft.duplexVoice }?.name ?: "选择音色",
                                showChevron = true, onClick = if (busy) null else ({ dialog = "DUPLEX_VOICE" }))
                            SettingsRow(if (realtimeRefreshing) "正在刷新…" else "刷新音色", summary = when (realtimeCatalogStatus) {
                                1 -> "音色列表已更新"; 2 -> "更新失败，保留当前目录"; else -> "使用内置音色列表"
                            }, showChevron = true, onClick = if (realtimeRefreshing || busy) null else ({
                                if (current()) {
                                    realtimeRefreshing = true
                                    scope.launch {
                                        try {
                                            val updated = refreshRealtimeSpeechVoices()
                                            if (current()) { realtimeCatalog = updated; realtimeCatalogStatus = 1 }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { if (current()) realtimeCatalogStatus = 2 }
                                        finally { realtimeRefreshing = false }
                                    }
                                }
                            }))
                            SettingsTextField("角色说明", draft.instructions, { if (!busy) { changeDraft(draft.copy(instructions = it)); realtimeDirty = true } }, multiline = true, enabled = !busy)
                        } }
                        "PERSONAL" -> item { VoiceSettingsCard {
                            SettingsRow("豆包语音", tag = "voice-account-open", showChevron = true, onClick = if (busy) null else ({
                                if (current() && stopAudio()) {
                                    invalidatePicker()
                                    val selected = personalAccounts["DOUBAO"]
                                    if (selected != null && runtime.accountCurrent(selected)) accountPage = selected
                                    else child = "DOUBAO_VOICES"
                                }
                            }))
                            SettingsRow("MiMo 语音", tag = "voice-mimo-open", showChevron = true, onClick = if (busy) null else ({ if (current() && stopAudio()) { child = "MIMO"; notice = "" } }))
                        } }
                    }
                    if (notice.isNotBlank()) item { SettingsNote(notice, error = notice.startsWith("未")) }
                }
            }
        }
        key(profile, dialog) { when (dialog) {
            "MODEL" -> VoiceModelDialog(draft, modelSource, { current() && !busy }, onSave = { value ->
                if (current() && !busy) {
                    preferences.rememberCloud(profile, draft)
                    val catalog = SpeechVoices.catalog(SpeechEngine.valueOf(value.engine), value.model)
                    val history = preferences.cloudVoice(profile, value.referenceOwner(), value.historyModel)
                    val selected = value.copy(voice = value.voice.ifBlank { history.ifBlank { catalog.firstOrNull()?.id.orEmpty() } })
                    cloud = true; changeDraft(selected)
                    if (selected.voice.isNotBlank()) save(selected, speechOnly = true)
                }
            }, onDismiss = { dialog = null })
            "MIMO_MODEL", "DOUBAO_MODEL", "DUPLEX_PROVIDER" -> {
                val kind = if (dialog == "MIMO_MODEL") "MIMO" else "DOUBAO"
                VoiceProviderDialog(if (dialog == "DUPLEX_PROVIDER") "提供商" else if (kind == "MIMO") "MiMo 提供商" else "声音复刻账户",
                    kind, modelSource, if (dialog == "DUPLEX_PROVIDER") draft.duplexProvider else personalAccounts[kind]?.provider.orEmpty(),
                    { current() && !busy }, onSelect = { choice, settings ->
                        if (current() && !busy && stopAudio()) {
                            if (dialog == "DUPLEX_PROVIDER") {
                                save(realtimeConfiguration().copy(duplexKey = settings.apiKey, duplexProvider = choice.provider.slotId))
                            } else {
                                val value = VoiceConfiguration(engine = kind, provider = choice.provider.slotId,
                                    endpoint = settings.endpoint, key = settings.apiKey, customHeaders = settings.customHeaders,
                                    model = if (kind == "MIMO") "mimo-v2.5-tts" else "seed-tts-2.0",
                                    voice = if (kind == "MIMO") "mimo_default" else DoubaoVoices.DEFAULT_ID)
                                if (kind == "DOUBAO") dialog = null
                                busy = true
                                scope.launch {
                                    try {
                                        runtime.saveAccount(value)
                                        if (current()) {
                                            if (kind == "DOUBAO") accountPage = runtime.accountConfigurations.value[kind]
                                            else { confirmedMimo = null; notice = "" }
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { notice = "账户未保存，请重新选择。" }
                                    finally { busy = false }
                                }
                            }
                        }
                    }, onDismiss = { dialog = null })
            }
            "VOICE_MANUAL" -> VoiceTextDialog("Fish 音色 ID", draft.voice, onSave = {
                if (current() && it.isNotBlank()) { dialog = null; save(draft.copy(voice = it.trim()), speechOnly = true) }
            }, onDismiss = { dialog = null })
            "VOICE" -> VoiceToneDialog("音色", draft.voice, buildList {
                SpeechEngine.entries.firstOrNull { it.name == draft.engine }?.let { engine ->
                    addAll(SpeechVoices.catalog(engine, draft.model).map { it.id to it.name })
                }
                if (draft.engine == "MIMO") voices.filter { it.providerId == draft.referenceOwner() }.forEach { add(it.id to it.name) }
                if (draft.engine == "DOUBAO") {
                    val identity = runCatching { app.daiyu.android.voice.account.VoiceAccountIdentity.from(draft.speech()).fingerprint }.getOrNull()
                    accountVoices.filter { it.account == identity && it.tts && it.accepted }.forEach { add(it.id to it.name) }
                }
            }, onSelect = { if (current() && !busy) save(draft.copy(voice = it), speechOnly = true) }, onDismiss = { dialog = null })
            "ASR_ADVANCED" -> ChatSelectionSheet("识别服务", onDismiss = { dialog = null },
                subtitle = "仅在控制台开通了不同服务时更改。", showCloseButton = false, titleFontSize = 20.sp, subtitleFontSize = 14.sp) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("voice-asr-service-sheet")) {
                    DoubaoAsrProtocol.resources.forEachIndexed { index, resource ->
                        SettingsRadioRow(listOf("识别 2.0 · 按时长", "识别 2.0 · 按并发", "识别 1.0 · 按时长", "识别 1.0 · 按并发")[index],
                            draft.asrResource == resource, !busy) {
                            if (current() && !busy) {
                                changeDraft(draft.copy(asrResource = resource))
                                save((saved ?: VoiceConfiguration()).copy(asrResource = resource))
                            }
                        }
                    }
                }
            }
            "DUPLEX_VOICE" -> VoiceToneDialog("选择音色", draft.duplexVoice,
                realtimeCatalog.map { it.id to it.name } + accountVoices.filter {
                    it.tts && it.accepted && runCatching { it.account == app.daiyu.android.voice.account.VoiceAccountIdentity
                        .from(SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", draft.duplexKey, "seed-tts-2.0", draft.duplexProvider)).fingerprint }.getOrDefault(false)
                }.map { it.id to it.name },
                onSelect = { if (current() && !busy) save(realtimeConfiguration().copy(duplexVoice = it)) }, onDismiss = { dialog = null })
        }
        }
        removing?.let { id ->
            DaiyuConfirmDialog("删除参考声音？", "删除本机录音与记录。若正在用于朗读，需要重新选择。", "删除", true,
                onConfirm = {
                    removing = null
                    if (current() && !busy && stopAudio()) {
                        busy = true
                        scope.launch {
                            try { runtime.removePersonal(id); notice = "参考声音已删除" }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { notice = "删除未确认" }
                            finally { busy = false }
                        }
                    }
                }, onDismiss = { removing = null })
        }
    }
}
