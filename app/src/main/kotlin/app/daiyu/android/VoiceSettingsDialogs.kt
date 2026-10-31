package app.daiyu.android

import app.daiyu.android.voice.providers.DoubaoVoiceIdentity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.haptics.selectable
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.components.MiuixDialogActions
import app.daiyu.android.ui.screens.settings.*
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPicker
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPickerState
import app.daiyu.android.ui.screens.chat.conversation.ChatModelProviderUi
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun VoiceChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String,
    onSelect: (String) -> Unit, onDismiss: () -> Unit, isEnabled: (String) -> Boolean = { true },
    retainOnSelect: Boolean = false) {
    ChatSelectionSheet(title, onDismiss = onDismiss, showCloseButton = false, titleFontSize = 20.sp) {
        val finishSelection = app.daiyu.android.ui.components.rememberBottomSheetAction()
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            .testTag("voice-choice-dialog")) {
            options.forEach { (id, label) -> SettingsRadioRow(label, selected == id, enabled = isEnabled(id)) {
                if (retainOnSelect) { if (isEnabled(id)) onSelect(id) }
                else finishSelection { if (isEnabled(id)) onSelect(id) }
            } }
        }
    }
}

@Composable
internal fun VoiceModelDialog(initial: VoiceConfiguration, source: SettingsModelSource,
    isCurrent: () -> Boolean, fixedEngine: String? = null, onSave: (VoiceConfiguration) -> Unit, onDismiss: () -> Unit) {
    var choices by remember { mutableStateOf<List<SettingsSpeechModelChoice>?>(null) }
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
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { choices = emptyList(); error = "模型列表读取失败，请重新打开。" }
    }
    val displayed = choices.orEmpty()
    fun isSelected(choice: SettingsSpeechModelChoice): Boolean = initial.provider == choice.connection.provider.slotId &&
        if (initial.modelIdentity.isBlank() || choice.identity.isBlank()) initial.model == choice.model
        else initial.modelIdentity == choice.identity
    val selected = displayed.singleOrNull(::isSelected)
    val providers = remember(displayed) {
        displayed.groupBy { it.connection.provider.slotId }.map { (id, models) ->
            ChatModelProviderUi(id, models.first().connection.name,
                models.map { ModelCatalogEntryUi(it.speechPickerId, it.name, apiModelId = it.model) })
        }
    }
    val providerId = selected?.connection?.provider?.slotId ?: providers.singleOrNull()?.id.orEmpty()
    val state = ChatModelPickerState(
        current = selected?.speechPickerId.orEmpty(), providerId = providerId, providers = providers,
        models = providers.singleOrNull { it.id == providerId }?.models.orEmpty(),
        loading = choices == null || loading, enabled = choices != null && !loading && displayed.isNotEmpty(),
        message = error.takeIf { it.isNotBlank() }
            ?: if (choices != null && displayed.isEmpty()) "暂无可选朗读模型，请先在供应商中添加语音模型。" else null,
    )
    fun select(provider: String, model: String) {
        val choice = displayed.singleOrNull { it.connection.provider.slotId == provider && it.speechPickerId == model } ?: return
        if (alive.get() && !loading && current()) {
            loading = true
            scope.launch {
                try {
                    val next = withContext(Dispatchers.IO) { source.withSpeechModel(choice) { settings ->
                        val engine = app.daiyu.android.voice.providers.SpeechModelCapabilities.engine(settings.endpoint, settings.model)
                        require(fixedEngine == null || engine.name == fixedEngine)
                        initial.copy(engine = engine.name, provider = choice.connection.provider.slotId,
                            endpoint = settings.endpoint, model = settings.model, key = settings.apiKey,
                            customHeaders = settings.customHeaders, voice = initial.voice.takeIf {
                                isSelected(choice) && initial.engine == engine.name && initial.endpoint == settings.endpoint &&
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
    ChatModelPicker(state, { select(providerId, it) }, onDismiss,
        onSelectOtherProvider = { provider, model -> select(provider, model) }, title = "朗读模型",
        modifier = Modifier.testTag("voice-model-dialog"))
}

internal val SettingsSpeechModelChoice.speechPickerId: String
    get() = if (identity.isBlank()) "model:$model" else "local:$identity"

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
    ChatSelectionSheet(title, onDismiss = onDismiss, showCloseButton = false, titleFontSize = 20.sp) {
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
        }
    }
}

@Composable
internal fun VoiceTextDialog(title: String, initial: String, password: Boolean = false,
    multiline: Boolean = false, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        app.daiyu.android.diagnostics.ObserveDialogFrames("dialog")
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
        id.startsWith("mimo-local-") || DoubaoVoiceIdentity.isPersonal(id) -> "个人声音"
        "female" in id -> "女声"
        "male" in id -> "男声"
        else -> ""
    }
    ChatSelectionSheet(title, onDismiss = onDismiss, showCloseButton = false,
        titleFontSize = 20.sp, titleTextAlign = TextAlign.Start) {
        val sheetColors = usagePalette()
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())
            .selectableGroup().testTag("voice-tone-dialog")) {
            listOf("女声", "男声", "", "个人声音").forEach { section ->
                val rows = catalog.filter { group(it.first) == section }
                if (section.isNotEmpty() && rows.isNotEmpty()) SettingsSectionTitle(section)
                rows.forEach { (id, label) ->
                    val selected = id == initial
                    Row(Modifier.fillMaxWidth().testTag("settings-radio:$label")
                        .heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) sheetColors.selectionContainer else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(id) })
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 24.sp,
                            textAlign = TextAlign.Start,
                            color = if (selected) sheetColors.selectionContent else sheetColors.text)
                        Box(Modifier.size(22.dp).testTag("voice-tone-indicator:$id")
                            .background(if (selected) sheetColors.accent else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center) {
                            if (selected) DaiyuIcon(DaiyuIcons.Check, "已选择", tint = sheetColors.onPrimary, size = 14.dp)
                        }
                    }
                }
            }
        }
    }
}
