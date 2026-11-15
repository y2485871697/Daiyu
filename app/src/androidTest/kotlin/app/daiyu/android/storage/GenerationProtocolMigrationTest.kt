package app.daiyu.android.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.model.generation.*
import app.daiyu.android.testContext
import java.util.UUID

@Suppress("DEPRECATION")
class GenerationProtocolMigrationTest : InstrumentationTestCase() {
    private val contexts = mutableListOf<android.content.Context>()
    private val helpers = mutableListOf<ConversationDatabase>()
    private val hash = "a".repeat(64)
    private fun helper(context: android.content.Context) = ConversationDatabase(context).also { helpers += it }
    private fun context() = testContext(instrumentation.targetContext, "protocol-migration-${UUID.randomUUID()}-")
        .also { contexts += it }

    override fun tearDown() {
        try {
            helpers.forEach { it.close() }
            contexts.forEach { it.deleteDatabase("conversations.db") }
        } finally { super.tearDown() }
    }

    private fun owner(db: SQLiteDatabase, binding: GenerationAttemptBinding) {
        db.insertOrThrow("conversations", null, ContentValues().apply {
            put("id", binding.conversationId); put("title", "retained"); put("draft", "draft"); put("updated", 123L)
        })
        db.insertOrThrow("messages", null, ContentValues().apply {
            put("id", binding.assistantMessageId); put("conversation_id", binding.conversationId)
            put("run_id", binding.runId); put("role", "assistant"); put("status", "RUNNING")
            put("blocks", """[{"type":"tool","id":"${binding.toolCallId}","name":"${binding.toolName}","arguments":"{}","result":null}]""")
            put("protocol", "[]"); put("history_segments", "[]")
        })
    }

    private fun output(binding: GenerationAttemptBinding, protocol: GenerationProtocol = GenerationProtocol.OPENAI_IMAGES_JSON) = ContentValues().apply {
        put("operation_id", binding.operationId); put("item_id", "item"); put("output_index", 0)
        put("fingerprint", hash); put("protocol", protocol.name); put("account_scope", "account")
        put("conversation_id", binding.conversationId); put("assistant_id", binding.assistantMessageId)
        put("run_id", binding.runId); put("tool_call_id", binding.toolCallId); put("tool_name", binding.toolName)
        put("receipt", "genasset-v1-${"b".repeat(64)}-$hash"); put("kind", "IMAGE"); put("mime_type", "image/png")
        put("byte_count", 68L); put("sha256", hash); put("width", 1); put("height", 1); put("duration_ms", 0L)
    }

