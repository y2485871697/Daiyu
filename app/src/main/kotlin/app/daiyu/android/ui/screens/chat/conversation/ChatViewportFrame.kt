package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

private class ViewportFrameRecord { var hasFrame = false }

/** Commit a positioned viewport atomically; an unready visit cannot erase the last drawn frame. */
@Composable
internal fun ChatViewportFrame(
    owner: Any?,
    empty: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ((Boolean) -> Unit) -> Unit,
) {
    val frame = rememberGraphicsLayer()
    val recorded = remember { ViewportFrameRecord() }
    // A retired visit only holds its own readiness cell, never the current visit's permission.
    val readiness = remember(owner, empty) { mutableStateOf(empty) }
    val report = remember(readiness) { { ready: Boolean -> readiness.value = empty || ready } }
    val ready = readiness.value
    Box(modifier.clipToBounds()
        .drawWithContent {
            if (readiness.value) {
                frame.record { this@drawWithContent.drawContent() }
                recorded.hasFrame = true
            }
            if (recorded.hasFrame) drawLayer(frame)
        }
        .then(if (ready) Modifier else Modifier.clearAndSetSemantics {})
        .pointerInput(ready) {
            if (!ready) awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }) { content(report) }
}
