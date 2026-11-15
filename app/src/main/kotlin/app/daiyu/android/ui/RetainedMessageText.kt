package app.daiyu.android.ui

import app.daiyu.android.domain.MessageBlock

/** 搜索只使用消息正文，不包含工具调用、结果或图片元数据。 */
internal fun retainedSearchText(blocks: List<MessageBlock>): String =
    blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }
