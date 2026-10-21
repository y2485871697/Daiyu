package app.daiyu.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.MiuixDialogActions
import app.daiyu.android.ui.screens.settings.*
import app.daiyu.android.voice.providers.SpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.daiyu.android.ui.haptics.miuix.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun VoiceChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String,
    onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            .testTag("voice-choice-dialog")) {
            options.forEach { (id, label) -> SettingsRadioRow(label, selected == id) { onSelect(id) } }
            TextButton(text = "关闭", onClick = onDismiss)
        }
    }
}

/** Protocol and speech URL are explicit: the chat catalog does not declare TTS capabilities. */
@Composable
internal fun VoiceModelDialog(initial: VoiceConfiguration, source: SettingsModelSource,
    isCurrent: () -> Boolean, fixedEngine: String? = null, onSave: (VoiceConfiguration) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    var choosingModel by remember { mutableStateOf(false) }
    var choosingEngine by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(isCurrent)
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { alive.set(false) } }
    if (choosingModel) {
        SettingsModelPicker("选择语音模型", source, null, onSelect = { choice ->
            if (!loading && current()) {
                loading = true
                scope.launch {
                    try {
                        val next = withContext(Dispatchers.IO) { source.withModel(choice) { settings ->
                            require(settings.customHeaders.isEmpty)
                            value.copy(provider = choice.provider.slotId, endpoint = settings.endpoint,
                                model = settings.model, key = settings.apiKey, voice = "")
                        } }
                        if (alive.get() && current()) { value = next; choosingModel = false; error = "" }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "模型连接不可读取，或含语音配置尚不支持的请求头。请重新选择。"; choosingModel = false }
                    finally { loading = false }
                }
            }
        }, onDismiss = { if (!loading) choosingModel = false })
        return
    }
    if (choosingEngine) {
        VoiceChoiceDialog("语音服务", SpeechEngine.entries.map { it.name to it.name }, value.engine,
            onSelect = { value = value.copy(engine = it, voice = ""); choosingEngine = false },
            onDismiss = { choosingEngine = false })
        return
    }
    WindowDialog(show = true, title = "语音模型", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState())
            .testTag("voice-model-dialog")) {
            SettingsRow("模型", summary = value.model.ifBlank { "选择已配置的供应商模型" },
                tag = "voice-catalog-open", onClick = { if (current()) choosingModel = true })
            SettingsRow("语音服务", summary = value.engine.takeUnless { it == "SYSTEM" } ?: "请选择",
                onClick = if (fixedEngine != null) null else ({ if (current()) choosingEngine = true }))
            SettingsTextField("语音接口地址", value.endpoint, { value = value.copy(endpoint = it) },
                hint = "确认所选语音服务使用的 HTTPS 地址")
            SettingsTextField("音色 ID", value.voice, { value = value.copy(voice = it) })
            SettingsNote("请选择语音服务，并确认接口地址与音色。")
            SettingsRow("手动配置", onClick = { manual = !manual })
            if (manual) {
                SettingsTextField("账户标识", value.provider, { value = value.copy(provider = it) })
                SettingsTextField("模型 ID", value.model, { value = value.copy(model = it, voice = "") })
                SettingsTextField("API Key", value.key, { value = value.copy(key = it) }, password = true)
            }
            if (error.isNotBlank()) SettingsNote(error, error = true)
            MiuixDialogActions("确定", onCancel = onDismiss, onConfirm = {
                if (current()) {
                    if (value.engine == "SYSTEM" || value.provider.isBlank() || value.model.isBlank() || value.key.isBlank())
                        error = "请选择模型和语音服务。"
                    else onSave(value)
                }
            }, confirmEnabled = !loading, modifier = Modifier.testTag("voice-model-save"))
        }
    }
}

@Composable
internal fun VoiceTextDialog(title: String, initial: String, password: Boolean = false,
    multiline: Boolean = false, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            SettingsTextField(title, value, { value = it }, password = password, multiline = multiline)
            MiuixDialogActions("保存", onCancel = onDismiss, onConfirm = { onSave(value) })
        }
    }
}

@Composable
internal fun VoiceToneDialog(title: String, initial: String, choices: List<Pair<String, String>>,
    onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(initial) }
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())
            .testTag("voice-tone-dialog")) {
            choices.distinctBy { it.first }.forEach { (id, label) ->
                SettingsRadioRow(label, selected = id == initial) { onSelect(id) }
            }
            SettingsRow("输入音色 ID", onClick = { custom = !custom })
            if (custom) {
                SettingsTextField("音色 ID", value, { value = it })
                TextButton("使用此音色", enabled = value.isNotBlank(), onClick = { onSelect(value) })
            }
            TextButton("关闭", onClick = onDismiss)
        }
    }
}
