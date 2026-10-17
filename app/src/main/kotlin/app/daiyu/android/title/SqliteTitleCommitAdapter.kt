package app.daiyu.android.title

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.RunId

/** A revision witness, not a second conversation/usage ledger. Delete/recreate and even an
 * identical manual rename change the revision. Capture only AFTER the schema owner installs it. */
data class TitleRevision internal constructor(
    val conversationId: ConversationId,
    val originalTitle: String,
    val revision: String,
    val titleTouched: Boolean,
    val firstMessageId: String,
    val firstRunId: String,
    val firstMessageRevision: String,
)

data class ParentTitleUsage(
    val requestId: String,
    val conversationId: ConversationId,
    val parentRunId: RunId,
    /** Last provider snapshot replaces earlier snapshots. null means no report, not zero. */
    val usage: ModelEvent.Usage?,
    val request: app.daiyu.android.domain.runtime.RequestCheckpoint = app.daiyu.android.domain.runtime.RequestCheckpoint(
        requestId, requestId, requestId, app.daiyu.android.domain.runtime.RequestOperation.NEXT),
) {
    init { require(request.requestId == requestId && request.operation == app.daiyu.android.domain.runtime.RequestOperation.NEXT) }
}

/** Required parent-owned port. Persist into the EXISTING usage store using THIS transaction.
 * Do not call a different DB/helper, dispatch an asynchronous write, or create a title ledger.
 * Throw on failure; both title and usage then roll back. Also used for actual failed/stale
 * requests in a usage-only transaction (legacy method name retained). No network/UI work. */
fun interface ParentTitleUsagePort {
    fun recordAfterTitleSaved(db: SQLiteDatabase, receipt: ParentTitleUsage)
    /** Durable admission before the only actual next(); compatibility ports may opt out. */
    fun beginRequest(db: SQLiteDatabase, receipt: ParentTitleUsage) {}
    fun observeRequest(db: SQLiteDatabase, receipt: ParentTitleUsage) {}
}

class TitleUsagePersistenceException(cause: Exception) : IllegalStateException("标题用量持久化未确认", cause)

