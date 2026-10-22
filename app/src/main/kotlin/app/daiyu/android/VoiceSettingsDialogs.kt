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

@Composable
internal fun VoiceModelDialog(initial: VoiceConfiguration, source: SettingsModelSource,
    isCurrent: () -> Boolean, fixedEngine: String? = null, onSave: (VoiceConfiguration) -> Unit, onDismiss: () -> Unit) {
    var choices by remember { mutableStateOf<List<SettingsSpeechModelChoice>?>(null) }
    var expanded by remember { mutableStateOf(initial.provider) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(isCurrent)
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { alive.set(false) } }
    LaunchedEffect(source, fixedEngine) {
        try {
            val values = withContext(Dispatchers.IO) { source.speechChoices().filter { choice ->
                fixedEngine == null || app.daiyu.android.voice.providers.SpeechModelCapabilities
                    .engine(choice.connection.endpoint, choice.model).name == fixedEngine
            } }
            choices = values
            if (expanded.isBlank() && values.map { it.connection.provider.slotId }.distinct().size == 1) expanded = values.first().connection.provider.slotId
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { choices = emptyList(); error = "模型列表读取失败，请重新打开。" }
    }
    WindowDialog(show = true, title = "朗读模型", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            .testTag("voice-model-dialog")) {
            if (choices == null) SettingsNote("正在读取…")
            else if (choices!!.isEmpty() && error.isBlank()) SettingsNote("暂无可选朗读模型，请先在供应商中添加语音模型。")
            choices.orEmpty().groupBy { it.connection.provider.slotId }.forEach { (provider, models) ->
                SettingsRow(models.first().connection.name, tag = "voice-catalog-open:$provider",
                    onClick = { expanded = if (expanded == provider) "" else provider })
                if (expanded == provider) models.forEach { choice ->
                    val selected = initial.provider == provider && if (initial.modelIdentity.isBlank() || choice.identity.isBlank()) initial.model == choice.model
                        else initial.modelIdentity == choice.identity
                    SettingsRadioRow(choice.name, selected, enabled = !loading) {
                        if (!loading && current()) {
                            loading = true
                            scope.launch {
                                try {
                                    val next = withContext(Dispatchers.IO) { source.withSpeechModel(choice) { settings ->
                                        val engine = app.daiyu.android.voice.providers.SpeechModelCapabilities.engine(settings.endpoint, settings.model)
                                        require(fixedEngine == null || engine.name == fixedEngine)
                                        initial.copy(engine = engine.name, provider = choice.connection.provider.slotId,
                                            endpoint = settings.endpoint, model = settings.model, key = settings.apiKey,
                                            customHeaders = settings.customHeaders, voice = initial.voice.takeIf {
                                                selected && initial.engine == engine.name && initial.endpoint == settings.endpoint &&
                                                    initial.key == settings.apiKey && initial.customHeaders == settings.customHeaders
                                            }.orEmpty(),
                                            modelIdentity = choice.identity, modelLabel = "${choice.connection.name} · ${choice.name}")
                                    } }
                                    if (alive.get() && current()) onSave(next)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { if (alive.get()) error = "模型连接已变化或不可读取，请重新选择。" }
                                finally { loading = false }
                            }
                        }
                    }
                }
            }
            if (error.isNotBlank()) SettingsNote(error, error = true)
            TextButton("关闭", onClick = onDismiss)
        }
    }
}

@Composable
internal fun VoiceProviderDialog(title: String, engine: String, source: SettingsModelSource,
    selected: String, isCurrent: () -> Boolean, onSelect: (SettingsVoiceProviderChoice, app.daiyu.android.storage.ModelSettings) -> Unit,
    onDismiss: () -> Unit) {
    var choices by remember { mutableStateOf<List<SettingsVoiceProviderChoice>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(isCurrent)
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { alive.set(false) } }
    LaunchedEffect(source, engine) {
        try { choices = withContext(Dispatchers.IO) { source.voiceProviders(engine) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "提供商列表读取失败，请重新打开。"; choices = emptyList() }
    }
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            .testTag("voice-provider-dialog")) {
            if (choices == null) SettingsNote("正在读取…")
            else if (choices!!.isEmpty() && error.isBlank()) SettingsNote("请先在供应商中配置${if (engine == "MIMO") "MiMo" else "豆包语音"}账户。")
            choices.orEmpty().forEach { choice ->
                SettingsRadioRow(choice.name, choice.provider.slotId == selected, enabled = !loading) {
                    if (current() && !loading) {
                        loading = true
                        scope.launch {
                            try {
                                val value = withContext(Dispatchers.IO) { source.withVoiceProvider(choice) { it } }
                                if (alive.get() && current()) onSelect(choice, value)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { if (alive.get()) error = "账户连接已变化或不可读取，请重新选择。" }
                            finally { loading = false }
                        }
                    }
                }
            }
            if (error.isNotBlank()) SettingsNote(error, error = true)
            TextButton("关闭", onClick = onDismiss)
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
    val catalog = choices.distinctBy { it.first }
    fun group(id: String): String = when {
        id.startsWith("mimo-local-") || id.startsWith("S_") || id.startsWith("etaClone") -> "个人声音"
        "female" in id -> "女声"
        "male" in id -> "男声"
        else -> ""
    }
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())
            .testTag("voice-tone-dialog")) {
            listOf("女声", "男声", "", "个人声音").forEach { section ->
                val rows = catalog.filter { group(it.first) == section }
                if (section.isNotEmpty() && rows.isNotEmpty()) SettingsSectionTitle(section)
                rows.forEach { (id, label) -> SettingsRadioRow(label, selected = id == initial) { onSelect(id) } }
            }
            TextButton("关闭", onClick = onDismiss)
        }
    }
}
