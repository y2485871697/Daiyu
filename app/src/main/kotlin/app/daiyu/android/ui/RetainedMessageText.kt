package app.daiyu.android.ui

import app.daiyu.android.domain.MessageBlock

/** 搜索正文和工具调用文本；图片块不参与文本匹配。 */
internal fun retainedSearchText(blocks: List<MessageBlock>): String = blocks.mapNotNull { block ->
    when (block) {
        is MessageBlock.Text -> block.text
        is MessageBlock.Tool -> listOfNotNull(block.invocation.name, block.invocation.arguments, block.result).joinToString(" ")
        is MessageBlock.Image -> null
    }
}.joinToString("\n")
