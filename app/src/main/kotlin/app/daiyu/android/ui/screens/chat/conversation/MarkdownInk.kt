package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult

/** Position is a fractional UTF-16 offset for drawing; the clock spends graphemes, not code units. */
internal class MarkdownInk(start: Int) {
    var position by mutableDoubleStateOf(start.toDouble()); private set
    private val paintedEnds = mutableMapOf<Int, Double>()
    fun paintPosition(offset: Int, length: Int, stopped: Boolean): Double {
        val visible = (position - offset).coerceIn(0.0, length.toDouble())
        if (stopped) return (paintedEnds[offset] ?: 0.0).coerceAtMost(visible)
        if (position <= offset + length) lastDrawnProgress = position
        paintedEnds[offset] = visible
        return visible
    }
    private var lastDrawnProgress = -1.0

    fun visibleHeight(layout: TextLayoutResult, offset: Int, stopped: Boolean): Int {
        val length = layout.layoutInput.text.length
        val local = if (stopped) paintedEnds[offset] ?: 0.0 else position - offset
        if (local <= 0.0) return 0
        if (local >= length) return layout.size.height
        val last = (kotlin.math.ceil(local).toInt() - 1).coerceIn(0, length - 1)
        return kotlin.math.ceil(layout.getLineBottom(layout.getLineForOffset(last))).toInt()
    }

    fun advance(text: String, boundaries: MarkdownTextBoundaries, limit: Int, seconds: Double) {
        val end = boundaries.floor(limit.coerceIn(0, text.length))
        if (position >= end) { position = end.toDouble(); return }
        // A delayed first layout or a blocked draw cannot spend invisible animation time.
        if (lastDrawnProgress != position && text[position.toInt()] != '\uFFFC') return
        var start = boundaries.floor(position.toInt())
        var next = boundaries.next(start)
        val fraction = (position - start) / (next - start)
        // At 48 remaining graphemes the upstream speed already saturates (48 / .2 == 240).
        var scan = start
        var count = 0
        while (scan < end && count < 49) { scan = boundaries.next(scan); count++ }
        var budget = ((count - fraction) / .2).coerceIn(36.0, 240.0) * seconds.coerceIn(0.0, .05) + fraction
        while (budget >= 1.0 && next <= end) {
            start = next
            budget -= 1.0
            if (start == end) break
            next = boundaries.next(start)
        }
        position = if (start == end) end.toDouble() else start + (next - start) * budget
    }
}

internal val LocalMarkdownInk = staticCompositionLocalOf<MarkdownInk?> { null }
internal val LocalInkOffset = staticCompositionLocalOf { 0 }

@Composable
internal fun rememberPartInk(reveal: MarkdownReveal, id: String): MarkdownInk? {
    if (!reveal.inkEnabled) return null
    val text = reveal.displayText(id)
    val bounds = remember(text) { MarkdownTextBoundaries(text) }
    val currentText by rememberUpdatedState(text)
    val currentBounds by rememberUpdatedState(bounds)
    val admission = reveal.visibility(id)
    val ink = remember(reveal.publication, id) { reveal.inkFor(id) }
    val paused = LocalReplyPlayback.current?.let { it.paused || it.control.stopped } == true || !LocalChatPresentationActive.current
    val busy by remember(ink, admission) { derivedStateOf { ink.position != admission.value.toDouble() } }
    val control = LocalReplyPlayback.current?.control
    val playback = LocalReplyPlayback.current
    val currentPlayback by rememberUpdatedState(playback)
    SideEffect { reveal.publication.inkPending(ink, busy && control?.stopped != true); control?.reportPending(ink, busy) }
    DisposableEffect(ink, control) { onDispose { reveal.publication.inkPending(ink, false); control?.reportPending(ink, false) } }
    LaunchedEffect(ink, admission, busy, paused) {
        if (busy && !paused) {
            var before = withFrameNanos { it }
            while (ink.position != admission.value.toDouble()) {
                withFrameNanos { now ->
                    if (currentPlayback?.let { it.paused || it.control.stopped } != true)
                        ink.advance(currentText, currentBounds, admission.value, (now - before) / 1e9)
                    before = now
                }
            }
        }
    }
    return ink
}

internal fun ContentDrawScope.drawMarkdownInk(layout: TextLayoutResult?, ink: MarkdownInk?, offset: Int,
    boundaries: MarkdownTextBoundaries?, stopped: Boolean) {
    if (ink == null || boundaries == null) { drawContent(); return }
    if (layout == null) return
    val end = layout.layoutInput.text.length
    val p = ink.paintPosition(offset, end, stopped)
    if (p >= end) { drawContent(); return }
    val whole = boundaries.floor(p.toInt())
    val scope = this
    if (whole > 0) clipPath(layout.getPathForRange(0, whole)) { scope.drawContent() }
    val next = boundaries.next(whole).coerceAtMost(end)
    if (p > whole && next > whole) clipPath(layout.getPathForRange(whole, next)) {
        val canvas = drawContext.canvas
        canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = ((p - whole) / (next - whole)).toFloat() })
        try { scope.drawContent() } finally { canvas.restore() }
    }
}
