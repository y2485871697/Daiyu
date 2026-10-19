package app.daiyu.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import app.daiyu.android.ui.components.MiuixScaffold
import app.daiyu.android.ui.screens.settings.*
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
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
    onOpenOfflineSpeech: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { VoiceSettingsPreferences(context) }
    var profile by remember { mutableStateOf(VoiceProfile.READ_ALOUD) }
    var section by remember { mutableStateOf("RECOGNITION") }
    var child by remember { mutableStateOf<String?>(null) }
    var choosingSection by remember { mutableStateOf(false) }
    val sections = listOf("RECOGNITION" to "语音识别", "READ_ALOUD" to "朗读",
        "CONVERSATION" to "语音会话", "REALTIME" to "实时通话", "PERSONAL" to "个人声音")
    var accountPage by remember { mutableStateOf<VoiceConfiguration?>(null) }
    val saved = key(profile) { runtime.profileConfiguration(profile).collectAsState().value }
    val voices by runtime.personal.state.collectAsState()
    val pack by OfflineSpeechPack.state.collectAsStateWithLifecycle()
    val accountVoices by accounts.voices.state.collectAsState()
    val scope = rememberCoroutineScope()
    val drafts = remember { mutableMapOf<VoiceProfile, VoiceConfiguration>() }
    var draft by remember { mutableStateOf(saved ?: VoiceConfiguration()) }
    var cloud by remember { mutableStateOf(draft.engine != "SYSTEM") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sampleName by remember { mutableStateOf("") }
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
        return confirmedMimo?.takeIf { mimoBaseRevision == live?.revision } ?: live?.takeIf { it.engine == "MIMO" }
    }
    fun recognitionConfiguration() = (saved ?: VoiceConfiguration()).copy(
        dictation = draft.dictation, asrKey = draft.asrKey, asrResource = draft.asrResource)
    fun realtimeConfiguration() = (saved ?: VoiceConfiguration()).copy(
        duplexKey = draft.duplexKey, duplexVoice = draft.duplexVoice, instructions = draft.instructions)
    fun save(value: VoiceConfiguration, disableOffline: Boolean = false) {
        if (!current() || busy) return
        changeDraft(value)
        if (value.engine != "SYSTEM" && value.voice.isBlank()) { notice = "请选择音色后保存。"; return }
        val target = profile
        val previous = saved
        if (!stopAudio()) return
        busy = true
        scope.launch {
            try {
                runtime.save(value, target)
                preferences.rememberCloud(target, value)
                if (disableOffline) OfflineSpeechPack.setEnabled(context, false)
                if (alive.get()) notice = "已保存"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (alive.get()) {
                cloud = previous?.engine?.let { it != "SYSTEM" } ?: false
                draft = previous ?: VoiceConfiguration()
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
    fun leaveChild() { if (current() && !busy && stopAudio()) { invalidatePicker(); child = null; notice = "" } }
    DisposableEffect(picker) { onDispose { alive.set(false); picker.close() } }
    LaunchedEffect(profile, saved?.revision) {
        invalidatePicker()
        draft = drafts.remove(profile) ?: saved ?: VoiceConfiguration()
        cloud = draft.engine != "SYSTEM"
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        picker.deliver(uri); pickerPending = picker.pending; resultRevision++
    }
    LaunchedEffect(resultRevision, lifecycleState, saved?.revision) {
        selectedSample = picker.take(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            pageCurrent() && child == "MIMO", mimoConnection()) ?: selectedSample
        pickerPending = picker.pending
    }
    BackHandler(enabled = accountPage == null && (child != null || choosingSection)) {
        if (choosingSection) choosingSection = false else leaveChild()
    }
    val accountConfiguration = accountPage
    if (accountConfiguration != null) {
        key(profile, accountConfiguration.revision) {
            ProductionVoiceAccountPage(runtime, accounts, profile, accountConfiguration,
                isCurrent = isCurrent, installPreviewRevoke = installPreviewRevoke, onBack = { accountPage = null })
        }
        return
    }
    val pageTitle = when (child) { "ASR" -> "识别方式"; "MIMO" -> "MiMo 语音"; else -> "语音设置" }
    MiuixScaffold(title = pageTitle, onBack = {
        if (current() && !busy) {
            if (child != null) leaveChild() else if (stopAudio()) { picker.close(); onBack() }
        }
    }, modifier = Modifier.testTag("voice-settings")) { padding, scrollBehavior, sidePadding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .padding(horizontal = sidePadding).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            if (child == null) SettingsCard {
                SettingsRow(sections.first { it.first == section }.second,
                    onClick = if (busy) null else ({ if (current()) choosingSection = !choosingSection }),
                    tag = "voice-section-select", trailing = {
                        DaiyuIcon(if (choosingSection) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                            contentDescription = if (choosingSection) "收起分组" else "展开分组")
                    })
                if (choosingSection) Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    sections.forEach { entry ->
                        SettingsRadioRow(entry.second, section == entry.first, enabled = !busy) {
                            if (current() && !busy && stopAudio()) {
                                invalidatePicker(); dialog = null; notice = ""
                                val target = if (entry.first == "CONVERSATION") VoiceProfile.CONVERSATION else VoiceProfile.READ_ALOUD
                                if (target != profile) { drafts[profile] = draft; profile = target }
                                section = entry.first; choosingSection = false
                            }
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("page:语音设置"),
                contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (child == "ASR") {
                    item { SettingsCard {
                        SettingsRadioRow("本机识别", draft.dictation != "DOUBAO", !busy) {
                            if (current() && !busy) { changeDraft(draft.copy(dictation = "SYSTEM")); dialog = null }
                        }
                        SettingsRadioRow("豆包识别", draft.dictation == "DOUBAO", !busy) {
                            if (current() && !busy) changeDraft(draft.copy(dictation = "DOUBAO"))
                        }
                    } }
                    if (draft.dictation == "DOUBAO") {
                        item { SettingsCard {
                            SettingsSectionTitle("连接豆包账户")
                            SettingsTextField("ASR API Key", draft.asrKey, { if (!busy) changeDraft(draft.copy(asrKey = it)) }, password = true, enabled = !busy)
                            SettingsRow("识别资源", summary = draft.asrResource.ifBlank { "请选择" }, onClick = if (busy) null else ({ dialog = "ASR_RESOURCE" }))
                            SettingsRow("保存连接设置", tag = "voice-save", onClick = if (busy) null else ({
                                if (current()) save(recognitionConfiguration(), disableOffline = true)
                            }))
                        }; SettingsNote("语音会发送给豆包识别服务；此连接与朗读、实时通话分别配置。") }
                    } else item {
                        SettingsNote("本机识别无需账户。使用离线听写前，请下载语音包并启用。")
                        SettingsCard { SettingsRow("保存识别方式", tag = "voice-save", onClick = if (busy) null else ({ save(recognitionConfiguration()) })) }
                    }
                } else if (child == "MIMO") {
                    item { SettingsCard {
                        SettingsRow("MiMo 提供商", summary = mimoConnection()?.model ?: "请选择模型",
                            onClick = if (busy) null else ({ dialog = "MIMO_MODEL" }))
                        SettingsTextField("声音名称", sampleName, { if (!busy) { sampleName = it; selectedSample = null } }, enabled = !busy)
                        SettingsRow(if (selectedSample == null) "选择 MP3 / WAV 录音" else "录音已选择 · 重新选择",
                            tag = "voice-sample-import", onClick = if (busy || pickerPending) null else ({
                                val selected = mimoConnection()
                                if (current() && selected != null && picker.begin(selected, sampleName)) {
                                    pickerPending = true
                                    try { importer.launch("audio/*") }
                                    catch (_: Exception) { picker.deliver(null); pickerPending = picker.pending; resultRevision++; notice = "未能打开录音选择器" }
                                } else notice = "请先配置并保存 MiMo 模型。"
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
                    }; SettingsNote("导入仅保存在本机。试听或朗读时，需另行确认发送参考录音与文字。") }
                    items(voices.size, key = { voices[it].id }) { index ->
                        val voice = voices[index]
                        SettingsCard {
                            SettingsRow(voice.name, summary = "${voice.durationMs / 1000.0} 秒")
                            SettingsRow("用于朗读", onClick = if (busy || mimoConnection()?.referenceOwner() != voice.providerId) null else ({
                                mimoConnection()?.let { save(it.copy(voice = voice.id, model = app.daiyu.android.voice.personal.MimoPersonalVoices.MODEL)) }
                            }))
                            SettingsRow("删除", onClick = if (busy) null else ({ removing = voice.id }))
                        }
                    }
                    item { VoiceSettingsPreview(runtime, profile, !busy && saved == draft && saved?.engine == "MIMO", ::current, installPreviewRevoke, configuration = saved) }
                } else when (section) {
                    "RECOGNITION" -> {
                        item { SettingsCard {
                            SettingsSwitchRow("启用语音输入", inputEnabled, enabled = !busy, tag = "voice-input-enable",
                                summary = "点击聊天输入框的语音按钮，把说话变成文字。") { switchFeature("input", it) { inputEnabled = it } }
                            SettingsRow("离线语音", tag = "voice-offline-settings", onClick = if (busy) null else ({
                                if (current() && !busy && stopAudio()) { invalidatePicker(); onOpenOfflineSpeech() }
                            }))
                        } }
                        item { OfflineSpeechPackCard(context, ::current) }
                        item { SettingsCard { SettingsRow("识别方式", tag = "voice-recognition-method",
                            summary = if (draft.dictation == "DOUBAO") "豆包识别 · 需要联网" else "本机识别 · 无需账户",
                            onClick = if (busy) null else ({ if (current() && stopAudio()) { child = "ASR" } })) } }
                        if (pack.ready && draft.dictation != "DOUBAO") item { SettingsCard { SettingsSwitchRow("离线听写", pack.enabled,
                            tag = "offline-speech-enable") { if (current()) OfflineSpeechPack.setEnabled(context, it) } } }
                    }
                    "READ_ALOUD", "CONVERSATION" -> {
                        if (section == "CONVERSATION") item { SettingsCard {
                            SettingsSwitchRow("启用语音会话", conversationEnabled, enabled = !busy) { switchFeature("conversation", it) { conversationEnabled = it } }
                            SettingsSwitchRow("云端识别", draft.dictation == "DOUBAO", enabled = !busy) {
                                if (current() && stopAudio()) {
                                    if (it) changeDraft(draft.copy(dictation = "DOUBAO")) else save(recognitionConfiguration().copy(dictation = "SYSTEM"))
                                }
                            }
                            if (draft.dictation == "DOUBAO") {
                                SettingsTextField("ASR API Key", draft.asrKey, { if (!busy) changeDraft(draft.copy(asrKey = it)) }, password = true, enabled = !busy)
                                SettingsRow("识别资源", summary = draft.asrResource.ifBlank { "请选择" }, onClick = if (busy) null else ({ dialog = "ASR_RESOURCE" }))
                                SettingsRow("保存识别设置", onClick = if (busy) null else ({ save(recognitionConfiguration()) }))
                            }
                        } }
                        if (section == "CONVERSATION" && draft.dictation != "DOUBAO") item { OfflineSpeechPackCard(context, ::current) }
                        item { SettingsCard {
                            SettingsSwitchRow("云端语音", cloud, enabled = !busy, tag = "voice-cloud-enable", summary = "关闭时使用系统语音。") { enabled ->
                                if (current() && !busy && stopAudio()) {
                                    cloud = enabled
                                    if (!enabled) { preferences.rememberCloud(profile, draft); save(draft.copy(engine = "SYSTEM", voice = "")) }
                                    else {
                                        val engine = preferences.cloudEngine(profile)
                                        val voice = preferences.cloudVoice(profile, draft.referenceOwner(), draft.model)
                                        if (engine != null && voice.isNotBlank()) save(draft.copy(engine = engine, voice = voice))
                                        else dialog = "MODEL"
                                    }
                                }
                            }
                            if (cloud) {
                                SettingsRow("模型", summary = draft.model.ifBlank { "选择模型" }, tag = "voice-model-open", onClick = if (busy) null else ({ dialog = "MODEL" }))
                                SettingsRow("音色", summary = draft.voice.ifBlank { "选择音色" }, tag = "voice-voice-open", onClick = if (busy) null else ({ dialog = "VOICE" }))
                            }
                        } }
                        item { VoiceSettingsPreview(runtime, profile,
                            !busy && ((saved == null && !cloud && draft.engine == "SYSTEM") || (saved == draft && (!cloud || draft.engine != "SYSTEM"))),
                            ::current, installPreviewRevoke, configuration = saved,
                            onInitialize = if (saved == null && !cloud && draft.engine == "SYSTEM") ({ save(draft) }) else null) }
                    }
                    "REALTIME" -> item { SettingsCard {
                        SettingsSwitchRow("启用实时通话", realtimeEnabled, enabled = !busy) { switchFeature("realtime", it) { realtimeEnabled = it } }
                        SettingsRow("服务商", summary = if (draft.duplexKey.isBlank()) "连接豆包账户" else "豆包语音", onClick = if (busy) null else ({ dialog = "DUPLEX_KEY" }))
                        SettingsRow("音色", summary = draft.duplexVoice, onClick = if (busy) null else ({ dialog = "DUPLEX_VOICE" }))
                        SettingsNote("可选择默认音色或输入已有音色 ID。")
                        SettingsTextField("角色说明", draft.instructions, { if (!busy) changeDraft(draft.copy(instructions = it)) }, multiline = true, enabled = !busy)
                        SettingsRow("保存", tag = "voice-save", onClick = if (busy) null else ({ save(realtimeConfiguration()) }))
                    } }
                    "PERSONAL" -> item { SettingsCard {
                        SettingsRow("豆包语音", tag = "voice-account-open", onClick = if (busy) null else ({
                            val selected = saved
                            if (current() && stopAudio()) {
                                invalidatePicker()
                                if (selected != null && selected.engine == "DOUBAO" && runtime.current(selected, profile)) accountPage = selected
                                else dialog = "DOUBAO_MODEL"
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
        "MODEL", "MIMO_MODEL", "DOUBAO_MODEL" -> {
            val fixed = when (dialog) { "MIMO_MODEL" -> "MIMO"; "DOUBAO_MODEL" -> "DOUBAO"; else -> draft.engine }
            VoiceModelDialog((if (dialog == "MIMO_MODEL") mimoConnection() ?: draft else draft).copy(engine = fixed),
                modelSource, ::current, fixedEngine = fixed.takeIf { dialog != "MODEL" }, onSave = {
                if (dialog == "MIMO_MODEL" && it.engine == "MIMO") {
                    confirmedMimo = it; mimoBaseRevision = saved?.revision; dialog = null
                    if (it.voice.isNotBlank()) save(it)
                    else notice = "连接已确认，可选择录音并保存参考声音；用于朗读需另行选择。"
                } else {
                    dialog = null; cloud = true; changeDraft(it)
                    if (it.voice.isBlank()) { notice = "请选择音色后保存。"; dialog = "VOICE" }
                    else save(it)
                }
            }, onDismiss = { dialog = null; if (draft.engine == "SYSTEM") cloud = false })
        }
        "VOICE" -> VoiceToneDialog("音色", draft.voice, buildList {
            if (draft.voice.isNotBlank()) add(draft.voice to draft.voice)
            if (draft.engine == "MIMO") voices.filter { it.providerId == draft.referenceOwner() }.forEach { add(it.id to it.name) }
            if (draft.engine == "DOUBAO") {
                val identity = runCatching { app.daiyu.android.voice.account.VoiceAccountIdentity.from(draft.speech()).fingerprint }.getOrNull()
                accountVoices.filter { it.account == identity && it.tts && it.accepted }.forEach { add(it.id to it.name) }
            }
        }, onSelect = { if (current()) { dialog = null; save(draft.copy(voice = it)) } }, onDismiss = { dialog = null })
        "ASR_RESOURCE" -> VoiceChoiceDialog("识别资源", DoubaoAsrProtocol.resources.map { it to it }, draft.asrResource,
            onSelect = { if (current()) { changeDraft(draft.copy(asrResource = it)); dialog = null } }, onDismiss = { dialog = null })
        "DUPLEX_KEY" -> VoiceTextDialog("实时通话 API Key", draft.duplexKey, password = true,
            onSave = { if (current()) { dialog = null; save(realtimeConfiguration().copy(duplexKey = it)) } }, onDismiss = { dialog = null })
        "DUPLEX_VOICE" -> VoiceToneDialog("实时通话音色", draft.duplexVoice,
            listOf(draft.duplexVoice to draft.duplexVoice, DoubaoDuplexProtocol.DEFAULT_VOICE to "默认音色"),
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
