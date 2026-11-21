package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult

/** One in-flight slice and one latest destination. New arrivals cannot move the in-flight deadline. */
internal class MarkdownInk(start: Int) {
    var position by mutableDoubleStateOf(start.toDouble()); private set
    private val paintedEnds = mutableMapOf<Int, Double>()
    fun paintPosition(offset: Int, length: Int, stopped: Boolean): Double {
        val visible = (position - offset).coerceIn(0.0, length.toDouble())
        if (stopped) return (paintedEnds[offset] ?: 0.0).coerceAtMost(visible)
        paintedEnds[offset] = visible
        return visible
    }
    private var goal = start
    private var unitsPerSecond = 0.0
    fun advance(end: Int, seconds: Double) {
        if (end < position) { position = end.toDouble(); goal = end; return }
        if (position >= goal || goal > end) {
            goal = end
            unitsPerSecond = maxOf(36.0, (goal - position) / .096)
        }
        position = minOf(goal.toDouble(), position + seconds.coerceIn(0.0, .05) * unitsPerSecond)
    }
}

internal val LocalMarkdownInk = staticCompositionLocalOf<MarkdownInk?> { null }
internal val LocalInkOffset = staticCompositionLocalOf { 0 }

@Composable
internal fun rememberPartInk(reveal: MarkdownReveal, id: String): MarkdownInk? {
    if (!reveal.inkEnabled) return null
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
                        ink.advance(admission.value, (now - before) / 1e9)
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
