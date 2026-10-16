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

    fun search(query: String): List<MessageSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        require(needle.length <= 1000)
        return buildList {
            for (conversation in database.conversations()) {
                val stored = context.displayHistory(conversation.id)
                val owners = stored.flatMap { owner -> owner.historySegments.map { it.messageId.value to owner.id.value } }.toMap()
                val history = MessageHistory.expand(stored)
                for (message in history) {
                    val text = message.blocks.joinToString("\n") { block -> when (block) {
                        is MessageBlock.Text -> block.text
                        is MessageBlock.Tool -> listOfNotNull(block.invocation.name, block.invocation.arguments, block.result).joinToString(" ")
                    } }
                    val at = text.indexOf(needle, ignoreCase = true)
                    if (at >= 0) add(MessageSearchHit(conversation.id.value, conversation.title, message.id.value,
                        text.substring((at - 36).coerceAtLeast(0), (at + needle.length + 80).coerceAtMost(text.length)),
                        if (message.role == "user") "用户" else "助手", owners[message.id.value] ?: message.id.value))
                    if (size >= 200) return@buildList
                }
            }
        }.distinctBy { it.conversationId to it.messageId }
    }

    fun stats(): UsageStatsSnapshot = DatabaseTransactions { database.readableDatabase }.run { db ->
        val conversations = db.rawQuery("SELECT COUNT(*) FROM conversations", null).use { it.moveToFirst(); it.getInt(0) }
        val messages = db.rawQuery("SELECT COUNT(*) FROM messages", null).use { it.moveToFirst(); it.getInt(0) }
        UsageStatsSnapshot(currentDataAvailable = true, currentConversations = conversations,
            currentMessages = messages, recordedUsage = RecordedUsageReader.read(db))
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
