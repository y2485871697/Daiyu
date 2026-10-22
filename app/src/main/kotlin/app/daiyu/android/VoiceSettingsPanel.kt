package app.daiyu.android

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.ui.screens.settings.SettingsScaffold
import app.daiyu.android.ui.screens.settings.*
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.voice.ActivityVoiceAudio
import app.daiyu.android.voice.duplex.DoubaoDuplexProtocol
import app.daiyu.android.voice.offline.OfflineSpeechPack
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
    val pack by OfflineSpeechPack.state.collectAsStateWithLifecycle()
    val accountVoices by accounts.voices.state.collectAsState()
    val scope = rememberCoroutineScope()
    val drafts = remember { mutableMapOf<VoiceProfile, VoiceConfiguration>() }
    var draft by remember { mutableStateOf(saved ?: VoiceConfiguration()) }
    var cloud by remember { mutableStateOf(preferences.cloudEnabled(profile, draft.engine != "SYSTEM")) }
    val draftBases = remember { mutableMapOf<VoiceProfile, VoiceConfiguration>() }
    var asrAdvanced by rememberSaveable { mutableStateOf(false) }
    var conversationAsrDirty by remember { mutableStateOf(false) }
    var realtimeDirty by remember { mutableStateOf(false) }
    var realtimeCatalog by remember { mutableStateOf(realtimeSpeechVoices) }
    var realtimeRefreshing by remember { mutableStateOf(false) }
    var realtimeCatalogStatus by remember { mutableIntStateOf(0) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
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
                if (alive.get()) { onSaved(); notice = "已保存" }
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
            profile == VoiceProfile.CONVERSATION && conversationAsrDirty -> recognitionConfiguration().copy(asrKey = draft.asrKey.trim())
            profile == VoiceProfile.READ_ALOUD && realtimeDirty -> realtimeConfiguration()
            else -> null
        }
        if (pending == null) { after(); return }
        val target = profile
        busy = true
        scope.launch {
            try {
                runtime.save(pending, target)
                conversationAsrDirty = false; realtimeDirty = false
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
    LaunchedEffect(profile, draft.asrKey, draft.asrResource, conversationAsrDirty) {
        if (profile == VoiceProfile.CONVERSATION && conversationAsrDirty && !busy) {
            kotlinx.coroutines.delay(300)
            if (current() && !busy) {
                val pending = recognitionConfiguration().copy(asrKey = draft.asrKey.trim())
                save(pending, onSaved = {
                    if (draft.asrKey.trim() == pending.asrKey && draft.asrResource == pending.asrResource) conversationAsrDirty = false
                })
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
    BackHandler(enabled = accountPage == null) {
        if (choosingSection) choosingSection = false
        else if (child != null) leaveChild()
        else leavePage { picker.close(); onBack() }
    }
    val accountConfiguration = accountPage
    if (accountConfiguration != null) {
        key(profile, accountConfiguration.revision) {
            ProductionVoiceAccountPage(runtime, accounts, profile, accountConfiguration,
                isCurrent = isCurrent, installPreviewRevoke = installPreviewRevoke, onBack = { accountPage = null }, standalone = true)
        }
        return
    }
    if (child == "OFFLINE") {
        OfflineSpeechSettingsScreen(context, onBack = ::leaveChild, ownerCurrent = ::current)
        return
    }
    if (child == "DOUBAO_ACCOUNT") {
        SettingsScaffoldPage("声音复刻账户", ::leaveChild) {
            item { VoiceSettingsCard {
                SettingsTextField("声音复刻 API Key", accountKeyDraft, { accountKeyDraft = it }, password = true)
                SettingsRow("保存账户", onClick = if (busy || accountKeyDraft.isBlank()) null else ({
                    if (current() && stopAudio()) {
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
                }))
                SettingsRow("打开豆包控制台", onClick = { if (current()) uriHandler.openUri("https://console.volcengine.com/speech/app") })
            }; SettingsNote("声音复刻 API Key 与音色名额须属于同一项目；不会改动朗读或实时通话的账户。") }
            if (notice.isNotBlank()) item { SettingsNote(notice, error = true) }
        }
        return
    }
    val pageTitle = when (child) { "ASR" -> "识别方式"; "MIMO" -> "MiMo 语音"; else -> "语音" }
    SettingsScaffold(title = pageTitle, onBack = {
        if (child != null) leaveChild() else leavePage { picker.close(); onBack() }
    }, modifier = Modifier.testTag("voice-settings")) { padding, scrollBehavior, sidePadding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .padding(horizontal = sidePadding).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            if (child == null) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.fillMaxWidth().testTag("voice-section-select").clickable {
                    if (current() && !busy) choosingSection = !choosingSection
                }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sections.first { it.first == section }.second, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
                    DaiyuIcon(DaiyuIcons.ChevronDown, contentDescription = "选择语音分组",
                        modifier = Modifier.rotate(if (choosingSection) 180f else 0f))
                }
                if (choosingSection) Column(Modifier.heightIn(max = 220.dp).padding(bottom = 8.dp).verticalScroll(rememberScrollState())) {
                    sections.forEach { entry ->
                        Text(entry.second, style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.fillMaxWidth().clickable {
                                leavePage {
                                    dialog = null; notice = ""
                                    val target = if (entry.first == "CONVERSATION") VoiceProfile.CONVERSATION else VoiceProfile.READ_ALOUD
                                    if (target != profile) drafts[profile] = draft
                                    section = entry.first; choosingSection = false
                                }
                            }.padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("page:语音设置"),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                if (child == "ASR") {
                    item { VoiceSettingsCard {
                        Row(Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f)) { SettingsRadioRow("本机识别", draft.dictation != "DOUBAO", !busy) {
                                if (current() && !busy) {
                                    changeDraft(draft.copy(dictation = "SYSTEM"))
                                    save((saved ?: VoiceConfiguration()).copy(dictation = "SYSTEM"))
                                }
                            } }
                            Box(Modifier.weight(1f)) { SettingsRadioRow("豆包识别", draft.dictation == "DOUBAO", !busy) {
                                if (current() && !busy) {
                                    changeDraft(draft.copy(dictation = "DOUBAO"))
                                    save((saved ?: VoiceConfiguration()).copy(dictation = "DOUBAO"))
                                }
                            } }
                        }
                    } }
                    if (draft.dictation == "DOUBAO") {
                        item { VoiceSettingsCard {
                            SettingsSectionTitle("连接豆包账户")
                            SettingsNote("识别音频会发送到豆包；与朗读、实时对话分别配置。")
                            SettingsTextField("ASR API Key", draft.asrKey, { if (!busy) changeDraft(draft.copy(asrKey = it)) }, password = true, enabled = !busy)
                            SettingsRow(if (asrAdvanced) "收起高级设置" else "高级：更换识别服务",
                                onClick = if (busy) null else ({ asrAdvanced = !asrAdvanced }))
                            if (asrAdvanced) {
                                SettingsNote("仅在控制台开通了不同服务时更改。")
                                DoubaoAsrProtocol.resources.forEachIndexed { index, resource ->
                                    SettingsRadioRow(listOf("识别 2.0 · 按时长", "识别 2.0 · 按并发", "识别 1.0 · 按时长", "识别 1.0 · 按并发")[index],
                                        draft.asrResource == resource, !busy) {
                                        if (current()) {
                                            changeDraft(draft.copy(asrResource = resource))
                                            save((saved ?: VoiceConfiguration()).copy(asrResource = resource))
                                        }
                                    }
                                }
                            }
                            SettingsRow("保存连接设置", tag = "voice-save", onClick = if (busy || draft.asrKey.isBlank()) null else ({
                                if (current()) save(recognitionConfiguration().copy(asrKey = draft.asrKey.trim()))
                            }))
                            SettingsRow("打开豆包控制台", onClick = { if (current()) uriHandler.openUri("https://console.volcengine.com/speech/app") })
                        } }
                    } else item { SettingsNote("无需账户。上一页下载语音包后，即可离线识别。") }
                } else if (child == "MIMO") {
                    item { VoiceSettingsCard {
                        SettingsRow("MiMo 提供商", summary = mimoConnection()?.let { modelSource.providerName(it.provider) } ?: "请选择提供商",
                            onClick = if (busy) null else ({ dialog = "MIMO_MODEL" }))
                        SettingsTextField("声音名称", sampleName, { if (!busy) { sampleName = it.take(80); selectedSample = null } }, enabled = !busy)
                        SettingsRow(if (selectedSample == null) "选择 MP3 / WAV 录音" else "录音已选择 · 重新选择",
                            tag = "voice-sample-import", onClick = if (busy || pickerPending) null else ({
                                val selected = mimoConnection()
                                if (current() && sampleName.isNotBlank() && selected != null && picker.begin(selected, sampleName)) {
                                    pickerPending = true
                                    try { importer.launch("audio/*") }
                                    catch (_: Exception) { picker.deliver(null); pickerPending = picker.pending; resultRevision++; notice = "未能打开录音选择器" }
                                } else notice = "请先选择 MiMo 提供商。"
                            }))
                        SettingsRow("保存参考声音", tag = "voice-sample-save", onClick = if (busy || selectedSample == null) null else ({
                            val selection = selectedSample
                            if (current() && selection != null && selection.configuration == mimoConnection()) {
                                busy = true
                                scope.launch {
                                    try { runtime.personal.import(context, selection.value, selection.name, selection.configuration.referenceOwner()); selectedSample = null; notice = "参考声音已保存在本机" }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { notice = "录音未保存，请检查名称及 MP3 / WAV 文件。" }
                                    finally { busy = false }
                                }
                            }
                        }))
                    }; SettingsTextField("试听文字", sampleText, { sampleText = it.take(300) }, multiline = true)
                    SettingsNote("支持 MP3 / WAV，参考录音不超过 7.5 MB。导入仅保存在本机；试听与朗读时，会在单次确认后把参考录音和文字发送到所选 MiMo 提供商。") }
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
                            SettingsRow("用于朗读", onClick = if (busy || mimoConnection()?.referenceOwner() != voice.providerId) null else ({
                                mimoConnection()?.let { save(it.copy(voice = voice.id, model = app.daiyu.android.voice.personal.MimoPersonalVoices.MODEL), speechOnly = true) }
                            }))
                            SettingsRow("删除", onClick = if (busy) null else ({ removing = voice.id }))
                        }
                    }
                } else when (section) {
                    "RECOGNITION" -> {
                        item { VoiceSettingsCard {
                            SettingsSwitchRow("启用语音转文字", inputEnabled, busy = busy, tag = "voice-input-enable",
                                summary = "点击聊天输入框的语音按钮，把说话变成文字。") { switchFeature("input", it) { inputEnabled = it } }
                            SettingsRow("离线语音", tag = "voice-offline-settings", onClick = if (busy) null else ({
                                if (current() && !busy && stopAudio()) { invalidatePicker(); child = "OFFLINE" }
                            }))
                        } }
                        item { OfflineSpeechPackCard(context, ::current) }
                        item { VoiceSettingsCard { SettingsRow("识别方式", tag = "voice-recognition-method",
                            summary = if (saved?.dictation == "DOUBAO") "豆包识别 · 需要联网" else "本机识别 · 无需账户",
                            onClick = if (busy) null else ({ if (current() && stopAudio()) { child = "ASR" } })) } }
                        if (pack.ready && draft.dictation != "DOUBAO") item { VoiceSettingsCard { SettingsSwitchRow("离线听写", pack.enabled,
                            tag = "offline-speech-enable") { if (current()) OfflineSpeechPack.setEnabled(context, it) } } }
                    }
                    "READ_ALOUD", "CONVERSATION" -> {
                        if (section == "CONVERSATION") item { VoiceSettingsCard {
                            SettingsSwitchRow("启用语音对话", conversationEnabled, busy = busy) { switchFeature("conversation", it) { conversationEnabled = it } }
                            SettingsSwitchRow("豆包语音识别", draft.dictation == "DOUBAO", busy = busy) {
                                if (current() && stopAudio()) {
                                    val method = if (it) "DOUBAO" else "SYSTEM"
                                    changeDraft(draft.copy(dictation = method))
                                    save((saved ?: VoiceConfiguration()).copy(dictation = method))
                                }
                            }
                            if (draft.dictation == "DOUBAO") {
                                SettingsTextField("ASR API Key", draft.asrKey, { if (!busy) { changeDraft(draft.copy(asrKey = it)); conversationAsrDirty = true } }, password = true, enabled = !busy)
                                SettingsRow("识别服务", summary = draft.asrResource.ifBlank { "请选择" }, onClick = if (busy) null else ({ dialog = "ASR_RESOURCE" }))
                            }
                        } }
                        if (section == "CONVERSATION" && draft.dictation != "DOUBAO") item { OfflineSpeechPackCard(context, ::current) }
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
                                tag = "voice-model-open", onClick = if (busy) null else ({ dialog = "MODEL" }))
                            val catalog = SpeechEngine.entries.firstOrNull { it.name == draft.engine }?.let { SpeechVoices.catalog(it, draft.model) }.orEmpty()
                            SettingsRow("音色", summary = catalog.firstOrNull { it.id == draft.voice }?.name ?: draft.voice.ifBlank { "选择音色" },
                                tag = "voice-voice-open", onClick = if (busy || draft.model.isBlank() || (catalog.isEmpty() && draft.engine != "FISH")) null
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
                            onClick = if (busy) null else ({ dialog = "DUPLEX_PROVIDER" }))
                        SettingsRow("选择音色", summary = realtimeCatalog.firstOrNull { it.id == draft.duplexVoice }?.name
                            ?: accountVoices.firstOrNull { it.id == draft.duplexVoice }?.name ?: "选择音色",
                            onClick = if (busy) null else ({ dialog = "DUPLEX_VOICE" }))
                        SettingsRow(if (realtimeRefreshing) "正在刷新…" else "刷新音色", summary = when (realtimeCatalogStatus) {
                            1 -> "音色列表已更新"; 2 -> "更新失败，保留当前目录"; else -> "使用内置音色列表"
                        }, onClick = if (realtimeRefreshing || busy) null else ({
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
                        SettingsRow("豆包语音", tag = "voice-account-open", onClick = if (busy) null else ({
                            if (current() && stopAudio()) {
                                invalidatePicker()
                                val selected = personalAccounts["DOUBAO"]
                                if (selected != null && runtime.accountCurrent(selected)) accountPage = selected
                                else child = "DOUBAO_ACCOUNT"
                            }
                        }))
                        SettingsRow("MiMo 语音", tag = "voice-mimo-open", onClick = if (busy) null else ({ if (current() && stopAudio()) { child = "MIMO"; notice = "" } }))
                    } }
                }
                if (notice.isNotBlank()) item { SettingsNote(notice, error = notice.startsWith("未")) }
            }
        }
    }
    key(profile, saved?.revision, dialog) { when (dialog) {
        "MODEL" -> VoiceModelDialog(draft, modelSource, ::current, onSave = { value ->
            if (current()) {
                preferences.rememberCloud(profile, draft)
                val catalog = SpeechVoices.catalog(SpeechEngine.valueOf(value.engine), value.model)
                val history = preferences.cloudVoice(profile, value.referenceOwner(), value.historyModel)
                val selected = value.copy(voice = value.voice.ifBlank { history.ifBlank { catalog.firstOrNull()?.id.orEmpty() } })
                cloud = true; changeDraft(selected); dialog = null
                if (selected.voice.isNotBlank()) save(selected, speechOnly = true)
            }
        }, onDismiss = { dialog = null })
        "MIMO_MODEL", "DOUBAO_MODEL", "DUPLEX_PROVIDER" -> {
            val kind = if (dialog == "MIMO_MODEL") "MIMO" else "DOUBAO"
            VoiceProviderDialog(if (dialog == "DUPLEX_PROVIDER") "提供商" else if (kind == "MIMO") "MiMo 提供商" else "声音复刻账户",
                kind, modelSource, if (dialog == "DUPLEX_PROVIDER") draft.duplexProvider else personalAccounts[kind]?.provider.orEmpty(),
                ::current, onSelect = { choice, settings ->
                    if (current() && !busy && stopAudio()) {
                        if (dialog == "DUPLEX_PROVIDER") {
                            dialog = null
                            save(realtimeConfiguration().copy(duplexKey = settings.apiKey, duplexProvider = choice.provider.slotId))
                        } else {
                            val value = VoiceConfiguration(engine = kind, provider = choice.provider.slotId,
                                endpoint = settings.endpoint, key = settings.apiKey, customHeaders = settings.customHeaders,
                                model = if (kind == "MIMO") "mimo-v2.5-tts" else "seed-tts-2.0",
                                voice = if (kind == "MIMO") "mimo_default" else DoubaoVoices.DEFAULT_ID)
                            dialog = null; busy = true
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
        }, onSelect = { if (current()) { dialog = null; save(draft.copy(voice = it), speechOnly = true) } }, onDismiss = { dialog = null })
        "ASR_RESOURCE" -> VoiceChoiceDialog("识别资源", DoubaoAsrProtocol.resources.map { it to it }, draft.asrResource,
            onSelect = { if (current()) { changeDraft(draft.copy(asrResource = it)); dialog = null; save((saved ?: VoiceConfiguration()).copy(asrResource = it)) } }, onDismiss = { dialog = null })
        "DUPLEX_VOICE" -> VoiceToneDialog("选择音色", draft.duplexVoice,
            realtimeCatalog.map { it.id to it.name } + accountVoices.filter {
                it.tts && it.accepted && runCatching { it.account == app.daiyu.android.voice.account.VoiceAccountIdentity
                    .from(SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", draft.duplexKey, "seed-tts-2.0", draft.duplexProvider)).fingerprint }.getOrDefault(false)
            }.map { it.id to it.name },
            onSelect = { if (current()) { dialog = null; save(realtimeConfiguration().copy(duplexVoice = it)) } }, onDismiss = { dialog = null })
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
