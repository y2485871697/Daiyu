package app.daiyu.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.subagent.ConversationAgentButton
import app.daiyu.android.ui.components.subagent.ConversationAgentHandle
import app.daiyu.android.ui.components.subagent.ConversationAgentSheet
import app.daiyu.android.ui.components.subagent.ConversationAgentTypography
import app.daiyu.android.ui.components.subagent.conversationAgentColors

@Composable
internal fun VoiceLocalRemovalSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConversationAgentTypography {
        ConversationAgentSheet(onDismiss) {
            Column(Modifier.fillMaxWidth().testTag("voice-local-removal-sheet")
                .semantics { paneTitle = "确认本次操作" }
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                ConversationAgentHandle()
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 29.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("确认本次操作", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                HorizontalDivider(color = conversationAgentColors().line)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConversationAgentButton("取消", modifier = Modifier.weight(1f).fillMaxHeight().testTag("dialog-cancel"),
                        onClick = onDismiss)
                    ConversationAgentButton("确认", primary = true,
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("dialog-confirm"), onClick = onConfirm)
                }
            }
        }
    }
}
