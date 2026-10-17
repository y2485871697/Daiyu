package app.daiyu.android.ui

import android.content.ContentValues
import android.os.CancellationSignal
import app.daiyu.android.domain.context.ContextRevision
import java.security.MessageDigest
import app.daiyu.android.context.ContextService
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.storage.RecordedUsageReader
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.ui.model.*
import app.daiyu.android.data.repository.UsageStatsSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Read-only projection of this application's records; never reads the old application's data. */
internal class RetainedConversationData(private val database: ConversationDatabase, private val context: ContextService) {
    fun summaries(active: Message?): List<ConversationSummaryUi> {
        val today = LocalDate.now()
        return database.conversations().map { conversation ->
            val latest = database.messages(conversation.id, limit = 1).lastOrNull()?.second
            val date = Instant.ofEpochMilli(conversation.updatedAt).atZone(ZoneId.systemDefault())
            ConversationSummaryUi(
                id = conversation.id.value, title = conversation.title,
                preview = latest?.blocks?.filterIsInstance<MessageBlock.Text>()?.joinToString(" ") { it.text }?.take(160).orEmpty(),
                timeLabel = date.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)),
                updatedAtMillis = conversation.updatedAt, mode = ConversationModeUi.Chat,
                isPinned = conversation.pinned, isActiveRun = active?.conversationId == conversation.id,
                hasCompletionMarker = app.daiyu.android.storage.ConversationCompletionMarkers.read(
                    database.readableDatabase, conversation.id) != null,
                folderId = conversation.folder.takeIf { it.isNotEmpty() }, isToday = date.toLocalDate() == today,
            )
        }
    }

    companion object {
        // Compatibility readers below return the first page only. Production uses searchPage.
        const val SEARCH_RESULT_LIMIT = 200
    }

    fun search(query: String): List<MessageSearchHit> = searchPage(query).results

    fun searchCurrentConversation(query: String, conversationId: String): List<MessageSearchHit> {
        require(conversationId.isNotBlank())
        return searchPage(query, conversationId).results
    }

    /**
     * Result paging AFTER complete MessageHistory expansion (never LIMIT 200 physical owners).
     * Each page verifies every scoped conversation revision in the same SQLite snapshot. This
     * intentionally still reads whole conversations; it is not bounded database-history paging.
     */
    fun searchPage(
        query: String,
        conversationId: String? = null,
        cursor: MessageSearchCursor? = null,
        cancellationSignal: CancellationSignal = CancellationSignal(),
        pageSize: Int = SEARCH_RESULT_LIMIT,
    ): MessageSearchPage {
        val needle = query.trim()
        require(needle.length <= 1000 && pageSize in 1..SEARCH_RESULT_LIMIT)
        require(conversationId == null || conversationId.isNotBlank())
        cancellationSignal.throwIfCanceled()
        if (cursor != null && (cursor.query != needle || cursor.conversationId != conversationId))
            throw SearchCursorInvalidatedException()
        if (needle.isEmpty()) return MessageSearchPage(emptyList())
        return context.searchRead(cancellationSignal) { db ->
            val conversations = db.rawQuery(
                "SELECT id,title FROM conversations" + (if (conversationId == null) "" else " WHERE id=?") +
                    " ORDER BY pinned DESC,updated DESC,id ASC",
                conversationId?.let { arrayOf(it) }, cancellationSignal,
            ).use { rows -> buildList {
                while (rows.moveToNext()) {
                    cancellationSignal.throwIfCanceled()
                    add(rows.getString(0) to rows.getString(1))
                }
            } }
            if (cursor != null && conversations != cursor.conversations.map { it.id to it.title })
                throw SearchCursorInvalidatedException()
            check(conversationId == null || conversations.size == 1) { "会话已不存在" }
            val revisions = ArrayList<SearchConversationRevision>(conversations.size)
            val hits = ArrayList<MessageSearchHit>(pageSize)
            var nextConversation = 0
            var nextExpanded = 0
            var hasMore = false
            for ((conversationIndex, conversation) in conversations.withIndex()) {
                cancellationSignal.throwIfCanceled()
                val (id, title) = conversation
                val stored = context.displayHistoryForSearch(db, ConversationId(id), cancellationSignal)
                val revision = searchRevision(stored, cancellationSignal)
                if (cursor != null && cursor.conversations[conversationIndex].revision != revision)
                    throw SearchCursorInvalidatedException()
                revisions.add(SearchConversationRevision(id, title, revision))
                val owners = buildMap {
                    for (owner in stored) for (segment in owner.historySegments) {
                        cancellationSignal.throwIfCanceled()
                        put(owner.runId.value to segment.messageId.value, owner.id.value)
                    }
                }
                val occurrences = mutableMapOf<Pair<String, String>, Int>()
                for ((expandedIndex, message) in MessageHistory.expand(stored).withIndex()) {
                    cancellationSignal.throwIfCanceled()
                    val identity = message.runId.value to message.id.value
                    val segmentIndex = occurrences[identity] ?: 0
                    occurrences[identity] = segmentIndex + 1
                    // Count occurrences even before the cursor: same-owner segments across pages
                    // must retain the exact segmentIndex used by the chat navigation mapper.
                    if (cursor != null && (conversationIndex < cursor.nextConversation ||
                            conversationIndex == cursor.nextConversation && expandedIndex < cursor.nextExpanded)) continue
                    val text = retainedSearchText(message.blocks)
                    val at = text.indexOf(needle, ignoreCase = true)
                    if (at < 0) continue
                    if (hits.size == pageSize) { hasMore = true; continue }
                    hits.add(MessageSearchHit(id, title, message.id.value,
                        text.substring((at - 36).coerceAtLeast(0), (at + needle.length + 80).coerceAtMost(text.length)),
                        if (message.role == "user") "用户" else "助手",
                        owners[identity] ?: message.id.value, segmentIndex))
                    nextConversation = conversationIndex
                    nextExpanded = expandedIndex + 1
                }
            }
            cancellationSignal.throwIfCanceled()
            MessageSearchPage(hits.toList(), if (hasMore) MessageSearchCursor(
                needle, conversationId, revisions.toList(), nextConversation, nextExpanded,
            ) else null)
        }
    }

    private fun searchRevision(stored: List<Message>, signal: CancellationSignal): String {
        // ContextRevision includes blocks/protocol/segments/status, but deliberately omits time.
        // Frame each owner's fixed-size content digest and its timestamp metadata separately.
        val hash = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            hash.update(bytes)
        }
        for (message in stored) {
            signal.throwIfCanceled()
            field(ContextRevision.digest(listOf(message)))
            field("${message.createdAt}")
            field(message.historySegments.size.toString())
            for (segment in message.historySegments) {
                signal.throwIfCanceled()
                field("${segment.createdAt}")
            }
        }
        signal.throwIfCanceled()
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun stats(): UsageStatsSnapshot = DatabaseTransactions { database.readableDatabase }.run { db ->
        val conversations = db.rawQuery("SELECT COUNT(*) FROM conversations", null).use { it.moveToFirst(); it.getInt(0) }
        val messages = db.rawQuery("SELECT COUNT(*) FROM messages", null).use { it.moveToFirst(); it.getInt(0) }
        val usage = RecordedUsageReader.readAttributed(db)
        UsageStatsSnapshot(currentDataAvailable = true, currentConversations = conversations,
            currentMessages = messages, recordedUsage = usage.parentCoverage, attributedUsage = usage)
    }

    fun folders(): List<ConversationFolderUi> {
        val names = database.readableDatabase.rawQuery(
            "SELECT name FROM conversation_folders UNION SELECT folder FROM conversations WHERE folder<>'' ORDER BY 1", null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return names.mapIndexed { index, name -> ConversationFolderUi(name, name, index) }
    }

    fun createFolder(name: String) {
        require(name.isNotBlank() && name.length <= 200)
        database.writableDatabase.insertOrThrow("conversation_folders", null, ContentValues().apply { put("name", name) })
    }

    fun changeFolder(name: String, replacement: String?) {
        require(name.isNotEmpty() && (replacement == null || replacement.isNotBlank() && replacement.length <= 200))
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            if (replacement != null) {
                check(folders().none { it.name == replacement }) { "文件夹名称已存在" }
                db.insertOrThrow("conversation_folders", null, ContentValues().apply { put("name", replacement) })
            }
            db.update("conversations", ContentValues().apply { put("folder", replacement.orEmpty()) }, "folder=?", arrayOf(name))
            db.delete("conversation_folders", "name=?", arrayOf(name))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun deleteAll() {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            database.conversations().forEach { database.delete(it.id) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
