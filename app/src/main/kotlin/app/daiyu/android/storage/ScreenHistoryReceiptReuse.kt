package app.daiyu.android.storage

import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.diagnostics.RuntimeDiagnostics

/** 当前租约内缓存屏幕历史回执的纯校验结果，SQL、所有者和外部视频校验每次重做。 */
internal class ScreenHistoryReceiptReuse(
    private val maxCharacters: Long = 16L * 1024 * 1024,
    private val maxEntries: Int = 8,
    private val maxIndices: Int = 128,
) {
    init { require(maxCharacters >= 0 && maxEntries >= 0 && maxIndices >= 0) }
    private data class Entry(val items: List<String>, val tools: List<MessageBlock.Tool>,
        val indices: MutableSet<Int>, val characters: Long)
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
        val context = entries.firstOrNull { it.items == items && it.tools == tools }
        if (context != null && index in context.indices) {
            hits++
            RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.HISTORY_RECEIPT, RuntimeDiagnostics.Code.CACHE_HIT)
            return true
        }
        validationCalls++
        val valid = RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.HISTORY_RECEIPT) { proof() }
        if (!valid) return false
        if (context != null) {
            if (context.indices.size < maxIndices) context.indices += index
            return true
        }
        val size = items.sumOf { it.length.toLong() } + tools.sumOf {
            it.invocation.id.value.length.toLong() + it.invocation.name.length +
                it.invocation.arguments.length + (it.result?.length ?: 0)
        }
        if (maxEntries > 0 && maxIndices > 0 && size <= maxCharacters) {
            while (entries.isNotEmpty() && (entries.size >= maxEntries || retainedCharacters > maxCharacters - size)) {
                retainedCharacters -= entries.removeAt(0).characters
            }
            // Domain leaves list implementations to callers. Retain copies, never their mutable lists.
            entries += Entry(ArrayList(items), ArrayList(tools), mutableSetOf(index), size)
            retainedCharacters += size
        }
        return true
    }

    fun clear() {
        entries.clear()
        retainedCharacters = 0
    }
}
