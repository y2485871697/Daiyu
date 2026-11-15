package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.*

/** Pure text adapter over the conversation's character clock, including paced final snapshots. */
internal class CallCaptionTypingState {
    private var motion = MarkdownTypingState(emptyList())
    private var previous = ""
    private var current: MarkdownTypingTarget? = null
    fun update(text: String, complete: Boolean): MarkdownTypingTarget {
        current?.let { if (text == previous && complete == it.terminal) return it }
        if (!text.startsWith(previous)) motion = MarkdownTypingState(emptyList())
        previous = text
        return checkNotNull(motion.update(listOf(MarkdownTypingPart(MarkdownTypingSource("caption", text), text.length)),
            0, streaming = !complete, terminal = complete, live = true, paceTerminal = true)).also { current = it }
    }
    fun visible(target: MarkdownTypingTarget) = motion.visible(target)
    fun pause(target: MarkdownTypingTarget) = motion.pause(target)
    fun advance(target: MarkdownTypingTarget, nanos: Long) = motion.advance(target, nanos)
}

@Composable
internal fun rememberCallCaptionReveal(text: String, complete: Boolean, running: Boolean): State<String> {
    val motion = remember { CallCaptionTypingState() }
    val target = remember(text, complete) { motion.update(text, complete) }
    val boundaries = remember(text) { MarkdownTextBoundaries(text) }
    val active by rememberUpdatedState(running)
    val frame = produceState(MarkdownTypingFrame(target, target.start), target, running) {
        var shown = motion.visible(target)
        value = MarkdownTypingFrame(target, shown)
        if (!running) { motion.pause(target); return@produceState }
        while (shown < target.total) {
            shown = withFrameNanos { nanos ->
                if (active) motion.advance(target, nanos) else { motion.pause(target); motion.visible(target) }
            }
            value = MarkdownTypingFrame(target, shown)
        }
    }
    return remember(text, target, frame) { derivedStateOf {
        val shown = frame.value.let { if (it.target === target) it.visible else target.start }
        text.take(boundaries.end(shown))
    } }
}
