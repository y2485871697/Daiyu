package app.daiyu.android.model.usage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.usage.*
import app.daiyu.android.title.ParentTitleUsage

/** Explicitly typed requests in the EXISTING context_usage store. Legacy receipts are never
 * guessed from request prefixes. Admission is metadata, not a fabricated provider snapshot.
 * Auxiliary mutations run under SQLiteRuntimeStore's lease/monitor; title commits use their
 * existing caller-owned title transaction. Neither edits a terminal parent checkpoint. */
internal object SQLiteAdditionalUsageStore {
    fun install(db: SQLiteDatabase) {
        val columns = db.rawQuery("PRAGMA table_info(context_usage)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }
        check(columns.isNotEmpty())
        val additions = mapOf("usage_source" to "TEXT NOT NULL DEFAULT 'LEGACY'",
            "request_owner" to "TEXT", "attempt_id" to "TEXT", "parent_message_id" to "TEXT",
            "has_usage" to "INTEGER", "process_owner" to "TEXT", "usage_state" to "TEXT",
            "usage_revision" to "INTEGER", "operation_id" to "TEXT", "terminal_reason" to "TEXT")
        additions.forEach { (name, type) -> if (name !in columns) db.execSQL("ALTER TABLE context_usage ADD COLUMN $name $type") }
        // v17's same atomic helper install: legacy run-owned receipts retain every column/value,
        // but conversation operations must store SQL NULL, never a fabricated historical run.
        val requiresNullableRun = db.rawQuery("PRAGMA table_info(context_usage)", null).use { c ->
            var required = false
            while (c.moveToNext()) if (c.getString(1) == "run_id") required = c.getInt(3) != 0
            required
        }
        if (requiresNullableRun) {
            db.execSQL("CREATE TABLE context_usage_v17(request_id TEXT PRIMARY KEY,run_id TEXT,branch_id TEXT NOT NULL," +
                "input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,reasoning_tokens INTEGER," +
                "usage_source TEXT NOT NULL DEFAULT 'LEGACY',request_owner TEXT,attempt_id TEXT,parent_message_id TEXT," +
                "has_usage INTEGER,process_owner TEXT,usage_state TEXT,usage_revision INTEGER,operation_id TEXT,terminal_reason TEXT)")
            val names = "request_id,run_id,branch_id,input_tokens,output_tokens,cached_tokens,reasoning_tokens," +
                "usage_source,request_owner,attempt_id,parent_message_id,has_usage,process_owner,usage_state,usage_revision,operation_id,terminal_reason"
            db.execSQL("INSERT INTO context_usage_v17($names) SELECT $names FROM context_usage")
            db.execSQL("DROP TABLE context_usage")
            db.execSQL("ALTER TABLE context_usage_v17 RENAME TO context_usage")
        }
        db.execSQL("CREATE TRIGGER IF NOT EXISTS context_usage_delete_typed_parent AFTER DELETE ON messages BEGIN " +
            "DELETE FROM context_usage WHERE usage_source IN ('TITLE','AUXILIARY_VISION') AND parent_message_id=OLD.id; END")
        db.execSQL("CREATE INDEX IF NOT EXISTS context_usage_source_owner ON context_usage(usage_source,branch_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS context_usage_manual_operation ON context_usage(branch_id,operation_id) WHERE usage_source='MANUAL_SUMMARY'")
    }

    private fun parent(db: SQLiteDatabase, conversation: String, run: String, message: String? = null): String {
        val args = arrayOf(conversation, run) + if (message == null) emptyArray() else arrayOf(message)
        return db.rawQuery("SELECT id FROM messages WHERE conversation_id=? AND run_id=? AND role='assistant'" +
            if (message == null) "" else " AND id=?", args).use {
            check(it.moveToFirst()) { "additional_usage_parent_missing" }
            val id = it.getString(0); check(!it.moveToNext()) { "additional_usage_parent_ambiguous" }; id
        }
    }

    private fun attribution(db: SQLiteDatabase, conversation: String, run: String?, message: String?,
        source: UsageRequestSource, operation: String?): String? {
        require(source in setOf(UsageRequestSource.AUXILIARY_VISION, UsageRequestSource.TITLE, UsageRequestSource.MANUAL_SUMMARY))
        if (source != UsageRequestSource.MANUAL_SUMMARY) {
            require(operation == null)
            return parent(db, conversation, requireNotNull(run), message)
        }
        require(run == null && message == null && operation != null && operation.isNotBlank() && operation.length <= 200)
        check(db.rawQuery("SELECT id FROM conversations WHERE id=?", arrayOf(conversation)).use { it.moveToFirst() }) {
            "manual_summary_conversation_missing"
        }
        return null
    }

    fun admit(db: SQLiteDatabase, conversation: String, run: String?, message: String?,
        request: RequestCheckpoint, source: UsageRequestSource, process: String, operation: String? = null) {
        check(db.inTransaction())
        require(request.operation == RequestOperation.NEXT)
        val id = attribution(db, conversation, run, message, source, operation)
        // INSERT, not UPSERT: a persisted unknown attempt can never authorize a second POST.
        val inserted = db.insertOrThrow("context_usage", null, ContentValues().apply {
            put("request_id", request.requestId); put("run_id", run); put("branch_id", conversation)
            put("usage_source", source.name); put("parent_message_id", id); put("operation_id", operation)
            put("request_owner", request.owner); put("attempt_id", request.attemptId)
            put("process_owner", process); put("usage_state", "ADMITTED"); put("usage_revision", 1L); put("has_usage", 0)
        })
        check(inserted != -1L) { "additional_usage_admission_not_persisted" }
    }

    fun observe(db: SQLiteDatabase, conversation: String, run: String?, message: String?,
        request: RequestCheckpoint, source: UsageRequestSource, process: String, usage: ModelEvent.Usage?, terminal: Boolean,
        operation: String? = null, terminalReason: String? = null) {
        check(db.inTransaction())
        require(request.operation == RequestOperation.NEXT)
        val id = attribution(db, conversation, run, message, source, operation)
        if (source == UsageRequestSource.MANUAL_SUMMARY) require(if (terminal)
            terminalReason in setOf("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN") else terminalReason == null)
        db.rawQuery("SELECT branch_id,run_id,parent_message_id,request_owner,attempt_id,usage_source,process_owner," +
            "usage_state,usage_revision,has_usage,input_tokens,output_tokens,cached_tokens,operation_id,terminal_reason FROM context_usage WHERE request_id=?",
            arrayOf(request.requestId)).use { c ->
            check(c.moveToFirst() && c.getString(0) == conversation && c.getString(1) == run && c.getString(2) == id &&
                c.getString(3) == request.owner && c.getString(4) == request.attemptId && c.getString(5) == source.name &&
                c.getString(6) == process && c.getString(13) == operation) { "additional_usage_stale_identity" }
            check(c.getType(8) == Cursor.FIELD_TYPE_INTEGER && c.getType(9) == Cursor.FIELD_TYPE_INTEGER &&
                c.getLong(8) in 1L until Long.MAX_VALUE && c.getLong(9) in 0L..1L) { "additional_usage_corrupt_revision_or_presence" }
            val revision = c.getLong(8)
            val prior = if (c.getLong(9) == 0L) null else ModelEvent.Usage(token(c, 10), token(c, 11), token(c, 12))
            if (c.getString(7) == "TERMINAL") {
                check(terminal && prior == usage && c.getString(14) == terminalReason) { "additional_usage_terminal" }; return
            }
            check(c.getString(7) == "ADMITTED")
            if (!terminal && prior == usage) return
            check(db.update("context_usage", ContentValues().apply {
                put("usage_revision", revision + 1); put("has_usage", if (usage == null) 0 else 1)
                put("input_tokens", usage?.inputTokens); put("output_tokens", usage?.outputTokens); put("cached_tokens", usage?.cachedTokens)
                if (terminal) { put("usage_state", "TERMINAL"); put("terminal_reason", terminalReason) }
            }, "request_id=? AND request_owner=? AND attempt_id=? AND usage_revision=? AND usage_state='ADMITTED'",
                arrayOf(request.requestId, request.owner, request.attemptId, revision.toString())) == 1)
        }
    }

    fun beginTitle(db: SQLiteDatabase, receipt: ParentTitleUsage) = admit(db, receipt.conversationId.value,
        receipt.parentRunId.value, null, receipt.request, UsageRequestSource.TITLE, receipt.request.owner)

    fun observeTitle(db: SQLiteDatabase, receipt: ParentTitleUsage) = observe(db, receipt.conversationId.value,
        receipt.parentRunId.value, null, receipt.request, UsageRequestSource.TITLE, receipt.request.owner, receipt.usage, false)

    fun finishTitle(db: SQLiteDatabase, receipt: ParentTitleUsage) = observe(db, receipt.conversationId.value,
        receipt.parentRunId.value, null, receipt.request, UsageRequestSource.TITLE, receipt.request.owner, receipt.usage, true)

    internal val manualProcessOwner = java.util.UUID.randomUUID().toString()

    fun recoverManual(db: SQLiteDatabase) {
        check(db.inTransaction())
        // A new process closes unknown admissions; it never obtains permission to reopen them.
        db.execSQL("UPDATE context_usage SET usage_state='TERMINAL',terminal_reason='UNKNOWN'," +
            "usage_revision=usage_revision+1,request_owner=lower(hex(randomblob(16))) " +
            "WHERE usage_source='MANUAL_SUMMARY' AND usage_state='ADMITTED' AND process_owner!=?",
            arrayOf(manualProcessOwner))
    }

    fun recover(db: SQLiteDatabase, process: String) {
        recoverManual(db)
        db.execSQL("UPDATE context_usage SET usage_state='TERMINAL',usage_revision=usage_revision+1," +
            "request_owner=lower(hex(randomblob(16))) WHERE usage_source='AUXILIARY_VISION' AND usage_state='ADMITTED' AND process_owner!=?", arrayOf(process))
    }

    data class Read(val requests: List<AttributedRequestUsage>, val total: Int, val inspected: Int, val missing: Int, val unreadable: Int)
    fun read(db: SQLiteDatabase, conversation: ConversationId?, source: UsageRequestSource, limit: Int): Read {
        check(db.inTransaction()); require(limit >= 0)
        val args = arrayOf(source.name) + (conversation?.let { arrayOf(it.value) } ?: emptyArray())
        val from = " FROM context_usage u JOIN conversations c ON c.id=u.branch_id WHERE u.usage_source=?" +
            if (conversation == null) "" else " AND u.branch_id=?"
        val total = db.rawQuery("SELECT COUNT(*)$from", args).use { it.moveToFirst(); it.getInt(0) }
        val records = mutableListOf<AttributedRequestUsage>()
        var inspected = 0; var missing = 0; var unreadable = 0
        db.rawQuery("SELECT u.request_id,u.branch_id,u.run_id,u.parent_message_id,u.request_owner,u.attempt_id," +
            "u.has_usage,u.input_tokens,u.output_tokens,u.cached_tokens,u.usage_state,u.usage_revision,u.operation_id,u.terminal_reason,u.process_owner$from ORDER BY u.rowid DESC LIMIT ?",
            args + limit.toString()).use { c -> while (c.moveToNext()) {
            inspected++
            try {
                val manual = source == UsageRequestSource.MANUAL_SUMMARY
                val requiredText = if (manual) listOf(0, 1, 4, 5, 12, 14) else listOf(0, 1, 2, 3, 4, 5, 14)
                require(requiredText.all { !c.isNull(it) && c.getType(it) == Cursor.FIELD_TYPE_STRING &&
                    c.getString(it).isNotBlank() && c.getString(it).length <= 200 })
                require(c.getType(6) == Cursor.FIELD_TYPE_INTEGER && c.getType(10) == Cursor.FIELD_TYPE_STRING &&
                    c.getType(11) == Cursor.FIELD_TYPE_INTEGER)
                require(c.getString(10) in setOf("ADMITTED", "TERMINAL") && c.getLong(11) in 1L until Long.MAX_VALUE && c.getLong(6) in 0L..1L)
                val message = if (manual) {
                    require(c.isNull(2) && c.isNull(3))
                    require(if (c.getString(10) == "ADMITTED") c.isNull(13) else
                        c.getType(13) == Cursor.FIELD_TYPE_STRING && c.getString(13) in setOf("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN"))
                    null
                } else { require(c.isNull(12)); parent(db, c.getString(1), c.getString(2), c.getString(3)) }
                val request = RequestCheckpoint(c.getString(0), c.getString(5), c.getString(4), RequestOperation.NEXT)
                if (c.getLong(6) == 0L) {
                    require((7..9).all { c.isNull(it) }); missing++
                } else records.add(AttributedRequestUsage(c.getString(1), if (manual) null else c.getString(2), message,
                    if (manual) null else c.getString(2), message,
                    if (manual) UsageOrigin.ConversationPreparation(c.getString(1), c.getString(12)) else UsageOrigin.Main,
                    request, ModelEvent.Usage(token(c, 7), token(c, 8), token(c, 9)), source))
            } catch (failure: Exception) {
                if (failure is android.database.sqlite.SQLiteException ||
                    failure is java.util.concurrent.CancellationException) throw failure
                if (failure !is IllegalArgumentException && failure !is IllegalStateException) throw failure
                unreadable++
            }
        } }
        return Read(records, total, inspected, missing, unreadable)
    }
    private fun token(c: Cursor, i: Int): Long? {
        if (c.isNull(i)) return null
        require(c.getType(i) == Cursor.FIELD_TYPE_INTEGER); return c.getLong(i)
    }
}

/** One actual auxiliary request; complete/close is terminal, never permission to retry. */
internal interface AuxiliaryUsageAttempt : AutoCloseable {
    fun observe(usage: ModelEvent.Usage)
}
