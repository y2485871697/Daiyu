package app.daiyu.android.ui

import app.daiyu.android.domain.MessageBlock

/** 搜索只使用消息正文，不包含工具调用、结果或图片元数据。 */
internal fun retainedSearchText(blocks: List<MessageBlock>): String =
    blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }

internal fun retainedSearchSnippet(text: String, matchStart: Int, matchLength: Int): String {
    var start = matchStart
    var end = matchStart + matchLength
    repeat(12) { if (start > 0) start = text.offsetByCodePoints(start, -1) }
    repeat(80) { if (end < text.length) end = text.offsetByCodePoints(end, 1) }
    return text.substring(start, end)
}
