package app.daiyu.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.ui.screens.settings.SettingsCard
import app.daiyu.android.ui.screens.settings.SettingsRow
import app.daiyu.android.ui.screens.settings.SettingsRadioRow
import app.daiyu.android.ui.screens.settings.SettingsTextField
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.voice.providers.DoubaoAsrProtocol
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.duplex.DoubaoDuplexProtocol
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Draft is memory-only. Render/load/import/select NEVER initiates any billable voice request. */
@Composable
internal fun VoiceSettingsPanel(
    runtime: ControllerVoiceRuntime,
    remote: ActivityRemoteVoiceHost,
    accounts: app.daiyu.android.voice.account.VoiceAccountResources,
    installPreviewRevoke: (() -> Unit) -> (() -> Unit),
    isCurrent: () -> Boolean,
    invalidate: () -> Unit,
    onBack: () -> Unit,
    onOpenOfflineSpeech: () -> Unit,
) {
    var profile by remember { mutableStateOf(VoiceProfile.READ_ALOUD) }
    var section by remember { mutableStateOf("RECOGNITION") }
    var choosingSection by remember { mutableStateOf(false) }
    val sections = listOf("RECOGNITION" to "语音识别", "READ_ALOUD" to "朗读",
        "CONVERSATION" to "语音会话", "REALTIME" to "实时通话", "PERSONAL" to "个人声音")
    var accountPage by remember { mutableStateOf<VoiceConfiguration?>(null) }
    val saved = key(profile) { runtime.profileConfiguration(profile).collectAsState().value }
    val voices by runtime.personal.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val drafts = remember { mutableMapOf<VoiceProfile, VoiceConfiguration>() }
    var draft by remember { mutableStateOf(saved ?: VoiceConfiguration()) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sampleName by remember { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val pageCurrent by rememberUpdatedState(isCurrent)
    // This visit owns its picker independently of the foreground-only microphone/network gate.
    val picker = remember(runtime) { VoiceSamplePickerRequest<android.net.Uri>() }
    var pickerPending by remember { mutableStateOf(false) }
    var resultRevision by remember { mutableIntStateOf(0) }
    fun invalidatePicker() { picker.invalidate(); pickerPending = picker.pending }
    fun changeDraft(value: VoiceConfiguration) { invalidatePicker(); draft = value }
    DisposableEffect(picker) { onDispose { picker.close() } }
    LaunchedEffect(profile, saved?.revision) {
        invalidatePicker()
        draft = drafts.remove(profile) ?: saved ?: VoiceConfiguration()
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        picker.deliver(uri)
        pickerPending = picker.pending
        resultRevision++
    }
    LaunchedEffect(resultRevision, lifecycleState, saved?.revision) {
        val selection = picker.take(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            pageCurrent(), runtime.profileConfiguration(profile).value)
        pickerPending = picker.pending
        if (selection != null) {
            busy = true
            try {
                val selected = selection.configuration
                check(pageCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && runtime.current(selected, profile))
                runtime.personal.import(context, selection.value, selection.name, selected.referenceOwner())
                notice = "已导入本机；选择不会上传。每次实际发送参考录音前仍需确认。"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "录音未导入：需已保存的 MiMo 配置、名称及有效 MP3/WAV" }
            finally { busy = false }
        }
    }
    val accountConfiguration = accountPage
    if (accountConfiguration != null) {
        key(profile, accountConfiguration.revision) {
            ProductionVoiceAccountPage(runtime, accounts, profile, accountConfiguration,
                isCurrent = isCurrent, installPreviewRevoke = installPreviewRevoke, onBack = { accountPage = null })
        }
        return
    }
    app.daiyu.android.ui.components.MiuixScaffoldPage(title = "语音设置", onBack = {
        if (isCurrent()) { picker.close(); invalidate(); onBack() }
    }, modifier = Modifier.testTag("voice-settings")) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsCard {
                    SettingsRow(sections.first { it.first == section }.second,
                        onClick = if (busy) null else ({ choosingSection = !choosingSection }),
                        tag = "voice-section-select", trailing = {
                            DaiyuIcon(if (choosingSection) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                                contentDescription = if (choosingSection) "收起分组" else "展开分组")
                        })
                    if (choosingSection) Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        sections.forEachIndexed { index, entry ->
                            SettingsRadioRow(entry.second, selected = section == entry.first, enabled = !busy) {
                                if (pageCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !busy) {
                                    val target = if (sections[index].first == "CONVERSATION") VoiceProfile.CONVERSATION else VoiceProfile.READ_ALOUD
                                    invalidatePicker(); runtime.invalidateSpeech(); invalidate()
                                    if (target != profile) {
                                        drafts[profile] = draft
                                        profile = target
                                    }
                                    section = sections[index].first
                                }
                                choosingSection = false
                            }
                        }
                    }
                }
                SettingsCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (section == "READ_ALOUD" || section == "CONVERSATION") {
                            Text("TTS 引擎：${draft.engine}")
                            TextButton(text = "切换 TTS 引擎", enabled = !busy, onClick = {
                                val engines = listOf("SYSTEM") + SpeechEngine.entries.map { it.name }
                                val next = engines[(engines.indexOf(draft.engine) + 1).mod(engines.size)]
                                changeDraft(draft.copy(engine = next, key = "", voice = ""))
                            })
                            VoiceField("TTS provider / 账户标识", draft.provider) { changeDraft(draft.copy(provider = it)) }
                            VoiceField("TTS HTTPS endpoint（按所选协议填写）", draft.endpoint) { changeDraft(draft.copy(endpoint = it)) }
                            VoiceField("TTS 精确 model", draft.model) { changeDraft(draft.copy(model = it)) }
                            VoiceField("TTS API key", draft.key, secret = true) { changeDraft(draft.copy(key = it)) }
                            VoiceField("TTS voice / 个人声音 ID", draft.voice) { changeDraft(draft.copy(voice = it)) }
                        }
                        if (section == "RECOGNITION") {
                            TextButton(text = "离线语音模型与听写", enabled = !busy,
                            modifier = Modifier.testTag("voice-offline-settings"), onClick = {
                                if (pageCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !busy) {
                                    picker.close(); invalidate(); onOpenOfflineSpeech()
                                }
                            })
                            Text("麦克风按钮听写：${draft.dictation}")
                            TextButton(text = "切换系统 / 豆包听写", enabled = !busy, onClick = {
                                changeDraft(draft.copy(dictation = if (draft.dictation == "SYSTEM") "DOUBAO" else "SYSTEM"))
                            })
                            if (draft.dictation == "DOUBAO") {
                                Text("豆包 ASR 固定 endpoint：${DoubaoAsrProtocol.URL}；model_name=bigmodel；PCM16/16k/mono。")
                                VoiceField("豆包 ASR 独立 key", draft.asrKey, secret = true) { changeDraft(draft.copy(asrKey = it)) }
                                VoiceField("ASR resource（${DoubaoAsrProtocol.resources.joinToString()}）", draft.asrResource) { changeDraft(draft.copy(asrResource = it)) }
                            }
                        }
                        if (section == "REALTIME") {
                            Text("豆包双工固定 endpoint：${DoubaoDuplexProtocol.ENDPOINT}；model=1.2.6.1。不使用 TTS / 聊天 / ASR 密钥。")
                            VoiceField("双工独立 key", draft.duplexKey, secret = true) { changeDraft(draft.copy(duplexKey = it)) }
                            VoiceField("双工 voice", draft.duplexVoice) { changeDraft(draft.copy(duplexVoice = it)) }
                            VoiceField("双工 instructions", draft.instructions) { changeDraft(draft.copy(instructions = it)) }
                        }
                        if (section == "PERSONAL") {
                            TextButton(text = "豆包语音",
                            enabled = !busy && saved?.engine == "DOUBAO", modifier = Modifier.testTag("voice-account-open"), onClick = {
                                val selected = saved
                                if (isCurrent() && selected != null && runtime.current(selected, profile)) {
                                    invalidatePicker(); runtime.invalidateSpeech(); invalidate()
                                    accountPage = selected
                                }
                            })
                            Text("个人参考声音（MiMo）：仅导入已拥有授权的录音，导入和选择只操作本机。账户 / endpoint / key 改变后旧引用不可用。")
                            VoiceField("参考录音名称", sampleName) { sampleName = it }
                            TextButton(text = "导入参考录音（本机 MP3 / WAV）", enabled = !busy && !pickerPending && saved?.engine == "MIMO",
                            modifier = Modifier.testTag("voice-sample-import"), onClick = {
                                val selected = saved
                                if (isCurrent() && selected != null && runtime.current(selected, profile) && picker.begin(selected, sampleName)) {
                                    pickerPending = true
                                    try { importer.launch("audio/*") }
                                    catch (_: Exception) {
                                        picker.deliver(null); pickerPending = picker.pending
                                        notice = "未能打开录音选择器"
                                    }
                                }
                            })
                            voices.forEach { voice ->
                                Text("${voice.name} · ${voice.durationMs} ms")
                                TextButton(text = "选择此参考录音（不授权上传）", enabled = !busy && saved?.referenceOwner() == voice.providerId,
                                onClick = { if (isCurrent()) changeDraft(draft.copy(voice = voice.id,
                                    model = app.daiyu.android.voice.personal.MimoPersonalVoices.MODEL)) })
                                TextButton(text = "删除此参考录音", enabled = !busy, onClick = {
                                    if (isCurrent()) {
                                        invalidate()
                                        scope.launch {
                                            busy = true
                                            try { runtime.removePersonal(voice.id); notice = "参考录音已删除，旧授权已撤销" }
                                            catch (_: Exception) { notice = "删除未确认" }
                                            finally { busy = false }
                                        }
                                    }
                                })
                            }
                        }
                    }
                }
                TextButton(text = "保存独立语音配置", enabled = !busy, modifier = Modifier.testTag("voice-save"), onClick = {
                    if (isCurrent()) {
                        val value = draft
                        val target = profile
                        invalidatePicker()
                        invalidate()
                        scope.launch {
                            busy = true
                            try { runtime.save(value, target); notice = "${target.name} 已独立加密保存；未发起任何请求" }
                            catch (_: Exception) { notice = "未保存：检查引擎、HTTPS 地址、精确模型、音色与独立密钥" }
                            finally { busy = false }
                        }
                    }
                })
                if (notice.isNotBlank()) Text(notice)
            }
        }
    }
}

@Composable
private fun VoiceField(label: String, value: String, secret: Boolean = false, change: (String) -> Unit) {
    SettingsTextField(label = label, value = value, onValueChange = change, password = secret)
}
