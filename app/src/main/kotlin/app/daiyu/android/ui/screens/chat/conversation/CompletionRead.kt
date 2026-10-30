package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.storage.ConversationCompletionMarkers.Receipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CompletionReadOwner(
    val conversationId: ConversationId,
    val generation: Long,
    val revision: Long,
    val completed: Set<Pair<String, String>>,
)

/** One receipt for the current terminal snapshot; text deltas do not re-enter SQLite. */
@Composable
internal fun rememberCompletionReceipt(owner: CompletionReadOwner?, read: suspend (ConversationId) -> Receipt?): MutableState<Receipt?> {
    val receipt = remember(owner) { mutableStateOf<Receipt?>(null) }
    val currentRead by rememberUpdatedState(read)
    LaunchedEffect(owner) {
        if (owner == null) return@LaunchedEffect
        val reader = currentRead
        receipt.value = try {
            withContext(Dispatchers.IO) { reader(owner.conversationId) }
                ?.takeIf { it.conversationId == owner.conversationId.value && (it.messageId to it.runId) in owner.completed }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    return receipt
}
