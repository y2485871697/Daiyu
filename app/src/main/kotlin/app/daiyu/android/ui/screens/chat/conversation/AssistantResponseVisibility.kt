package app.daiyu.android.ui.screens.chat.conversation

internal fun ChatTurnUi.Assistant.hasVisibleResponse(): Boolean {
    if (!error.isNullOrBlank()) return true
    val integratedReasoning = if (content.isNotEmpty()) content.any { it is MessageContentUi.Reasoning }
        else workProcess?.segments.orEmpty().any { it.reasoning != null }
    if (!integratedReasoning && reportedReasoning.any { hasReportedReasoningText(it.text) }) return true
    return if (content.isNotEmpty()) {
        content.any {
            when (it) {
                is MessageContentUi.Text -> it.text.isNotBlank()
                is MessageContentUi.Reasoning -> hasReportedReasoningText(it.reported.text)
                is MessageContentUi.Image, is MessageContentUi.Video, is MessageContentUi.Tool -> true
            }
        }
    } else {
        finalText.isNotBlank() || workProcess?.segments.orEmpty().any {
            !it.narration.isNullOrBlank() || it.steps.isNotEmpty() ||
                it.reasoning?.let { reasoning -> hasReportedReasoningText(reasoning.text) } == true
        }
    }
}
