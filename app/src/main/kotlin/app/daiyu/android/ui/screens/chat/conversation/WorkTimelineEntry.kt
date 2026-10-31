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
    content: @Composable () -> Unit,
) {
    val line = conversationColors().line
    Box(Modifier.fillMaxWidth().drawBehind {
        val x = 5.dp.toPx()
        if (throughConnector) drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        if (leadingConnector) drawLine(line, Offset(x, 0f), Offset(x, 17.dp.toPx()), 1.dp.toPx())
        if (trailingConnector && spacingAfter > 0.dp) drawLine(line,
            Offset(x, size.height - spacingAfter.toPx()), Offset(x, size.height), 1.dp.toPx())
    }.padding(bottom = spacingAfter)) { content() }
}
