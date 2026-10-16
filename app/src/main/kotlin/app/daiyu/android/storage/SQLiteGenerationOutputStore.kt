package app.daiyu.android.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.media.MessageGenerationAssets
import app.daiyu.android.media.PendingGenerationAsset
import app.daiyu.android.model.generation.GenerationAssetReceipt
import app.daiyu.android.model.generation.GenerationAttempt
import app.daiyu.android.model.generation.GenerationIdentity
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationProtocol
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream

/** A side-table reference, NOT a UUID MessageBlock.Image or a Tool.result update. */
data class GenerationOutputReference internal constructor(
    val identity: GenerationIdentity,
    val outputIndex: Int,
    val fingerprint: String,
    val protocol: GenerationProtocol,
    val accountScope: String,
    val conversationId: String,
    val assistantMessageId: String,
    val runId: String,
    val toolCallId: String,
    val toolName: String,
    val receipt: GenerationAssetReceipt,
    val kind: GenerationKind,
    val mimeType: String,
    val bytes: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
) {
    override fun toString() = "GenerationOutputReference(kind=$kind, payload=<redacted>)"
}

/** Exact-slot recovery never authorizes a new POST/download, including when the slot is absent. */
sealed interface GenerationOutputRecovery {
    data class Registered(val reference: GenerationOutputReference) : GenerationOutputRecovery
    data object Missing : GenerationOutputRecovery
}

/**
 * Additive P19/P20 leaf module. The supplied DB MUST be the account's application DB containing
 * the real messages and generation_attempts; accountScope must be stable across restarts.
 * No helper/version ownership, network, attempt mutation, asset deletion or Tool.result writes.
 *
 * Asset verification releases ALL file/fence locks before final registration admission.
 * Registration reacquires ONLY the asset fence, then opens the single top-level SQL transaction.
 * The gate stays held through COMMIT, ordering registration against same-fence revoke; file IO
 * stays outside SQLite. Authorization is rechecked in SQL, including on an idempotent retry.
 * Calls from an outer transaction are rejected BEFORE touching assets (lock ordering matters).
 * find authorizes metadata only; open also verifies the private original through the asset fence.
 */
