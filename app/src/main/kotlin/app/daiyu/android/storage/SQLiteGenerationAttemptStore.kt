package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.model.generation.*
import org.json.JSONArray
import java.security.MessageDigest

/**
 * A binding to a REAL persisted assistant tool call, not a caller-selected operation token.
 * Reconstruct this from the same owner/call after restart. Do not generate a new call ID to retry.
 * The application DB is the account scope; a shared multi-account DB needs an account owner column
 * and validation before it can use this implementation.
 */
class GenerationAttemptBinding(
    val conversationId: String,
    val assistantMessageId: String,
    val runId: String,
    val toolCallId: String,
    val toolName: String,
) {
    init {
        listOf(conversationId, assistantMessageId, runId, toolCallId, toolName).forEach {
            require(it.isNotBlank() && it.length <= 200)
        }
    }

    /** Versioned, length-delimited hash: stable across clients, helper instances and restarts. */
    val operationId: String = run {
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update("generation-operation-v1".toByteArray(Charsets.UTF_8))
        listOf(conversationId, assistantMessageId, runId, toolCallId).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            hash.update(bytes)
        }
        "gen_" + hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun identity(itemId: String) = GenerationIdentity(operationId, itemId).also { it.validate() }
    override fun toString() = "GenerationAttemptBinding(<redacted>)"
}

/**
 * Uses ONLY the injected application database, whose lifetime, private path, encryption/backup
 * policy and schema version belong to the host. [installSchema] must run in its migration.
 * No lazy database, network I/O, asset publication, logging, reset or automatic retry exists here.
 *
 * Tombstones deliberately have no cascading FK: deleting/recreating a message must not reopen
 * its attempt. The host may purge private outcomes under a separate retention policy, but must
 * retain the identity/fingerprint tombstones while that account scope can reuse an identity.
 */
