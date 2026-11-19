package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.drawscope.translate

private class ComposerAttachmentFrame {
    var targetHeight = 0
    var recordedHeight = 0
    var exitStartHeight = 0f
}

/** Retain pixels, not draft capabilities, while the attachment strip shrinks. */
@Composable
internal fun ComposerAttachmentExit(content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val frame = remember { ComposerAttachmentFrame() }
    Column(Modifier.fillMaxWidth().testTag("composer-attachment-exit").clipToBounds()
        .drawWithContent {
            if (frame.targetHeight == 0 && frame.recordedHeight > 0 && size.height > 0f) {
                if (frame.exitStartHeight == 0f) frame.exitStartHeight = size.height
                // The measured height is the only exit clock, including interrupted entrances.
                layer.alpha = (size.height / frame.exitStartHeight).coerceIn(0f, 1f)
                translate(top = size.height - frame.recordedHeight) { drawLayer(layer) }
            } else {
                frame.exitStartHeight = 0f
                if (frame.targetHeight == 0) frame.recordedHeight = 0
                drawContent()
            }
        }
        .animateContentSize(tween(180, easing = FastOutSlowInEasing), alignment = Alignment.BottomCenter)
        .onSizeChanged { frame.targetHeight = it.height }
        .drawWithContent {
            if (size.height > 0f) {
                // Record inside the changing size clip so its RenderNode is never retained.
                layer.alpha = 1f
                layer.record { this@drawWithContent.drawContent() }
                frame.recordedHeight = size.height.toInt()
                drawLayer(layer)
            } else drawContent()
        },
    ) { content() }
}