class SqliteTitleCommitAdapter(
    private val database: () -> SQLiteDatabase,
    private val usage: ParentTitleUsagePort,
) {
    /** Call on worker after the parent's accepted-send transaction returns, never inside it. */
    fun capture(conversationId: ConversationId): TitleRevision? = transaction { db -> read(db, conversationId) }

    fun isCurrent(expected: TitleRevision): Boolean = transaction { db -> mayApply(read(db, expected.conversationId), expected) }

    /** Read the committed first user question, not a potentially edited/current UI draft. */
    fun firstQuestion(expected: TitleRevision): String? = transaction { db ->
        if (!mayApply(read(db, expected.conversationId), expected)) return@transaction null
        val raw = requireNotNull(app.daiyu.android.storage.SQLiteText.read(db, "messages", "blocks", "id", expected.firstMessageId))
        app.daiyu.android.context.ArchiveCodec.decodeBlocks(org.json.JSONArray(raw))
            .filterIsInstance<app.daiyu.android.domain.MessageBlock.Text>().joinToString("\n") { it.text }
    }

    private fun mayApply(current: TitleRevision?, expected: TitleRevision): Boolean = ConversationTitleModel.mayApply(
        exists = current != null,
        renamed = current?.titleTouched != false || current?.revision != expected.revision,
        currentTitle = current?.originalTitle,
        expected = expected.originalTitle,
        currentFirstMessage = current?.firstMessageRevision,
        expectedFirstMessage = expected.firstMessageRevision,
    )

    /** A CAS failure has no title or usage effect. Successful return means endTransaction succeeded. */
    fun commit(expected: TitleRevision, title: String, receipt: ParentTitleUsage): Boolean {
        require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS)
        require(receipt.conversationId == expected.conversationId && receipt.requestId.isNotBlank() && receipt.parentRunId.value.isNotBlank())
        return transaction { db ->
            if (!mayApply(read(db, expected.conversationId), expected) || expected.firstRunId != receipt.parentRunId.value)
                return@transaction false
            // Prove the receipt belongs to a successfully saved parent run, not global 'active'.
            val completed = db.rawQuery("SELECT 1 FROM messages WHERE conversation_id=? AND run_id=? AND role='assistant' AND status='COMPLETED' LIMIT 1",
                arrayOf(expected.conversationId.value, receipt.parentRunId.value)).use { it.moveToFirst() }
            if (!completed) return@transaction false
            val count = db.update("conversations", ContentValues().apply { put("title", title) },
                "id=? AND title=? COLLATE BINARY AND EXISTS (SELECT 1 FROM conversation_title_revisions r WHERE r.conversation_id=conversations.id AND r.revision=? AND r.title_touched=0)",
                arrayOf(expected.conversationId.value, expected.originalTitle, expected.revision))
            if (count != 1) return@transaction false
            val saved = read(db, expected.conversationId)
            check(saved != null && saved.originalTitle == title && saved.revision != expected.revision) {
                "标题CAS写入或revision触发器未确认"
            }
            recordUsage(db, receipt)
            check(read(db, expected.conversationId) == saved) { "用量端口改变了标题所有者" }
            true
        }
    }

    /** A real failed/revoked/stale request still consumed provider resources. Use the SAME
     * parent ledger, preserving null when no usage was reported; never fabricate zero. */
    fun recordRequestUsage(receipt: ParentTitleUsage) = transaction { db -> recordUsage(db, receipt) }

    fun beginRequestUsage(receipt: ParentTitleUsage) = transaction { db ->
        try { usage.beginRequest(db, receipt) }
        catch (failure: Exception) { throw TitleUsagePersistenceException(failure) }
    }

    fun observeRequestUsage(receipt: ParentTitleUsage) = transaction { db ->
        try { usage.observeRequest(db, receipt) }
        catch (failure: Exception) { throw TitleUsagePersistenceException(failure) }
    }

    private fun recordUsage(db: SQLiteDatabase, receipt: ParentTitleUsage) {
        try { usage.recordAfterTitleSaved(db, receipt) }
        catch (failure: Exception) { throw TitleUsagePersistenceException(failure) }
    }

    private fun read(db: SQLiteDatabase, owner: ConversationId): TitleRevision? {
        val title = db.rawQuery(
            "SELECT c.title,r.revision,r.title_touched FROM conversations c JOIN conversation_title_revisions r ON r.conversation_id=c.id WHERE c.id=?",
            arrayOf(owner.value)).use { c -> if (!c.moveToFirst()) null else Triple(c.getString(0), c.getString(1), c.getInt(2) != 0) }
            ?: return null
        val first = db.rawQuery("SELECT id,run_id,role FROM messages WHERE conversation_id=? ORDER BY seq LIMIT 1",
            arrayOf(owner.value)).use { c -> if (!c.moveToFirst()) null else listOf(c.getString(0), c.getString(1), c.getString(2)) }
            ?: return null
        if (first[2] != "user") return null
        // Preserve the old first-message equality guard, strengthened to identity + exact body
        // revision. Chunked SQLiteText avoids CursorWindow truncation of the saved raw body.
        val fields = first + listOf("blocks", "history_segments").map { column ->
            requireNotNull(app.daiyu.android.storage.SQLiteText.read(db, "messages", column, "id", first[0]))
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fields.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        val witness = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return TitleRevision(owner, title.first, title.second, title.third, first[0], first[1], witness)
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = database()
        check(!db.inTransaction()) { "标题接缝只能在父事务提交后调用" }
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    companion object {
        const val MAX_TITLE_CHARS = 24

        /** Schema-owner hook: call inside the SAME conversations.db migration/bootstrap transaction.
         * This helper does not open another DB, bump user_version, or edit ConversationDatabase.
         * Backfill precedes all title jobs. No runtime auto-install/fallback if the hook is missing. */
        fun installSchema(db: SQLiteDatabase) {
            check(db.inTransaction()) { "标题revision必须由schema所有者在事务中安装" }
            db.execSQL("CREATE TABLE IF NOT EXISTS conversation_title_revisions(conversation_id TEXT PRIMARY KEY NOT NULL,revision TEXT NOT NULL CHECK(length(revision)=32),title_touched INTEGER NOT NULL DEFAULT 0 CHECK(title_touched IN (0,1)),FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE)")
            db.execSQL("INSERT OR IGNORE INTO conversation_title_revisions(conversation_id,revision) SELECT id,lower(hex(randomblob(16))) FROM conversations")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS conversation_title_insert AFTER INSERT ON conversations BEGIN INSERT INTO conversation_title_revisions(conversation_id,revision) VALUES(NEW.id,lower(hex(randomblob(16)))); END")
            // UPDATE OF intentionally fires when OLD.title == NEW.title: manual rename still wins.
            db.execSQL("CREATE TRIGGER IF NOT EXISTS conversation_title_update AFTER UPDATE OF title ON conversations BEGIN UPDATE conversation_title_revisions SET revision=lower(hex(randomblob(16))),title_touched=1 WHERE conversation_id=NEW.id; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS conversation_title_delete AFTER DELETE ON conversations BEGIN DELETE FROM conversation_title_revisions WHERE conversation_id=OLD.id; END")
        }
    }
}
