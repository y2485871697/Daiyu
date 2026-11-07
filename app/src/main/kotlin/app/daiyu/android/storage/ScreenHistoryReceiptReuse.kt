package app.daiyu.android.storage

import app.daiyu.android.domain.MessageBlock

/** Lease-local reuse of one pure predicate, never SQL, owner or external video evidence. */
internal class ScreenHistoryReceiptReuse(
    private val maxCharacters: Long = 8L * 1024 * 1024,
    private val maxEntries: Int = 8,
) {
    init { require(maxCharacters >= 0 && maxEntries >= 0) }
    private data class Entry(val items: List<String>, val tools: List<MessageBlock.Tool>,
        val index: Int, val characters: Long)
    private val entries = ArrayList<Entry>()
    internal var retainedCharacters = 0L
        private set
    internal val retainedEntries: Int get() = entries.size
    internal var validationCalls = 0L
        private set
    internal var hits = 0L
        private set

    fun validate(items: List<String>, blocks: List<MessageBlock>, index: Int, proof: () -> Boolean): Boolean {
        val tools = blocks.filterIsInstance<MessageBlock.Tool>()
        if (entries.any { it.index == index && it.items == items && it.tools == tools }) {
            hits++
            return true
        }
        validationCalls++
        if (!proof()) return false
        val size = items.sumOf { it.length.toLong() } + tools.sumOf {
            it.invocation.id.value.length.toLong() + it.invocation.name.length +
                it.invocation.arguments.length + (it.result?.length ?: 0)
        }
        if (maxEntries > 0 && size <= maxCharacters) {
            while (entries.isNotEmpty() && (entries.size >= maxEntries || retainedCharacters > maxCharacters - size)) {
                retainedCharacters -= entries.removeAt(0).characters
            }
            // Domain leaves list implementations to callers. Retain copies, never their mutable lists.
            entries += Entry(ArrayList(items), ArrayList(tools), index, size)
            retainedCharacters += size
        }
        return true
    }

    fun clear() {
        entries.clear()
        retainedCharacters = 0
    }
}
