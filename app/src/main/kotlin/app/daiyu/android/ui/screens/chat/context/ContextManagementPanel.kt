package app.daiyu.android.ui.screens.chat.context

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.daiyu.android.context.CatalogSummaryBindings
import app.daiyu.android.context.ContextManagementControllerAdapter
import app.daiyu.android.context.ContextManagementSelection
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ProviderCatalogRunTarget
import app.daiyu.android.ui.screens.settings.SettingsModelChoice
import app.daiyu.android.ui.screens.settings.SettingsModelPicker
import app.daiyu.android.ui.screens.settings.SettingsModelSource

/** Conversation compression: choosing a model is local; starting a summary is explicit. */
@Composable
internal fun ContextManagementRoute(
    adapter: ContextManagementControllerAdapter,
    source: SettingsModelSource,
    onClose: () -> Unit,
) {
    val state by adapter.state.collectAsState()
    LaunchedEffect(adapter) { adapter.refresh() }
    LaunchedEffect(state.compressionAccepted) { if (state.compressionAccepted) onClose() }
    DisposableEffect(adapter) { onDispose { adapter.close() } }
    val dismiss = { adapter.close(); onClose() }
    val initial = remember(adapter, state.snapshot?.savedConfiguration) {
        val binding = state.snapshot?.savedConfiguration?.summaryModel
        val protocol = runCatching { binding?.let(CatalogSummaryBindings::decode)?.protocol }.getOrNull()
            ?: source.protocol("compression") ?: ProviderProtocol.CHAT_COMPLETIONS
        CompressionDialogDraft(customModel = binding != null,
            modelLabel = source.label("compression"), modelKey = source.key("compression"),
            protocol = if (protocol == ProviderProtocol.RESPONSES) protocol else ProviderProtocol.CHAT_COMPLETIONS)
    }
    var draft by remember(initial) { mutableStateOf(initial) }
    var picked by remember(initial) { mutableStateOf<SettingsModelChoice?>(null) }
    var choosingModel by remember(adapter) { mutableStateOf(false) }
    var notice by remember(adapter) { mutableStateOf<String?>(null) }
    if (choosingModel) {
        SettingsModelPicker("压缩模型", source, draft.modelKey,
            protocols = setOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES, ProviderProtocol.CHAT_COMPLETIONS),
            onSelect = { choice ->
                adapter.dismissCompression()
                picked = choice
                notice = null
                draft = draft.copy(modelLabel = choice.label, modelKey = choice.key)
                choosingModel = false
            }, onDismiss = { choosingModel = false })
    } else {
        CompressionConversationDialog(draft, onChange = {
                adapter.dismissCompression(); draft = it; notice = null
            }, onChooseModel = { adapter.dismissCompression(); choosingModel = true }, onDismiss = dismiss,
            onConfirm = {
                val choice = picked
                val binding = if (!draft.customModel) null else if (choice == null)
                    state.snapshot?.savedConfiguration?.summaryModel else CatalogSummaryBindings.encode(
                        ProviderCatalogRunTarget(choice.provider, choice.selection.providerIdentity,
                            choice.selection.expectedRevision, choice.selection.record.settings.localIdentity,
                            choice.selection.record.id, choice.protocol))
                when {
                    state.snapshot?.configuration?.contextWindowTokens == null -> notice = "请先在模型目录设置上下文窗口"
                    draft.customModel && binding == null -> notice = "请先选择压缩模型"
                    else -> {
                        val selection = if (draft.customModel && choice == null &&
                            binding?.let(CatalogSummaryBindings::decode) == null) null
                            else ContextManagementSelection(binding, draft.protocol)
                        if (!adapter.submitCompression(selection)) notice = "当前不可准备压缩，请刷新后重试"
                    }
                }
            }, enabled = state.operation == null && !state.confirmingCompression,
            notice = notice ?: state.notice, requiresRefresh = state.requiresRefresh,
            onRefresh = {
                adapter.dismissCompression(); picked = null; draft = initial; notice = null; adapter.refresh()
            },
            operation = state.operation)
    }
}
