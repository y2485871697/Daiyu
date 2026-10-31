package app.daiyu.android.ui.screens.chat.context

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
import app.daiyu.android.context.ContextManagementControllerAdapter
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

/** Conversation compression: choosing a model is local; starting a summary is explicit. */
@Composable
internal fun ContextManagementRoute(
    adapter: ContextManagementControllerAdapter?,
    source: SettingsModelSource,
    onClose: () -> Unit,
    presentation: ContextManagementPresentation = remember { ContextManagementPresentation() },
    isCurrent: () -> Boolean = { true },
    onResume: () -> Unit = {},
) {
    val state = adapter?.state?.collectAsState()?.value
    LaunchedEffect(adapter) { adapter?.refresh() }
    LaunchedEffect(adapter, state?.compressionAccepted) {
        if (state?.compressionAccepted == true && isCurrent()) onClose()
    }
    DisposableEffect(adapter) { onDispose { adapter?.close() } }
    val dismiss = { if (isCurrent()) { adapter?.close(); onClose() } }
    val initial = remember(adapter, state?.snapshot?.savedConfiguration) {
        val binding = state?.snapshot?.savedConfiguration?.summaryModel
        val protocol = state?.snapshot?.savedConfiguration?.summaryProtocol
            ?: runCatching { binding?.let(CatalogSummaryBindings::decode)?.protocol }.getOrNull()
            ?: source.protocol("compression") ?: ProviderProtocol.CHAT_COMPLETIONS
        CompressionDialogDraft(customModel = binding != null,
            modelLabel = source.label("compression"), modelKey = source.key("compression"),
            protocol = if (protocol == ProviderProtocol.RESPONSES) protocol else ProviderProtocol.CHAT_COMPLETIONS)
    }
    val draft = presentation.draft ?: initial
    val binding = if (presentation.draft == null) state?.snapshot?.savedConfiguration?.summaryModel else presentation.binding
    SideEffect {
        if (presentation.draft == null && state?.snapshot != null && state.operation == null && !state.requiresRefresh)
            presentation.retain(initial, state.snapshot.savedConfiguration?.summaryModel)
    }
    if (presentation.choosingModel) {
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
                if (!isCurrent() || adapter == null) return@change
                adapter.dismissCompression()
                presentation.retain(it, binding)
                presentation.notice = null
            }, onChooseModel = {
                if (isCurrent() && adapter != null) {
                    adapter.dismissCompression()
                    presentation.retain(draft, binding)
                    presentation.choosingModel = true
                }
            }, onDismiss = dismiss, onConfirm = confirm@ {
                if (!isCurrent() || adapter == null) return@confirm
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
            }, enabled = adapter != null && state?.snapshot != null && state.operation == null && !state.confirmingCompression,
            notice = presentation.notice ?: state?.notice, requiresRefresh = state?.requiresRefresh == true,
            onRefresh = {
                if (isCurrent()) {
                    presentation.reset()
                    if (adapter == null) onResume() else { adapter.dismissCompression(); adapter.refresh() }
                }
            }, operation = state?.operation)
    }
}
