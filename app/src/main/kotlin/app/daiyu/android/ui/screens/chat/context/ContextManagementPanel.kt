package app.daiyu.android.ui.screens.chat.context

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.daiyu.android.context.CatalogSummaryBindings
import app.daiyu.android.context.CompressionSettings
import app.daiyu.android.context.ContextManagementControllerAdapter
import app.daiyu.android.context.ContextManagementOperation
import app.daiyu.android.context.SummaryModelBinding
import app.daiyu.android.context.ContextManagementSelection
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ProviderCatalogRunTarget
import app.daiyu.android.ui.screens.settings.SettingsModelPicker
import app.daiyu.android.ui.screens.settings.SettingsModelSource

internal class ContextManagementPresentation {
    var draft by mutableStateOf<CompressionDialogDraft?>(null)
    var binding: SummaryModelBinding? = null
    var choosingModel by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)

    fun retain(draft: CompressionDialogDraft, binding: SummaryModelBinding?) {
        this.binding = binding
        this.draft = draft
    }

    fun reset() {
        draft = null
        binding = null
        choosingModel = false
        notice = null
    }
}

/** Saved values for the local draft; submission still needs a fresh visit snapshot. */
internal data class CompressionDialogSeed(val draft: CompressionDialogDraft, val binding: SummaryModelBinding?) {
    companion object {
        fun from(saved: CompressionSettings, source: SettingsModelSource): CompressionDialogSeed {
            val binding = saved.summaryModel
            val protocol = saved.summaryProtocol
                ?: runCatching { binding?.let(CatalogSummaryBindings::decode)?.protocol }.getOrNull()
                ?: source.protocol("compression") ?: ProviderProtocol.CHAT_COMPLETIONS
            return CompressionDialogSeed(CompressionDialogDraft(customModel = binding != null,
                modelLabel = source.label("compression"), modelKey = source.key("compression"),
                protocol = if (protocol == ProviderProtocol.RESPONSES) protocol else ProviderProtocol.CHAT_COMPLETIONS), binding)
        }
    }
}

/** Conversation compression: choosing a model is local; starting a summary is explicit. */
@Composable
internal fun ContextManagementRoute(
    adapter: ContextManagementControllerAdapter?,
    source: SettingsModelSource,
    onClose: () -> Unit,
    presentation: ContextManagementPresentation = remember { ContextManagementPresentation() },
    isCurrent: () -> Boolean = { true },
    onResume: () -> Unit = {},
    seed: CompressionDialogSeed? = null,
) {
    val state = adapter?.state?.collectAsState()?.value
    LaunchedEffect(adapter) { adapter?.refresh() }
    LaunchedEffect(adapter, state?.compressionAccepted) {
        if (state?.compressionAccepted == true && isCurrent()) onClose()
    }
    DisposableEffect(adapter) { onDispose { adapter?.close() } }
    val dismiss = { if (isCurrent()) { adapter?.close(); onClose() } }
    val saved = state?.snapshot?.savedConfiguration
    val configurationReady = state != null && state.operation == null && !state.requiresRefresh
    val initial = remember(adapter, saved, configurationReady) {
        if (configurationReady && saved != null) CompressionDialogSeed.from(saved, source) else null
    }
    var allowSeed by remember(adapter) { mutableStateOf(true) }
    var earlyConfirmation by remember(adapter) { mutableStateOf(false) }
    val pendingInitialRead = adapter != null && state?.notice == null && state?.requiresRefresh != true &&
        (state?.snapshot == null || state.operation != null)
    val startingDraft = initial ?: seed?.takeIf { allowSeed && pendingInitialRead }
    val draft = presentation.draft ?: startingDraft?.draft
    val binding = if (presentation.draft == null) startingDraft?.binding else presentation.binding
    SideEffect {
        if (presentation.draft == null && initial != null) presentation.retain(initial.draft, initial.binding)
    }
    if (draft == null && pendingInitialRead) {
        BackHandler(onBack = dismiss)
        return
    }
    val operation = state?.operation
    val canEdit = draft != null && adapter != null && state?.confirmingCompression != true &&
        (operation == null || operation == ContextManagementOperation.REFRESH)
    val canSubmit = configurationReady && state?.snapshot != null
    if (presentation.choosingModel && draft != null) {
        SettingsModelPicker("压缩模型", source, draft.modelKey,
            protocols = setOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES, ProviderProtocol.CHAT_COMPLETIONS),
            onSelect = select@ { choice ->
                if (!isCurrent() || adapter == null) return@select
                adapter.dismissCompression()
                presentation.retain(draft.copy(modelLabel = choice.label, modelKey = choice.key),
                    CatalogSummaryBindings.encode(ProviderCatalogRunTarget(choice.provider,
                        choice.selection.providerIdentity, choice.selection.expectedRevision,
                        choice.selection.record.settings.localIdentity, choice.selection.record.id, choice.protocol)))
                presentation.notice = null
            }, onDismiss = { if (isCurrent()) presentation.choosingModel = false })
    } else {
        CompressionConversationDialog(draft, onChange = change@ {
                if (!isCurrent() || !canEdit || adapter == null || draft == null) return@change
                adapter.dismissCompression()
                presentation.retain(it, binding)
                presentation.notice = null
            }, onChooseModel = {
                if (isCurrent() && canEdit && adapter != null && draft != null) {
                    adapter.dismissCompression()
                    presentation.retain(draft, binding)
                    presentation.choosingModel = true
                }
            }, onDismiss = dismiss, onConfirm = confirm@ {
                if (!isCurrent() || !canEdit || adapter == null || draft == null) return@confirm
                if (!canSubmit) {
                    earlyConfirmation = true
                    return@confirm
                }
                val configuration = state?.snapshot?.configuration
                when {
                    configuration != null && configuration.contextWindowTokens == null ->
                        presentation.notice = "请先在模型目录设置上下文窗口"
                    configuration != null && draft.customModel && binding == null ->
                        presentation.notice = "请先选择压缩模型"
                    else -> {
                        val selection = if (draft.customModel && binding?.let(CatalogSummaryBindings::decode) == null) null
                            else ContextManagementSelection(if (draft.customModel) binding else null, draft.protocol)
                        if (!adapter.submitCompression(selection))
                            presentation.notice = adapter.state.value.notice ?: "当前不可准备压缩，请刷新后重试"
                    }
                }
            }, enabled = canEdit,
            notice = presentation.notice ?: state?.notice ?: when {
                adapter == null -> "会话已暂停，请重新读取配置"
                draft == null -> "压缩配置不可用，请重新读取"
                earlyConfirmation && !canSubmit -> "配置尚未就绪，请稍后再点确定"
                else -> null
            }, requiresRefresh = adapter == null || state?.requiresRefresh == true ||
                (draft == null && (state?.notice != null || state?.snapshot != null)),
            onRefresh = {
                if (isCurrent()) {
                    allowSeed = false
                    earlyConfirmation = false
                    presentation.reset()
                    if (adapter == null) onResume() else adapter.refresh()
                }
            }, operation = state?.operation)
    }
}
