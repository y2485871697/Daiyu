package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock

/** Reuse immutable, detached blocks only after the current whole document has been parsed. */
internal fun reuseMarkdownBlocks(previous: List<MarkdownBlock>?, parsed: List<MarkdownBlock>): List<MarkdownBlock> {
    if (previous == null) return parsed
    var unchanged = previous.size == parsed.size
    val blocks = parsed.mapIndexed { index, block ->
        val old = previous.getOrNull(index)
        val reused = old?.takeIf { it == block } ?: block
        if (reused !== old) unchanged = false
        reused
    }
    return if (unchanged) previous else blocks
}
