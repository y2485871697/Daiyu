package app.daiyu.android.ui.screens.chat

import androidx.compose.runtime.Immutable
import app.daiyu.android.ui.model.ConversationSummaryUi

/** Only fields rendered by the management list; preview/timestamp churn is not list content. */
@Immutable
internal data class ManageChatListItem(
    val id: String,
    val title: String,
    val timeLabel: String,
    val isPinned: Boolean,
    val isActiveRun: Boolean,
    val hasCompletionMarker: Boolean,
)

internal fun ConversationSummaryUi.manageChatListItem() = ManageChatListItem(
    id = id,
    title = title.ifBlank { preview },
    timeLabel = timeLabel,
    isPinned = isPinned,
    isActiveRun = isActiveRun,
    hasCompletionMarker = hasCompletionMarker,
)
