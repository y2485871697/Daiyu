package app.daiyu.android.storage

import app.daiyu.android.domain.Message
import app.daiyu.android.diagnostics.RuntimeDiagnostics
import java.io.Closeable
import java.util.Collections

/** 从 SQL 读出的完整原值，用于精确匹配缓存。 */
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

/** 当前存储租约内的有界解析缓存，写入失败、终态提交或租约释放时清空。
 * 查找使用刚从 SQL 确认的完整列值，逐项精确比较；解析图与调用方隔离。
 * 仅复用解析结果，每次仍执行完整校验，包括外部视频校验。
 */
internal class CommitMessageDecodeReuse(
    private val maxCharacters: Long = 20L * 1024 * 1024,
    private val maxEntries: Int = 8,
) : Closeable {
    init { require(maxCharacters >= 0 && maxEntries >= 0) }
    private data class Entry(val columns: StoredMessageColumns, val message: Message)
    private val entries = ArrayList<Entry>()
    internal val historyReceipts = ScreenHistoryReceiptReuse()
    internal val textReads = SQLiteVerifiedTextReuse()
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
                textReads.clear()
                throw failure
            }
            textReads.remember(columns)
            RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.MESSAGE_DECODE, RuntimeDiagnostics.Code.CACHE_HIT)
            return hit.message
        }
        val size = columns.characterCount
        // Release entries that cannot coexist with the next graph before constructing it.
        if (maxEntries > 0 && size <= maxCharacters) {
            while (entries.isNotEmpty() && (entries.size >= maxEntries || characters > maxCharacters - size)) {
                characters -= entries.removeAt(0).columns.characterCount
            }
        }
        RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.MESSAGE_DECODE,
            if (size <= maxCharacters) RuntimeDiagnostics.Code.CACHE_MISS else RuntimeDiagnostics.Code.CACHE_BYPASS, size = size)
        decodeCount++
        val message = try {
            RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.MESSAGE_DECODE, size = size) {
                snapshot(decode()).also(validate)
            }
        } catch (failure: Throwable) {
            historyReceipts.clear()
            textReads.clear()
            throw failure
        } // Never retain a partially decoded or unverified database read.
        textReads.remember(columns)
        if (maxEntries > 0 && size <= maxCharacters) {
            entries.add(Entry(columns, message))
            characters += size
        }
        return message
    }

    fun clear() {
        textReads.clear()
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
