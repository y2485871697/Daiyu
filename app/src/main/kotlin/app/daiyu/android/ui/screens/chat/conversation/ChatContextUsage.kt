package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.daiyu.android.ChatController
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.ui.model.CloudContextUsageUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No history scan on text deltas: reload only actual receipts, history/model changes or a new owner. */
@Composable
internal fun rememberChatContextUsage(controller: ChatController, conversation: ConversationId?,
    receiptRevision: Long, dataRevision: Long, modelRevision: Long, phase: ComposerPhase): CloudContextUsageUi {
    var usage by remember(controller, conversation, dataRevision, modelRevision) { mutableStateOf(CloudContextUsageUi()) }
    LaunchedEffect(controller, conversation, receiptRevision, dataRevision, modelRevision, phase) {
        usage = withContext(Dispatchers.IO) {
            try { controller.cloudContextUsage(conversation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { CloudContextUsageUi() }
        }
    }
    return usage
}
