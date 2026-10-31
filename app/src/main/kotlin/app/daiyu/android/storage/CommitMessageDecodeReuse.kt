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
 * Owned by ONE commit's transaction body and closed in its finally/use block.
 * Never store this on a database/store or carry it into the next transaction.
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
                throw failure
            }
            return hit.message
        }
        decodeCount++
        val message = snapshot(decode())
        validate(message) // Never retain a partially decoded or unverified database read.
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

    override fun close() {
        entries.clear()
        characters = 0
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
