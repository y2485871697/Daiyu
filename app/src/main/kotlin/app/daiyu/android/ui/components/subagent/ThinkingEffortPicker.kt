package app.daiyu.android.ui.components.subagent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.daiyu.android.delegation.ReasoningEffort

@Composable
internal fun SubAgentThinkingEffortPickerDialog(
    show: Boolean,
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    onDismiss: () -> Unit,
    onEffortChange: (ReasoningEffort) -> Unit,
    description: String? = null,
    mainChat: Boolean = false,
) {
    if (!show || options.isEmpty()) return
    CompositionLocalProvider(LocalConversationAgentStyle provides !mainChat) {
        ConversationThinkingPicker(show, effort, options, onDismiss, onEffortChange, description)
    }
}
