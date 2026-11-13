package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import java.util.LinkedHashMap
import java.util.IdentityHashMap

/** Activity-owned pure presentation reuse; receipt state and execution authority are never cached. */
class ToolPresentationCache internal constructor(
    private val build: (String, String, String?) -> ToolCardPresentations.Presentation,
    private val maxEntries: Int = 64,
    private val maxSourceChars: Int = 262_144,
    private val maxLargeEntries: Int = 2,
) {
    constructor() : this(ToolCardPresentations::present)

    private class Key(val name: String, val arguments: String, val result: String?, val large: Boolean) {
        val sourceChars: Long get() = name.length.toLong() + arguments.length + (result?.length ?: 0)
        override fun hashCode(): Int = if (large) 31 * (31 * name.hashCode() +
            System.identityHashCode(arguments)) + System.identityHashCode(result)
            else 31 * (31 * name.hashCode() + arguments.hashCode()) + (result?.hashCode() ?: 0)
        override fun equals(other: Any?): Boolean = other is Key && name == other.name && large == other.large &&
            if (large) arguments === other.arguments && result === other.result
            else arguments == other.arguments && result == other.result
    }

    private val entries = LinkedHashMap<Key, ToolCardPresentations.Presentation>(16, 0.75f, true)
    private val mounted = IdentityHashMap<MessageBlock.Tool, ToolCardPresentations.Presentation?>()
    private var sourceChars = 0L
    private var largeEntries = 0
    private var conversation: String? = null

    internal fun present(block: MessageBlock.Tool): ToolCardPresentations.Presentation {
        mounted[block]?.let { return it }
        val key = key(block)
        entries[key]?.let {
            if (mounted.containsKey(block)) mounted[block] = it
            return it
        }
        val presentation = build(key.name, key.arguments, key.result)
        if (mounted.containsKey(block)) mounted[block] = presentation
        if (maxEntries <= 0 || (key.large && (maxLargeEntries <= 0 ||
                key.sourceChars > app.daiyu.android.media.NativeScreenImage.MAX_RESULT_CHARS))) return presentation
        entries[key] = presentation
        if (key.large) largeEntries++ else sourceChars += key.sourceChars
        val oldest = entries.entries.iterator()
        while (entries.size > maxEntries || sourceChars > maxSourceChars || largeEntries > maxLargeEntries) {
            release(oldest.next().key)
            oldest.remove()
        }
        return presentation
    }

    private fun release(key: Key) {
        if (key.large) largeEntries-- else sourceChars -= key.sourceChars
    }

    internal fun retain(messages: List<Message>) {
        // Mounted rows already own these projections. Do not let the smaller off-window
        // LRU evict them in scan order and reparse every result on each streaming update.
        retainBlocks(messages.flatMap { it.blocks })
    }

    internal fun retainBlocks(blocks: List<MessageBlock>) {
        val sources = IdentityHashMap<MessageBlock.Tool, Boolean>()
        blocks.forEach { block -> if (block is MessageBlock.Tool) sources[block] = true }
        mounted.keys.retainAll(sources.keys)
        sources.keys.forEach { if (!mounted.containsKey(it)) mounted[it] = null }
        if (entries.isEmpty()) return
        val retained = sources.keys.map(::key).toHashSet()
        val existing = entries.entries.iterator()
        while (existing.hasNext()) {
            val entry = existing.next()
            if (entry.key !in retained) {
                release(entry.key)
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
        mounted.clear()
        sourceChars = 0L
        largeEntries = 0
    }

    private fun key(block: MessageBlock.Tool) = Key(block.invocation.name, block.invocation.arguments, block.result,
        block.invocation.name.length.toLong() + block.invocation.arguments.length + (block.result?.length ?: 0) > maxSourceChars)
}