class SQLiteGenerationOutputStore(
    private val database: () -> SQLiteDatabase,
    private val owner: GenerationAssetOwner,
    private val assets: MessageGenerationAssets,
) {
    private val binding get() = owner.binding

    fun register(attempt: GenerationAttempt, receipt: GenerationAssetReceipt): GenerationOutputReference = guarded {
        validateAttempt(attempt)
        val db = readyDatabase()
        val verified = assets.pending(receipt) // Outside SQLite; real record + original validation.
        registerVerified(db, attempt, verified)
    }

    /** A Pending is not proof: re-resolve it and compare EVERY field, including scalar owner fields. */
    fun register(attempt: GenerationAttempt, pending: PendingGenerationAsset): GenerationOutputReference = guarded {
        validateAttempt(attempt)
        val db = readyDatabase()
        val verified = assets.pending(pending.receipt)
        check(reference(attempt, pending) == reference(attempt, verified))
        registerVerified(db, attempt, verified)
    }

    /**
     * Recover only the original owner/operation/item/index slot. No receipt retention is required.
     * Verifies mapping + original outside SQLite, then uses the identical registration path.
     * Missing means no published slot AND no registered row (not permission to regenerate).
     * The original fingerprint/protocol must still match the persisted attempt even on Missing.
     */
    fun recoverPublished(originalAttempt: GenerationAttempt, outputIndex: Int): GenerationOutputRecovery = guarded {
        validateAttempt(originalAttempt); require(outputIndex in 0..31)
        val db = readyDatabase()
        val verified = assets.pending(originalAttempt.identity, outputIndex)
        if (verified == null) {
            assets.admitRegistration(owner) {
                transaction(db) {
                    authorize(it, originalAttempt)
                    val previous = read(it, originalAttempt.identity, outputIndex)
                    previous?.also { row -> validateReference(row, originalAttempt) }
                    // An existing reference with no publication is corruption, not a missing output.
                    check(previous == null)
                    GenerationOutputRecovery.Missing
                }
            }
        } else {
            GenerationOutputRecovery.Registered(registerVerified(db, originalAttempt, verified))
        }
    }

    /** Null means an authorized, valid attempt has no registered output at this index. */
    fun find(attempt: GenerationAttempt, outputIndex: Int): GenerationOutputReference? = guarded {
        validateAttempt(attempt); require(outputIndex in 0..31)
        transaction(readyDatabase()) { db ->
            authorize(db, attempt)
            read(db, attempt.identity, outputIndex)?.also { validateReference(it, attempt) }
        }
    }

    /**
     * Caller closes the verified, rewound ORIGINAL descriptor. Authorization linearizes at find's
     * transaction; the asset fence separately checks live admission. Neither can recall an already
     * returned descriptor. No SQL transaction is held while acquiring file/fence locks.
     */
    fun open(attempt: GenerationAttempt, outputIndex: Int): FileInputStream? = guarded {
        val registered = find(attempt, outputIndex) ?: return@guarded null
        check(reference(attempt, assets.pending(registered.receipt)) == registered)
        assets.openOriginal(registered.receipt)
    }

    private fun registerVerified(db: SQLiteDatabase, attempt: GenerationAttempt,
        pending: PendingGenerationAsset): GenerationOutputReference {
        val expected = reference(attempt, pending)
        return assets.admitRegistration(owner) {
            transaction(db) {
                authorize(it, attempt)
                val previous = read(it, attempt.identity, pending.outputIndex)
                if (previous == null) {
                    check(it.insertOrThrow(TABLE, null, values(expected)) != -1L)
                } else {
                    check(previous == expected) // No replace/update, even for a different valid receipt.
                }
                // Catch RAISE(IGNORE), mutating triggers and changes to the owner/attempt by a trigger.
                check(read(it, attempt.identity, pending.outputIndex) == expected)
                authorize(it, attempt)
                expected
            } // Including endTransaction: never release the gate before commit completes.
        }
    }

    private fun validateAttempt(attempt: GenerationAttempt) {
        attempt.identity.validate()
        require(attempt.identity.operationId == binding.operationId)
        require(attempt.fingerprint.isNotBlank() && attempt.fingerprint.length <= 512 &&
            attempt.fingerprint.toByteArray(Charsets.UTF_8).size <= 512)
    }

    private fun reference(attempt: GenerationAttempt, pending: PendingGenerationAsset): GenerationOutputReference {
        val b = pending.owner.binding
        check(pending.owner.accountScope == owner.accountScope && b.conversationId == binding.conversationId &&
            b.assistantMessageId == binding.assistantMessageId && b.runId == binding.runId &&
            b.toolCallId == binding.toolCallId && b.toolName == binding.toolName)
        check(pending.identity == attempt.identity && pending.outputIndex in 0..31)
        return GenerationOutputReference(pending.identity, pending.outputIndex, attempt.fingerprint, attempt.protocol,
            pending.owner.accountScope, b.conversationId, b.assistantMessageId, b.runId, b.toolCallId, b.toolName,
            pending.receipt, pending.kind, pending.mimeType, pending.bytes, pending.sha256,
            pending.width, pending.height, pending.durationMs).also { validateReference(it, attempt) }
    }

    private fun validateReference(row: GenerationOutputReference, attempt: GenerationAttempt) {
        check(row.identity == attempt.identity && row.fingerprint == attempt.fingerprint && row.protocol == attempt.protocol)
        check(row.accountScope == owner.accountScope && row.conversationId == binding.conversationId &&
            row.assistantMessageId == binding.assistantMessageId && row.runId == binding.runId &&
            row.toolCallId == binding.toolCallId && row.toolName == binding.toolName)
        check(row.outputIndex in 0..31 && row.receipt.assetId.matches(RECEIPT) &&
            row.sha256.matches(HASH) && row.receipt.assetId.endsWith("-${row.sha256}") &&
            row.bytes > 0 && row.width > 0 && row.height > 0)
        when (row.kind) {
            GenerationKind.IMAGE -> check(row.protocol != GenerationProtocol.OPENAI_VIDEO_MULTIPART &&
                row.mimeType in setOf("image/png", "image/jpeg", "image/webp") && row.durationMs == 0L)
            GenerationKind.VIDEO -> check(row.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART &&
                row.mimeType in setOf("video/mp4", "video/webm") && row.durationMs > 0)
        }
    }

    /** Only scalar attempt metadata is needed; never load the possibly large/private outcome BLOB. */
    private fun authorize(db: SQLiteDatabase, attempt: GenerationAttempt) {
        db.rawQuery("SELECT conversation_id,assistant_id,run_id,tool_call_id,tool_name,fingerprint,protocol " +
            "FROM ${SQLiteGenerationAttemptStore.TABLE} WHERE operation_id=? AND item_id=?",
            arrayOf(attempt.identity.operationId, attempt.identity.itemId)).use {
            check(it.moveToFirst())
            check(it.getString(0) == binding.conversationId && it.getString(1) == binding.assistantMessageId &&
                it.getString(2) == binding.runId && it.getString(3) == binding.toolCallId &&
                it.getString(4) == binding.toolName && it.getString(5) == attempt.fingerprint &&
                it.getString(6) == attempt.protocol.name)
        }
        db.rawQuery("SELECT m.id FROM messages m JOIN conversations c ON c.id=m.conversation_id " +
            "WHERE m.id=? AND m.conversation_id=? AND m.run_id=? AND m.role='assistant'",
            arrayOf(binding.assistantMessageId, binding.conversationId, binding.runId)).use { check(it.moveToFirst()) }
        val history = MessageHistoryCodec.decode(JSONArray(checkNotNull(SQLiteText.read(db,
            "messages", "history_segments", "id", binding.assistantMessageId))))
        val names = if (history.isNotEmpty()) {
            history.filter { it.role == "assistant" }.flatMap {
                check(it.messageId.value == binding.assistantMessageId)
                it.blocks.filterIsInstance<MessageBlock.Tool>()
            }.filter { it.invocation.id.value == binding.toolCallId }.map { it.invocation.name }
        } else {
            val blocks = JSONArray(checkNotNull(SQLiteText.read(db, "messages", "blocks", "id", binding.assistantMessageId)))
            (0 until blocks.length()).map { blocks.getJSONObject(it) }
                .filter { it.getString("type") == "tool" && it.getString("id") == binding.toolCallId }
                .map { it.getString("name") }
        }
        check(names.size == 1 && names.single() == binding.toolName)
        // A completed tool/run can recover registration; live cancellation policy belongs to fence.
    }

    private fun values(row: GenerationOutputReference) = ContentValues().apply {
        put("operation_id", row.identity.operationId); put("item_id", row.identity.itemId)
        put("output_index", row.outputIndex); put("fingerprint", row.fingerprint); put("protocol", row.protocol.name)
        put("account_scope", row.accountScope); put("conversation_id", row.conversationId)
        put("assistant_id", row.assistantMessageId); put("run_id", row.runId)
        put("tool_call_id", row.toolCallId); put("tool_name", row.toolName)
        put("receipt", row.receipt.assetId); put("kind", row.kind.name); put("mime_type", row.mimeType)
        put("byte_count", row.bytes); put("sha256", row.sha256)
        put("width", row.width); put("height", row.height); put("duration_ms", row.durationMs)
    }

    private fun read(db: SQLiteDatabase, identity: GenerationIdentity, index: Int): GenerationOutputReference? =
        db.rawQuery("SELECT fingerprint,protocol,account_scope,conversation_id,assistant_id,run_id,tool_call_id," +
            "tool_name,receipt,kind,mime_type,byte_count,sha256,width,height,duration_ms," +
            "operation_id,item_id,output_index FROM $TABLE WHERE operation_id=? AND item_id=? AND output_index=?",
            arrayOf(identity.operationId, identity.itemId, index.toString())).use {
            if (!it.moveToFirst()) return@use null
            // Validate storage classes BEFORE getters can coerce REAL/TEXT or narrow a 64-bit INTEGER.
            // Read the stored key too: substituting the query arguments would hide malformed keys.
            GenerationOutputReference(GenerationIdentity(it.text(16), it.text(17)),
                it.integer(18, 0L..31L).toInt(), it.text(0), GenerationProtocol.valueOf(it.text(1)),
                it.text(2), it.text(3), it.text(4), it.text(5), it.text(6), it.text(7),
                GenerationAssetReceipt(it.text(8)), GenerationKind.valueOf(it.text(9)), it.text(10),
                it.integer(11, 1L..Long.MAX_VALUE), it.text(12),
                it.integer(13, 1L..Int.MAX_VALUE.toLong()).toInt(),
                it.integer(14, 1L..Int.MAX_VALUE.toLong()).toInt(),
                it.integer(15, 0L..Long.MAX_VALUE)).also {
                check(it.identity == identity && it.outputIndex == index)
            }
        }

    private fun Cursor.text(column: Int): String {
        check(getType(column) == Cursor.FIELD_TYPE_STRING)
        return getString(column)
    }

    private fun Cursor.integer(column: Int, range: LongRange): Long {
        check(getType(column) == Cursor.FIELD_TYPE_INTEGER)
        return getLong(column).also { check(it in range) }
    }

    private fun readyDatabase(): SQLiteDatabase = database().also { checkDatabase(it) }

    private fun checkDatabase(db: SQLiteDatabase) {
        check(db.isOpen && !db.isReadOnly && File(db.path).isAbsolute && !db.inTransaction())
        check(db.rawQuery("PRAGMA synchronous", null).use { it.moveToFirst() && it.getInt(0) >= 2 })
        check(db.rawQuery("PRAGMA journal_mode", null).use {
            it.moveToFirst() && it.getString(0).lowercase() !in setOf("off", "memory")
        })
    }

    private fun <T> transaction(db: SQLiteDatabase, action: (SQLiteDatabase) -> T): T {
        checkDatabase(db)
        db.beginTransaction()
        val result: T
        try {
            result = action(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction() // Commit may fail; never return a success before this completes.
        }
        return result
    }

    private fun <T> guarded(action: () -> T): T = try { action() } catch (_: Exception) {
        // Do not expose SQL, paths, owner identifiers or signed/private values in an exception cause.
        throw IllegalStateException("generation_output_store_unavailable")
    }

    companion object {
        const val TABLE = "generation_outputs"
        private val HASH = Regex("[0-9a-f]{64}")
        private val RECEIPT = Regex("genasset-v1-[0-9a-f]{64}-[0-9a-f]{64}")

        /** Parent calls after installing its messages and attempt schemas in onCreate/onUpgrade. */
        fun installSchema(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS generation_outputs (
                operation_id TEXT NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),
                item_id TEXT NOT NULL CHECK(length(item_id) BETWEEN 1 AND 128),
                output_index INTEGER NOT NULL CHECK(typeof(output_index)='integer' AND output_index BETWEEN 0 AND 31),
                fingerprint TEXT NOT NULL CHECK(length(CAST(fingerprint AS BLOB)) BETWEEN 1 AND 512),
                protocol TEXT NOT NULL CHECK(protocol IN ('OPENAI_IMAGES_JSON','OPENAI_IMAGE_EDIT_MULTIPART','OPENAI_VIDEO_MULTIPART')),
                account_scope TEXT NOT NULL CHECK(length(account_scope) BETWEEN 1 AND 200),
                conversation_id TEXT NOT NULL, assistant_id TEXT NOT NULL, run_id TEXT NOT NULL,
                tool_call_id TEXT NOT NULL, tool_name TEXT NOT NULL,
                receipt TEXT NOT NULL UNIQUE CHECK(length(receipt)=141),
                kind TEXT NOT NULL CHECK(kind IN ('IMAGE','VIDEO')), mime_type TEXT NOT NULL,
                byte_count INTEGER NOT NULL CHECK(typeof(byte_count)='integer' AND byte_count BETWEEN 1 AND 9223372036854775807),
                sha256 TEXT NOT NULL CHECK(length(sha256)=64),
                width INTEGER NOT NULL CHECK(typeof(width)='integer' AND width BETWEEN 1 AND 2147483647),
                height INTEGER NOT NULL CHECK(typeof(height)='integer' AND height BETWEEN 1 AND 2147483647),
                duration_ms INTEGER NOT NULL CHECK(typeof(duration_ms)='integer' AND duration_ms BETWEEN 0 AND 9223372036854775807),
                PRIMARY KEY(operation_id,item_id,output_index),
                UNIQUE(conversation_id,assistant_id,run_id,tool_call_id,item_id,output_index)
            )""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS generation_outputs_owner ON generation_outputs(conversation_id,assistant_id)")
        }
    }
}
