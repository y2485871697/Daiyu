package app.daiyu.android.storage

import app.daiyu.android.domain.Message
import java.io.Closeable
import java.util.Collections

/** Exact values read from SQL, not a prospective write or a metadata/version stamp. */
internal data class StoredMessageColumns(
    val messageId: String,
    val conversationId: String,
    val runId: String,
    val role: String,
    val status: String,
    val createdAt: Long?,
    val blocks: String,
    val error: String?,
    val protocol: String,
    val history: String,
) {
    // UTF-16 code units retained in the raw key. Decoded strings/collections are also
    // bounded by the encoded input size; this is an input budget, not a heap estimate.
    val characterCount: Long get() = listOf(messageId, conversationId, runId, role, status,
        blocks, error, protocol, history).sumOf { it?.length?.toLong() ?: 0L }
}

/**
 * Bounded parsing reuse, confined to one runtime store lease. Cleared on failed
 * writes, terminal commits and lease release; never shares mutable caller graphs.
 * Every lookup receives freshly read, complete SQL columns. Only parsing/graph
 * construction is reused: validation (including external video witnesses) runs again.
 * Linear exact comparisons deliberately avoid hashes and content/version surrogates.
 */
internal class CommitMessageDecodeReuse(
    private val maxCharacters: Long = 8L * 1024 * 1024,
    private val maxEntries: Int = 8,
) : Closeable {
    init { require(maxCharacters >= 0 && maxEntries >= 0) }
    private data class Entry(val columns: StoredMessageColumns, val message: Message)
    private val entries = ArrayList<Entry>()
    internal val historyReceipts = ScreenHistoryReceiptReuse()
    private var characters = 0L
    private var closed = false
    internal var decodeCount: Int = 0
        private set
    internal val retainedCharacters: Long get() = characters
    internal val retainedEntries: Int get() = entries.size

    fun read(columns: StoredMessageColumns, decode: () -> Message, validate: (Message) -> Unit): Message {
        check(!closed) { "Message decode context already closed" }
        val hit = entries.firstOrNull { it.columns == columns }
        if (hit != null) {
            try { validate(hit.message) } catch (failure: Throwable) {
                entries.remove(hit)
                characters -= hit.columns.characterCount
                historyReceipts.clear()
                throw failure
            }
            return hit.message
        }
        decodeCount++
        val message = try {
            snapshot(decode()).also(validate)
        } catch (failure: Throwable) {
            historyReceipts.clear()
            throw failure
        } // Never retain a partially decoded or unverified database read.
        val size = columns.characterCount
        if (maxEntries > 0 && size <= maxCharacters) {
            while (entries.isNotEmpty() && (entries.size >= maxEntries || characters > maxCharacters - size)) {
                characters -= entries.removeAt(0).columns.characterCount
            }
            entries.add(Entry(columns, message))
            characters += size
        }
        return message
    }

    fun clear() {
        historyReceipts.clear()
        entries.clear()
        characters = 0
    }

    override fun close() {
        clear()
        closed = true
    }

    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

    // The domain leaves list implementations up to callers. Copy EVERY list level
    // before retaining/exposing a graph; leaf blocks, references and reasoning are values.
    private fun snapshot(message: Message): Message = message.copy(
        blocks = frozen(message.blocks),
        protocolItems = frozen(message.protocolItems),
        reportedReasoning = frozen(message.reportedReasoning),
        historySegments = frozen(message.historySegments.map { segment -> segment.copy(
            blocks = frozen(segment.blocks),
            protocolItems = frozen(segment.protocolItems),
            reportedReasoning = frozen(segment.reportedReasoning),
        ) }),
    )
}
