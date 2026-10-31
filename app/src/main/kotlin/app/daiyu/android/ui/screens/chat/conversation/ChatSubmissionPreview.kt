package app.daiyu.android.ui.screens.chat.conversation

/** Display only. A preparation preview is neither a saved message nor an action target. */
data class ChatSubmissionPreview(
    val key: String,
    val text: String? = null,
    val replaceFromKey: String? = null,
    val settledAssistantId: String? = null,
    val settledUserId: String? = null,
)

internal fun submissionTurns(turns: List<ChatTurnUi>, preview: ChatSubmissionPreview?): List<ChatTurnUi> {
    if (preview == null) return turns
    val settledId = preview.settledUserId ?: preview.settledAssistantId
    if (settledId != null && turns.any { it.id == settledId }) return turns
    preview.replaceFromKey?.let { key ->
        val index = turns.indexOfFirst { it.matchesKey(key) }
        return if (index < 0) turns else turns.take(index)
    }
    val text = preview.text ?: return turns
    val pending = ChatTurnUi.User(preview.key, text, pending = true)
    val assistantIndex = preview.settledAssistantId?.let { id -> turns.indexOfFirst { it.id == id } } ?: -1
    return if (assistantIndex < 0) turns + pending else turns.take(assistantIndex) + pending + turns.drop(assistantIndex)
}