class SQLiteGenerationAttemptStore(
    private val database: () -> SQLiteDatabase,
    private val binding: GenerationAttemptBinding,
) : GenerationAttemptStore {
    override fun reserve(attempt: GenerationAttempt): GenerationReservation {
        validateAttempt(attempt)
        return transaction { db ->
            val previous = read(db, attempt.identity)
            validateOwner(db, newAttempt = previous == null)
            if (previous != null) {
                verifyBinding(previous)
                // Preserve the original fingerprint so GenerationIo can report IDENTITY_CONFLICT.
                GenerationReservation.Existing(previous.fingerprint, previous.submission)
            } else {
                val values = ContentValues().apply {
                    put("operation_id", attempt.identity.operationId); put("item_id", attempt.identity.itemId)
                    put("conversation_id", binding.conversationId); put("assistant_id", binding.assistantMessageId)
                    put("run_id", binding.runId); put("tool_call_id", binding.toolCallId); put("tool_name", binding.toolName)
                    put("fingerprint", attempt.fingerprint); put("protocol", attempt.protocol.name)
                }
                check(db.insertOrThrow(TABLE, null, values) != -1L)
                // Also catch RAISE(IGNORE) / mutating triggers. Never grant permission without a row.
                val inserted = checkNotNull(read(db, attempt.identity))
                verifyBinding(inserted)
                check(inserted.fingerprint == attempt.fingerprint && inserted.protocol == attempt.protocol && inserted.submission == null)
                GenerationReservation.Started
            }
        }
    }

    override fun finish(attempt: GenerationAttempt, submission: Submission) {
        validateAttempt(attempt)
        // Snapshot mutable output lists and reject excessive/private payloads before touching SQLite.
        val incoming = GenerationAttemptCodec.decode(GenerationAttemptCodec.encode(submission))
        GenerationAttemptCodec.validateHandle(attempt, incoming)
        transaction { db ->
            val prior = checkNotNull(read(db, attempt.identity))
            verifyBinding(prior)
            check(prior.fingerprint == attempt.fingerprint && prior.protocol == attempt.protocol)
            validateOwner(db, newAttempt = false)
            val merged = GenerationAttemptCodec.merge(prior.submission, incoming)
            val bytes = GenerationAttemptCodec.encode(merged)
            check(db.update(TABLE, ContentValues().apply { put("outcome", bytes) },
                "operation_id=? AND item_id=?", key(attempt.identity)) == 1)
            // Confirm the write even if a host trigger silently ignores/replaces it.
            check(readOutcome(db, attempt.identity)?.contentEquals(bytes) == true)
        }
    }

    private fun validateAttempt(attempt: GenerationAttempt) {
        attempt.identity.validate()
        require(attempt.identity.operationId == binding.operationId)
        require(attempt.fingerprint.isNotBlank() && attempt.fingerprint.length <= 512 &&
            attempt.fingerprint.toByteArray(Charsets.UTF_8).size <= 512)
    }

    private fun validateOwner(db: SQLiteDatabase, newAttempt: Boolean) {
        val status = db.rawQuery("SELECT m.status FROM messages m JOIN conversations c ON c.id=m.conversation_id " +
            "WHERE m.id=? AND m.conversation_id=? AND m.run_id=? AND m.role='assistant'",
            arrayOf(binding.assistantMessageId, binding.conversationId, binding.runId)).use {
            check(it.moveToFirst()); it.getString(0)
        }
        if (newAttempt) check(status == "RUNNING")
        // History is authoritative when present; do not accept a stale legacy projection.
        val history = MessageHistoryCodec.decode(JSONArray(checkNotNull(SQLiteText.read(db,
            "messages", "history_segments", "id", binding.assistantMessageId))))
        val calls = if (history.isNotEmpty()) {
            history.filter { it.role == "assistant" }.flatMap {
                check(it.messageId.value == binding.assistantMessageId)
                it.blocks.filterIsInstance<MessageBlock.Tool>()
            }.filter { it.invocation.id.value == binding.toolCallId }.map { it.invocation.name to it.result }
        } else {
            val blocks = JSONArray(checkNotNull(SQLiteText.read(db, "messages", "blocks", "id", binding.assistantMessageId)))
            (0 until blocks.length()).map { blocks.getJSONObject(it) }
                .filter { it.getString("type") == "tool" && it.getString("id") == binding.toolCallId }
                .map { it.getString("name") to if (it.isNull("result")) null else it.getString("result") }
        }
        check(calls.size == 1 && calls.single().first == binding.toolName)
        if (newAttempt) check(calls.single().second == null)
    }

    private data class Row(
        val conversation: String, val assistant: String, val run: String, val call: String, val tool: String,
        val fingerprint: String, val protocol: GenerationProtocol, val submission: Submission?,
    )

    private fun verifyBinding(row: Row) {
        check(row.conversation == binding.conversationId && row.assistant == binding.assistantMessageId &&
            row.run == binding.runId && row.call == binding.toolCallId && row.tool == binding.toolName)
    }

    private fun read(db: SQLiteDatabase, identity: GenerationIdentity): Row? {
        val row = db.rawQuery("SELECT conversation_id,assistant_id,run_id,tool_call_id,tool_name,fingerprint,protocol " +
            "FROM $TABLE WHERE operation_id=? AND item_id=?", key(identity)).use {
            if (!it.moveToFirst()) null else Row(it.getString(0), it.getString(1), it.getString(2), it.getString(3),
                it.getString(4), it.getString(5), GenerationProtocol.valueOf(it.getString(6)), null)
        } ?: return null
        val outcome = readOutcome(db, identity)?.let(GenerationAttemptCodec::decode)
        if (outcome != null) GenerationAttemptCodec.validateHandle(GenerationAttempt(identity, row.fingerprint, row.protocol), outcome)
        return row.copy(submission = outcome)
    }

    /** Never put a large BLOB into a CursorWindow: both metadata and each slice are bounded. */
    private fun readOutcome(db: SQLiteDatabase, identity: GenerationIdentity): ByteArray? {
        val length = db.rawQuery("SELECT length(outcome) FROM $TABLE WHERE operation_id=? AND item_id=?", key(identity)).use {
            check(it.moveToFirst()); if (it.isNull(0)) null else it.getLong(0)
        } ?: return null
        check(length in 1..GenerationAttemptCodec.MAX_ENCODED_BYTES.toLong())
        val out = java.io.ByteArrayOutputStream(length.toInt())
        var start = 1
        while (out.size() < length) {
            val part = db.rawQuery("SELECT substr(outcome,?,65536) FROM $TABLE WHERE operation_id=? AND item_id=?",
                arrayOf(start.toString(), identity.operationId, identity.itemId)).use { check(it.moveToFirst()); it.getBlob(0) }
            check(part.isNotEmpty() && out.size().toLong() + part.size <= length)
            out.write(part); start += part.size
        }
        return out.toByteArray()
    }

    private fun key(identity: GenerationIdentity) = arrayOf(identity.operationId, identity.itemId)

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        try {
            val db = database()
            check(db.isOpen && !db.isReadOnly && java.io.File(db.path).isAbsolute)
            // SQLite nested success is NOT a durable reservation; fail before even reading an owner.
            check(!db.inTransaction())
            check(db.rawQuery("PRAGMA synchronous", null).use { it.moveToFirst() && it.getInt(0) >= 2 })
            check(db.rawQuery("PRAGMA journal_mode", null).use {
                it.moveToFirst() && it.getString(0).lowercase() !in setOf("off", "memory")
            })
            db.beginTransaction()
            val result: T
            try {
                result = block(db)
                db.setTransactionSuccessful()
            } finally {
                // May throw on commit: Started is not returned until this succeeds.
                db.endTransaction()
            }
            return result
        } catch (_: Exception) {
            // No SQL exception cause or private outcome/URL reaches logs through this boundary.
            throw IllegalStateException("generation_store_unavailable")
        }
    }

    companion object {
        const val TABLE = "generation_attempts"

        /** Additive helper only. Parent owns onCreate/onUpgrade and the public DB version. */
        fun installSchema(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS generation_attempts (
                operation_id TEXT NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),
                item_id TEXT NOT NULL CHECK(length(item_id) BETWEEN 1 AND 128),
                conversation_id TEXT NOT NULL, assistant_id TEXT NOT NULL, run_id TEXT NOT NULL,
                tool_call_id TEXT NOT NULL, tool_name TEXT NOT NULL,
                fingerprint TEXT NOT NULL CHECK(length(CAST(fingerprint AS BLOB)) BETWEEN 1 AND 512), protocol TEXT NOT NULL,
                outcome BLOB CHECK(outcome IS NULL OR (typeof(outcome)='blob' AND length(outcome) BETWEEN 1 AND ${GenerationAttemptCodec.MAX_ENCODED_BYTES})),
                PRIMARY KEY(operation_id,item_id),
                UNIQUE(conversation_id,assistant_id,run_id,tool_call_id,item_id)
            )""".trimIndent())
        }
    }
}
