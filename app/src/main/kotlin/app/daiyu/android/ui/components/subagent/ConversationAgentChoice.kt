package app.daiyu.android.ui.components.subagent

import androidx.compose.runtime.Composable
import app.daiyu.android.ui.components.OptionSelectionItem
import app.daiyu.android.ui.components.OptionSelectionSheet

@Composable
internal fun SubAgentChoiceWindow(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    OptionSelectionSheet(title, onDismiss) { content() }
}

@Composable
internal fun ConversationAgentChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    OptionSelectionItem(label, selected, onClick = onClick)
}
