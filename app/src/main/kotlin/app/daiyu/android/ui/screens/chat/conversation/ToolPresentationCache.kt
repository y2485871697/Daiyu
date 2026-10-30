package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import java.util.LinkedHashMap

/** Activity-owned pure presentation reuse; receipt state and execution authority are never cached. */
class ToolPresentationCache internal constructor(
    private val build: (String, String, String?) -> ToolCardPresentations.Presentation,
    private val maxEntries: Int = 64,
    private val maxSourceChars: Int = 262_144,
) {
    constructor() : this(ToolCardPresentations::present)

    private data class Key(val name: String, val arguments: String, val result: String?) {
        val sourceChars: Long get() = name.length.toLong() + arguments.length + (result?.length ?: 0)
    }

    private val entries = LinkedHashMap<Key, ToolCardPresentations.Presentation>(16, 0.75f, true)
    private var sourceChars = 0L
    private var conversation: String? = null

    internal fun present(block: MessageBlock.Tool): ToolCardPresentations.Presentation {
        val key = key(block)
        entries[key]?.let { return it }
        val presentation = build(key.name, key.arguments, key.result)
        if (maxEntries <= 0 || key.sourceChars > maxSourceChars) return presentation
        entries[key] = presentation
        sourceChars += key.sourceChars
        val oldest = entries.entries.iterator()
        while (entries.size > maxEntries || sourceChars > maxSourceChars) {
            sourceChars -= oldest.next().key.sourceChars
            oldest.remove()
        }
        return presentation
    }

    internal fun retain(messages: List<Message>) {
        if (entries.isEmpty()) return
        val retained = messages.asSequence().flatMap { it.blocks.asSequence() }
            .filterIsInstance<MessageBlock.Tool>().map(::key).toHashSet()
        val existing = entries.entries.iterator()
        while (existing.hasNext()) {
            val entry = existing.next()
            if (entry.key !in retained) {
                sourceChars -= entry.key.sourceChars
                existing.remove()
            }
        }
    }

    internal fun scope(conversation: String?) {
        if (this.conversation == conversation) return
        clear()
        this.conversation = conversation
    }

    fun clear() {
        entries.clear()
        sourceChars = 0L
    }

    private fun key(block: MessageBlock.Tool) = Key(block.invocation.name, block.invocation.arguments, block.result)
}
