package app.daiyu.android.ui

import app.daiyu.android.domain.MessageBlock

/** Search text only: image identity/metadata are neither searchable content nor display labels. */
internal fun retainedSearchText(blocks: List<MessageBlock>): String = blocks.mapNotNull { block ->
    when (block) {
        is MessageBlock.Text -> block.text
        is MessageBlock.Tool -> listOfNotNull(block.invocation.name, block.invocation.arguments, block.result).joinToString(" ")
        is MessageBlock.Image -> null
    }
}.joinToString("\n")