    private fun rows(db: SQLiteDatabase, table: String): List<String> {
        val columns = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }
        val projection = columns.joinToString(",") { "typeof($it),quote($it)" }
        return db.rawQuery("SELECT $projection FROM $table", null).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) }) }.sorted()
        }
    }

    private fun schema(db: SQLiteDatabase): List<String> = db.rawQuery(
        "SELECT type,name,coalesce(sql,'') FROM sqlite_master ORDER BY type,name", null).use { c ->
        buildList { while (c.moveToNext()) add((0..2).joinToString("|") { c.getString(it) }) }
    }

    private fun oldSchema(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE generation_attempts")
        db.execSQL(LEGACY_ATTEMPTS)
        db.execSQL("DROP TABLE generation_outputs")
        db.execSQL(LEGACY_OUTPUTS)
        db.execSQL("CREATE INDEX generation_outputs_owner ON generation_outputs(conversation_id,assistant_id)")
    }

    fun testRealHelperExpandsFrozenLegacyCheckWithoutChangingOutputsOrReservations() {
        for (version in listOf(11, 13, 32, 33)) {
            val context = context(); val initial = helper(context); val db = initial.writableDatabase
            oldSchema(db)
            val binding = GenerationAttemptBinding("owner", "assistant", "run", "call", "generate_media")
            val deleted = GenerationAttemptBinding("deleted", "deleted-assistant", "deleted-run", "deleted-call", "generate_media")
            owner(db, binding); owner(db, deleted)
            val attempt = GenerationAttempt(binding.identity("item"), hash, GenerationProtocol.OPENAI_IMAGES_JSON)
            val pending = attempt.copy(identity = binding.identity("pending"))
            val tombstone = attempt.copy(identity = deleted.identity("item"))
            val store = SQLiteGenerationAttemptStore({ db }, binding)
            assertEquals(GenerationReservation.Started, store.reserve(attempt))
            val completed = Submission.Completed(listOf(GenerationOutput(0, GenerationSource.Inline(byteArrayOf(1, 2, 3), "image/png"))))
            store.finish(attempt, completed)
            assertEquals(GenerationReservation.Started, store.reserve(pending))
            assertEquals(GenerationReservation.Started, SQLiteGenerationAttemptStore({ db }, deleted).reserve(tombstone))
            db.insertOrThrow("generation_outputs", null, output(binding))
            assertTrue(runCatching { db.execSQL("UPDATE generation_outputs SET protocol='NOVELAI_IMAGE_JSON'") }.isFailure)
            db.delete("messages", "id=?", arrayOf(deleted.assistantMessageId))
            db.delete("conversations", "id=?", arrayOf(deleted.conversationId))
            val outputs = rows(db, "generation_outputs"); val attempts = rows(db, "generation_attempts")
            db.version = version; initial.close()
            val migrated = helper(context); val upgraded = migrated.writableDatabase
            assertEquals(34, upgraded.version)
            assertTrue(upgraded.rawQuery("SELECT sql FROM sqlite_master WHERE name='generation_attempts'", null)
                .use { it.moveToFirst(); it.getString(0).contains("BETWEEN 1 AND ${GenerationAttemptCodec.MAX_ENCODED_BYTES}") })
            assertEquals(outputs, rows(upgraded, "generation_outputs"))
            assertEquals(attempts, rows(upgraded, "generation_attempts"))
            for (protocol in GenerationProtocol.entries) {
                upgraded.execSQL("UPDATE generation_outputs SET protocol=?", arrayOf(protocol.name))
            }
            assertTrue(runCatching { upgraded.execSQL("UPDATE generation_outputs SET protocol='UNKNOWN'") }.isFailure)
            upgraded.execSQL("UPDATE generation_outputs SET protocol='OPENAI_IMAGES_JSON'")
            assertEquals(outputs, rows(upgraded, "generation_outputs"))
            assertEquals(1, upgraded.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='generation_outputs_owner' AND type='index'", null)
                .use { it.moveToFirst(); it.getInt(0) })
            assertEquals(0, upgraded.rawQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('generation_outputs_legacy_protocols','generation_attempts_legacy_limit')", null)
                .use { it.moveToFirst(); it.getInt(0) })
            migrated.close()
            val reopened = helper(context).writableDatabase
            assertEquals(34, reopened.version)
            assertEquals(outputs, rows(reopened, "generation_outputs"))
            assertEquals(attempts, rows(reopened, "generation_attempts"))
            val reserved = SQLiteGenerationAttemptStore({ reopened }, binding)
            val existing = reserved.reserve(attempt) as GenerationReservation.Existing
            assertTrue(GenerationAttemptCodec.encode(completed).contentEquals(
                GenerationAttemptCodec.encode(checkNotNull(existing.submission))))
            assertEquals(GenerationReservation.Existing(hash), reserved.reserve(pending))
            owner(reopened, deleted)
            assertEquals(GenerationReservation.Existing(hash), SQLiteGenerationAttemptStore({ reopened }, deleted).reserve(tombstone))
            assertEquals(attempts, rows(reopened, "generation_attempts"))
        }
    }

    fun testFailedProtocolCopyRollsBackDdlRowsVersionAndAttempts() {
        val context = context(); val initial = helper(context); val db = initial.writableDatabase
        oldSchema(db)
        val binding = GenerationAttemptBinding("owner", "assistant", "run", "call", "generate_media")
        owner(db, binding)
        val attempt = GenerationAttempt(binding.identity("item"), hash, GenerationProtocol.OPENAI_IMAGES_JSON)
        assertEquals(GenerationReservation.Started, SQLiteGenerationAttemptStore({ db }, binding).reserve(attempt))
        db.insertOrThrow("generation_outputs", null, output(binding))
        db.execSQL("PRAGMA ignore_check_constraints=ON")
        db.execSQL("UPDATE generation_outputs SET width=0")
        db.execSQL("PRAGMA ignore_check_constraints=OFF")
        db.version = 33
        val beforeSchema = schema(db); val beforeOutputs = rows(db, "generation_outputs"); val beforeAttempts = rows(db, "generation_attempts")
        initial.close()
        val failing = helper(context)
        assertTrue(runCatching { failing.writableDatabase }.isFailure)
        failing.close()
        context.openOrCreateDatabase("conversations.db", 0, null).use { raw ->
            assertEquals(33, raw.version); assertEquals(beforeSchema, schema(raw))
            assertEquals(beforeOutputs, rows(raw, "generation_outputs")); assertEquals(beforeAttempts, rows(raw, "generation_attempts"))
        }
    }

    private companion object {
        val LEGACY_ATTEMPTS = """CREATE TABLE generation_attempts (
            operation_id TEXT NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),
            item_id TEXT NOT NULL CHECK(length(item_id) BETWEEN 1 AND 128),
            conversation_id TEXT NOT NULL, assistant_id TEXT NOT NULL, run_id TEXT NOT NULL,
            tool_call_id TEXT NOT NULL, tool_name TEXT NOT NULL,
            fingerprint TEXT NOT NULL CHECK(length(CAST(fingerprint AS BLOB)) BETWEEN 1 AND 512), protocol TEXT NOT NULL,
            outcome BLOB CHECK(outcome IS NULL OR (typeof(outcome)='blob' AND length(outcome) BETWEEN 1 AND 33554432)),
            PRIMARY KEY(operation_id,item_id),
            UNIQUE(conversation_id,assistant_id,run_id,tool_call_id,item_id)
        )""".trimIndent()
        // Frozen output DDL from the pre-port v33 schema, not current installSchema.
        val LEGACY_OUTPUTS = """CREATE TABLE IF NOT EXISTS generation_outputs (
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
            )""".trimIndent()
    }
}
