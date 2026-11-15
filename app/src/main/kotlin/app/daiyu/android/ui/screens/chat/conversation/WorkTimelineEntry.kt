package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun WorkTimelineEntry(
    leadingConnector: Boolean,
    trailingConnector: Boolean = false,
    throughConnector: Boolean = false,
    spacingAfter: Dp = 0.dp,
    fillWidth: Boolean = true,
    content: @Composable () -> Unit,
) {
    val line = conversationColors().line
    val motion = LocalWorkflowLineMotion.current
    Box(Modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).drawBehind {
        val x = 5.dp.toPx()
        if (throughConnector) drawLine(line, Offset(x, 0f), Offset(x, size.height * (motion.through?.fraction() ?: 1f)), 1.dp.toPx())
        if (leadingConnector) drawLine(line, Offset(x, 0f), Offset(x, 17.dp.toPx() * (motion.incoming?.fraction() ?: 1f)), 1.dp.toPx())
        if (trailingConnector && spacingAfter > 0.dp) {
            val start = size.height - spacingAfter.toPx()
            val end = 27.dp.toPx() + (size.height - 27.dp.toPx()) * (motion.outgoing?.fraction() ?: 1f)
            if (end > start) drawLine(line, Offset(x, start), Offset(x, end), 1.dp.toPx())
        }
    }.padding(bottom = spacingAfter)) { content() }
}
