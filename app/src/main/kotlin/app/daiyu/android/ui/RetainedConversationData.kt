package app.daiyu.android.ui

import android.content.ContentValues
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
                folderId = conversation.folder.takeIf { it.isNotEmpty() }, isToday = date.toLocalDate() == today,
            )
        }
    }

    companion object {
        // This reader has no continuation token: never advertise pagination or unlimited results.
        const val SEARCH_RESULT_LIMIT = 200
    }

    fun search(query: String): List<MessageSearchHit> = searchHistory(query, null)

    /** Scope the SQLite read BEFORE matching/limiting, not a filter of 200 global hits. */
    fun searchCurrentConversation(query: String, conversationId: String): List<MessageSearchHit> {
        require(conversationId.isNotBlank())
        return searchHistory(query, conversationId)
    }

    private fun searchHistory(query: String, conversationId: String?): List<MessageSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        require(needle.length <= 1000)
        val conversations = database.readableDatabase.rawQuery(
            "SELECT id,title FROM conversations" + (if (conversationId == null) "" else " WHERE id=?") +
                " ORDER BY pinned DESC,updated DESC,id ASC",
            conversationId?.let { arrayOf(it) },
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(ConversationId(cursor.getString(0)) to cursor.getString(1))
        } }
        // A removed scope is a failed read, not an apparently successful search of some other scope.
        check(conversationId == null || conversations.size == 1) { "会话已不存在" }
        return buildList {
            for ((id, title) in conversations) {
                // displayHistory binds WHERE conversation_id=? and includes old/segmented history.
                val stored = context.displayHistory(id)
                val owners = stored.flatMap { owner -> owner.historySegments.map {
                    (owner.runId.value to it.messageId.value) to owner.id.value
                } }.toMap()
                val occurrences = mutableMapOf<Pair<String, String>, Int>()
                for (message in MessageHistory.expand(stored)) {
                    val identity = message.runId.value to message.id.value
                    val segmentIndex = occurrences[identity] ?: 0
                    occurrences[identity] = segmentIndex + 1
                    // Search explicit text/tool records, not serialized block IDs or protocol
                    // metadata. No image/attachment tables, asset paths or sidecar files are read.
                    val text = retainedSearchText(message.blocks)
                    val at = text.indexOf(needle, ignoreCase = true)
                    if (at >= 0) {
                        add(MessageSearchHit(id.value, title, message.id.value,
                            text.substring((at - 36).coerceAtLeast(0), (at + needle.length + 80).coerceAtMost(text.length)),
                            if (message.role == "user") "用户" else "助手",
                            owners[identity] ?: message.id.value, segmentIndex))
                    }
                    if (size >= SEARCH_RESULT_LIMIT) return@buildList
                }
            }
        }
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
