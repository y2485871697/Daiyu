package app.daiyu.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    isCurrent: () -> Boolean,
    invalidate: () -> Unit,
    onBack: () -> Unit,
) {
    val saved by runtime.configuration.collectAsState()
    val voices by runtime.personal.state.collectAsState()
    val status by remote.status.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
    LaunchedEffect(saved?.revision) {
        invalidatePicker()
        if (saved != null) draft = saved!!
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        picker.deliver(uri)
        pickerPending = picker.pending
        resultRevision++
    }
    LaunchedEffect(resultRevision, lifecycleState, saved?.revision) {
        val selection = picker.take(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            pageCurrent(), runtime.configuration.value)
        pickerPending = picker.pending
        if (selection != null) {
            busy = true
            try {
                val selected = selection.configuration
                check(pageCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && runtime.current(selected))
                runtime.personal.import(context, selection.value, selection.name, selected.referenceOwner())
                notice = "已导入本机；选择不会上传。每次实际发送参考录音前仍需确认。"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "录音未导入：需已保存的 MiMo 配置、名称及有效 MP3/WAV" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("voice-settings"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(text = "返回", onClick = { if (isCurrent()) { picker.close(); invalidate(); onBack() } })
        Text("语音设置与实时通话")
        Text("独立于聊天模型和密钥。保存不会启动麦克风、联网或收费；未配置的功能不会回退。系统语音由设备系统提供，不保证离线。")
        if (saved == null) Text("语音配置尚未加载或凭据不可用；可明确重新保存修复。")
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
        Text("麦克风按钮听写：${draft.dictation}")
        TextButton(text = "切换系统 / 豆包听写", enabled = !busy, onClick = {
            changeDraft(draft.copy(dictation = if (draft.dictation == "SYSTEM") "DOUBAO" else "SYSTEM"))
        })
        Text("豆包 ASR 固定 endpoint：${DoubaoAsrProtocol.URL}；model_name=bigmodel；PCM16/16k/mono。")
        VoiceField("豆包 ASR 独立 key", draft.asrKey, secret = true) { changeDraft(draft.copy(asrKey = it)) }
        VoiceField("ASR resource（${DoubaoAsrProtocol.resources.joinToString()}）", draft.asrResource) { changeDraft(draft.copy(asrResource = it)) }
        Text("豆包双工固定 endpoint：${DoubaoDuplexProtocol.ENDPOINT}；model=1.2.6.1。不使用 TTS / 聊天 / ASR 密钥。")
        VoiceField("双工独立 key", draft.duplexKey, secret = true) { changeDraft(draft.copy(duplexKey = it)) }
        VoiceField("双工 voice", draft.duplexVoice) { changeDraft(draft.copy(duplexVoice = it)) }
        VoiceField("双工 instructions", draft.instructions) { changeDraft(draft.copy(instructions = it)) }
        TextButton(text = "保存独立语音配置", enabled = !busy, modifier = Modifier.testTag("voice-save"), onClick = {
            if (isCurrent()) {
                val value = draft
                invalidatePicker()
                invalidate()
                scope.launch {
                    busy = true
                    try { runtime.save(value); notice = "语音配置已加密保存；未发起任何请求" }
                    catch (_: Exception) { notice = "未保存：检查引擎、HTTPS 地址、精确模型、音色与独立密钥" }
                    finally { busy = false }
                }
            }
        })
        Text("个人参考声音（MiMo）：仅导入已拥有授权的录音，导入和选择只操作本机。账户 / endpoint / key 改变后旧引用不可用。")
        VoiceField("参考录音名称", sampleName) { sampleName = it }
        TextButton(text = "导入参考录音（本机 MP3 / WAV）", enabled = !busy && !pickerPending && saved?.engine == "MIMO",
            modifier = Modifier.testTag("voice-sample-import"), onClick = {
                val selected = saved
                if (isCurrent() && selected != null && runtime.current(selected) && picker.begin(selected, sampleName)) {
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
                        try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runtime.personal.remove(voice.id) }; notice = "参考录音已删除" }
                        catch (_: Exception) { notice = "删除未确认" }
                        finally { busy = false }
                    }
                }
            })
        }
        Text("双工通话会向豆包发送实时麦克风音频并可能收费；这是独立通话，不自动写入或发送聊天消息。请先保存配置。")
        TextButton(text = "明确开始豆包双工通话", enabled = !busy && saved?.duplexKey?.isNotBlank() == true,
            modifier = Modifier.testTag("voice-duplex-start"), onClick = {
                if (isCurrent()) remote.startDuplex(isCurrent)
            })
        TextButton(text = "停止语音 / 通话", modifier = Modifier.testTag("voice-stop"), onClick = { invalidate() })
        Text(status)
        Text(notice)
    }
}

@Composable
private fun VoiceField(label: String, value: String, secret: Boolean = false, change: (String) -> Unit) {
    Column {
        Text(label)
        BasicTextField(value = value, onValueChange = change, modifier = Modifier.fillMaxWidth().padding(8.dp),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
    }
}
