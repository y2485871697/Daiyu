package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPicker
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPickerState
import app.daiyu.android.ui.screens.chat.conversation.ChatModelProviderUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val SettingsModelProtocols = setOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES,
    ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)

@Composable
internal fun SettingsModelPicker(title: String, source: SettingsModelSource, selectedKey: String?,
    onSelect: (SettingsModelChoice) -> Unit, onDismiss: () -> Unit,
    protocols: Set<ProviderProtocol> = SettingsModelProtocols,
    showStatusMessages: Boolean = true, onLoadFailure: () -> Unit = {}) {
    var choices by remember(source, protocols) { mutableStateOf<List<SettingsModelChoice>?>(null) }
    var failed by remember(source, protocols) { mutableStateOf(false) }
    LaunchedEffect(source, protocols) {
        try { choices = withContext(Dispatchers.IO) { source.choices(protocols) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true; onLoadFailure() }
    }
    val loading = choices == null && !failed
    val displayed = if (failed) emptyList() else choices.orEmpty()
    val selected = displayed.singleOrNull { it.key == selectedKey }
    val providerId = selected?.provider?.slotId.orEmpty()
    val providers = remember(displayed) {
        displayed.groupBy { it.provider.slotId }.map { (id, models) ->
            ChatModelProviderUi(id, models.first().providerName,
                models.map { ModelCatalogEntryUi(it.selection.record.id, it.selection.record.displayName) })
        }
    }
    val state = ChatModelPickerState(
        current = selected?.selection?.record?.id.orEmpty(), providerId = providerId,
        models = providers.singleOrNull { it.id == providerId }?.models.orEmpty(), providers = providers,
        loading = loading, enabled = !loading && !failed && displayed.isNotEmpty(),
        message = when {
            failed -> "模型列表读取失败，请重新打开。"
            loading -> null
            displayed.isEmpty() -> "暂无可选模型，请先在供应商中添加模型。"
            else -> null
        },
    )
    // Return this visit's original ticket; identical model IDs in different slots never cross.
    fun select(slot: String, id: String) {
        if (!loading && !failed) displayed.singleOrNull {
            it.provider.slotId == slot && it.selection.record.id == id
        }?.let(onSelect)
    }
    ChatModelPicker(state, { select(providerId, it) }, onDismiss,
        onSelectOtherProvider = { slot, id -> select(slot, id) }, title = title,
        modifier = Modifier.testTag("settings-model-picker"), showStatusMessages = showStatusMessages, closeOnSelect = true)
}
