package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag

private class ComposerAttachmentFrame {
    var visualRevision by mutableIntStateOf(0)
    var targetHeight = 0
    var bitmap: ImageBitmap? = null
    var canvas: Canvas? = null
    var exitStartHeight = 0f
}

private val LocalComposerAttachmentVisualChange = staticCompositionLocalOf<() -> Unit> { {} }

/** Child-only image decoding must also invalidate the independently retained raster. */
@Composable
internal fun ObserveComposerAttachmentVisual(value: Any?) {
    val changed = LocalComposerAttachmentVisualChange.current
    val previous = remember { arrayOf<Any?>(Any()) }
    SideEffect {
        if (previous[0] != value) {
            previous[0] = value
            changed()
        }
    }
}

/** Retain raster pixels, never a removed child's RenderNode or draft capabilities. */
@Composable
internal fun ComposerAttachmentExit(content: @Composable () -> Unit) {
    val frame = remember { ComposerAttachmentFrame() }
    val changed = remember(frame) { { frame.visualRevision++; Unit } }
    Column(Modifier.fillMaxWidth().testTag("composer-attachment-exit").clipToBounds()
        .drawWithContent {
            frame.visualRevision
            val pixels = frame.bitmap
            if (frame.targetHeight == 0 && pixels != null && size.height > 0f) {
                if (frame.exitStartHeight == 0f) frame.exitStartHeight = size.height
                // The measured height is the only exit clock, including interrupted entrances.
                drawImage(pixels, topLeft = Offset(0f, size.height - pixels.height),
                    alpha = (size.height / frame.exitStartHeight).coerceIn(0f, 1f))
            } else {
                frame.exitStartHeight = 0f
                if (frame.targetHeight == 0) {
                    frame.bitmap = null
                    frame.canvas = null
                }
                drawContent()
            }
        }
        .animateContentSize(tween(180, easing = FastOutSlowInEasing), alignment = Alignment.BottomCenter)
        .layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            frame.targetHeight = child.height
            layout(child.width, child.height) { child.placeRelative(0, 0) }
        }
        .drawWithContent {
            frame.visualRevision
            val width = size.width.toInt()
            val height = size.height.toInt()
            if (width > 0 && height > 0) {
                if (frame.bitmap?.width != width || frame.bitmap?.height != height) {
                    frame.bitmap = ImageBitmap(width, height)
                    frame.canvas = Canvas(checkNotNull(frame.bitmap))
                }
                // Complete the software snapshot while the child is still alive; no async readback.
                draw(this, layoutDirection, checkNotNull(frame.canvas), size) {
                    drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                    this@drawWithContent.drawContent()
                }
            }
            // Live attachments keep their normal hardware drawing, independently of the exit copy.
            drawContent()
        },
    ) { CompositionLocalProvider(LocalComposerAttachmentVisualChange provides changed) { content() } }
}
