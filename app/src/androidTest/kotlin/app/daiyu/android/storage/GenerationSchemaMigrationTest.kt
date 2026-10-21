package app.daiyu.android.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.media.GenerationAssetFence
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.media.MessageGenerationAssets
import app.daiyu.android.model.generation.GenerationAttempt
import app.daiyu.android.model.generation.GenerationProtocol
import app.daiyu.android.model.generation.GenerationReservation
import app.daiyu.android.model.generation.Submission
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID

/**
 * Real on-disk SQLiteOpenHelper opens, never direct onUpgrade calls. Reconstructed from the
 * current parent helper/store contracts and existing instrumentation fixtures; the original
 * reviewed test was not readable across the isolated worktree boundary.
 *
 * No application database, network, paid generation, UI, or private-asset GC is used. The
 * intentionally weak legacy output table also permits corrupt/adversarial storage classes:
 * a migration must inspect the source before destination affinity can silently repair it.
 */
@Suppress("DEPRECATION")
class GenerationSchemaMigrationTest : InstrumentationTestCase() {
    private val fixtures = mutableListOf<Fixture>()
    private val helpers = mutableListOf<ConversationDatabase>()
    private val numericColumns = listOf("output_index", "byte_count", "width", "height", "duration_ms")
    private val textColumns = listOf("operation_id", "item_id", "fingerprint", "protocol", "account_scope",
        "conversation_id", "assistant_id", "run_id", "tool_call_id", "tool_name", "receipt", "kind",
        "mime_type", "sha256")
    private val hash = "a".repeat(64)
    private val receipt = "genasset-v1-${"b".repeat(64)}-$hash"

    private inner class Fixture {
        val context: Context = testContext(instrumentation.targetContext, "generation-schema-${UUID.randomUUID()}-")
        val root = File(context.cacheDir, "generation-schema-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val binding = GenerationAttemptBinding("owner", "assistant", "run", "call", "generate_media")
        val attempt = GenerationAttempt(binding.identity("item"), hash, GenerationProtocol.OPENAI_IMAGES_JSON)
        fun helper() = ConversationDatabase(context).also { helpers.add(it) }
        fun raw(): SQLiteDatabase = context.openOrCreateDatabase("conversations.db", 0, null)
        fun assetOwner() = GenerationAssetOwner("offline-account", binding)
        fun outputStore(helper: ConversationDatabase): SQLiteGenerationOutputStore {
            val owner = assetOwner()
            return SQLiteGenerationOutputStore({ helper.writableDatabase }, owner,
                MessageGenerationAssets(File(root, "media"), GenerationAssetFence(owner) { true }))
        }
    }

    private fun fixture() = Fixture().also { fixtures.add(it) }

    override fun tearDown() {
        try {
            helpers.forEach { it.close() }
            fixtures.forEach {
                it.context.deleteDatabase("conversations.db")
                it.context.getSharedPreferences("daiyu-assistants", 0).edit().clear().commit()
                it.root.deleteRecursively()
            }
        } finally { super.tearDown() }
    }

    private fun tableExists(db: SQLiteDatabase, name: String) = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }

    private fun columns(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }

    /** Assert the v12 DDL itself, not merely the presence of a table with the right name. */
    private fun assertVideoSidecarSchema(db: SQLiteDatabase) {
        assertTrue(tableExists(db, "message_video_inputs"))
        val expected = """CREATE TABLE message_video_inputs(
            message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
            payload TEXT NOT NULL CHECK(typeof(payload)='text' AND length(CAST(payload AS BLOB)) BETWEEN 1 AND 1048576)
        )"""
        fun normalized(sql: String) = sql.replace(Regex("\\s+"), "").lowercase()
        assertEquals(normalized(expected), normalized(scalar(db,
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='message_video_inputs'")))
        assertEquals(listOf("message_id", "payload"), columns(db, "message_video_inputs"))
        db.rawQuery("PRAGMA foreign_key_list(message_video_inputs)", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("messages", c.getString(c.getColumnIndexOrThrow("table")))
            assertEquals("message_id", c.getString(c.getColumnIndexOrThrow("from")))
            assertEquals("id", c.getString(c.getColumnIndexOrThrow("to")))
            assertEquals("CASCADE", c.getString(c.getColumnIndexOrThrow("on_delete")))
            assertFalse(c.moveToNext())
        }
    }

    private fun assertVideoWitnessSchema(db: SQLiteDatabase) {
        val expected = """CREATE TABLE message_video_witnesses(
            message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
            reference_count INTEGER NOT NULL CHECK(typeof(reference_count)='integer' AND reference_count BETWEEN -1 AND 1024),
            manifest_sha256 TEXT,
            CHECK((reference_count=-1 AND manifest_sha256 IS NULL) OR
              (reference_count>=0 AND typeof(manifest_sha256)='text' AND length(manifest_sha256)=64
               AND manifest_sha256 NOT GLOB '*[^0-9a-f]*'))
        )"""
        fun normalized(sql: String) = sql.replace(Regex("\\s+"), "").lowercase()
        assertEquals(normalized(expected), normalized(scalar(db,
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='message_video_witnesses'")))
        assertEquals(listOf("message_id", "reference_count", "manifest_sha256"), columns(db, "message_video_witnesses"))
        db.rawQuery("PRAGMA foreign_key_list(message_video_witnesses)", null).use { c ->
            assertTrue(c.moveToFirst())
            for ((column, value) in listOf("table" to "messages", "from" to "message_id", "to" to "id",
                    "on_update" to "NO ACTION", "on_delete" to "CASCADE", "match" to "NONE")) {
                assertEquals(column, value, c.getString(c.getColumnIndexOrThrow(column)))
            }
            assertFalse(c.moveToNext())
        }
    }

    private fun scalar(db: SQLiteDatabase, sql: String): String = db.rawQuery(sql, null).use {
        check(it.moveToFirst()); it.getString(0)
    }

    private fun count(db: SQLiteDatabase, table: String) = scalar(db, "SELECT count(*) FROM $table").toInt()

    /** Includes DDL, indexes, triggers, every row/storage class/BLOB, sequence and user_version. */
    private fun snapshot(db: SQLiteDatabase): Map<String, List<String>> = buildMap {
        put("version", listOf(db.version.toString()))
        put("schema", db.rawQuery("SELECT type,name,tbl_name,coalesce(sql,'') FROM sqlite_master " +
            "ORDER BY type,name", null).use { c ->
            buildList { while (c.moveToNext()) add((0..3).joinToString("\u0000") { c.getString(it) }) }
        })
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        tables.forEach { table ->
            val projection = columns(db, table).joinToString(",") { "typeof(\"$it\"),quote(\"$it\")" }
            put(table, db.rawQuery("SELECT $projection FROM \"$table\"", null).use { c ->
                buildList { while (c.moveToNext()) add((0 until c.columnCount).joinToString("\u0000") { c.getString(it) }) }
                    .sorted()
            })
        }
    }

    private fun rejected(action: () -> Unit): Exception {
        try { action() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected rejection without changing the database")
    }

    private fun addOwner(db: SQLiteDatabase, binding: GenerationAttemptBinding) {
        db.insertOrThrow("conversations", null, ContentValues().apply {
            put("id", binding.conversationId); put("title", "retained title"); put("draft", "retained draft")
            put("updated", 123L)
            if ("folder" in columns(db, "conversations")) { put("folder", "work"); put("pinned", 1) }
        })
        db.insertOrThrow("messages", null, ContentValues().apply {
            put("id", binding.assistantMessageId); put("conversation_id", binding.conversationId)
            put("run_id", binding.runId); put("role", "assistant"); put("status", "RUNNING")
            put("blocks", "[{\"type\":\"tool\",\"id\":\"${binding.toolCallId}\",\"name\":\"${binding.toolName}\",\"arguments\":\"{}\",\"result\":null}]")
            put("protocol", "[]")
            if ("history_segments" in columns(db, "messages")) put("history_segments", "[]")
        })
    }

    /** Historical core shapes follow the helper's actual v1/v2/v3/v5/v10 boundaries. */
    private fun seedCore(f: Fixture, version: Int) {
        if (version == 10) {
            val helper = f.helper()
            helper.writableDatabase.let { db ->
                addOwner(db, f.binding)
                db.execSQL("UPDATE composer SET draft='composer retained'")
                db.execSQL("DROP TABLE generation_outputs")
                db.execSQL("DROP TABLE generation_attempts")
                db.execSQL("DROP TABLE message_video_witnesses")
                db.execSQL("DROP TABLE message_video_inputs")
                assertFalse(tableExists(db, "message_video_inputs"))
                assertFalse(tableExists(db, "message_video_witnesses"))
                db.version = version
            }
            helper.close()
            return
        }
        f.raw().use { db ->
            val organization = if (version >= 3) ",folder TEXT NOT NULL DEFAULT '',pinned INTEGER NOT NULL DEFAULT 0" else ""
            val history = if (version >= 5) ",history_segments TEXT NOT NULL DEFAULT '[]'" else ""
            db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL$organization)")
            db.execSQL("CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL," +
                "conversation_id TEXT NOT NULL,run_id TEXT NOT NULL,role TEXT NOT NULL,blocks TEXT NOT NULL," +
                "status TEXT NOT NULL,error TEXT,protocol TEXT NOT NULL$history)")
            db.execSQL("CREATE INDEX messages_tail ON messages(conversation_id,seq)")
            if (version >= 2) {
                db.execSQL("CREATE TABLE composer(id INTEGER PRIMARY KEY CHECK(id=1),draft TEXT NOT NULL)")
                db.execSQL("INSERT INTO composer VALUES(1,'composer retained')")
            }
            addOwner(db, f.binding)
            db.version = version
        }
    }

    private fun createLegacyOutputs(db: SQLiteDatabase, noAffinity: String? = null) {
        // Keep ownership/identity uniqueness and the old owner index, but allow bad legacy data.
        // An untyped source column is deliberate: INTEGER/TEXT affinity would normalize the
        // integral REAL/numeric TEXT cases before the migration ever got a chance to inspect them.
        fun type(column: String, normal: String) = if (column == noAffinity) "" else normal
        val declarations = listOf(
            "operation_id", "item_id", "output_index", "fingerprint", "protocol", "account_scope",
            "conversation_id", "assistant_id", "run_id", "tool_call_id", "tool_name", "receipt", "kind",
            "mime_type", "byte_count", "sha256", "width", "height", "duration_ms"
        ).joinToString(",") { "$it ${type(it, if (it in numericColumns) "INTEGER" else "TEXT")} NOT NULL" }
        db.execSQL("CREATE TABLE generation_outputs($declarations," +
            "PRIMARY KEY(operation_id,item_id,output_index),UNIQUE(receipt)," +
            "UNIQUE(conversation_id,assistant_id,run_id,tool_call_id,item_id,output_index))")
        db.execSQL("CREATE INDEX generation_outputs_owner ON generation_outputs(conversation_id,assistant_id)")
    }

    private fun outputValues(binding: GenerationAttemptBinding, index: Int = 0) = ContentValues().apply {
        put("operation_id", binding.operationId); put("item_id", "item"); put("output_index", index)
        put("fingerprint", hash); put("protocol", GenerationProtocol.OPENAI_IMAGES_JSON.name)
        put("account_scope", "offline-account"); put("conversation_id", binding.conversationId)
        put("assistant_id", binding.assistantMessageId); put("run_id", binding.runId)
        put("tool_call_id", binding.toolCallId); put("tool_name", binding.toolName)
        put("receipt", if (index == 0) receipt else "genasset-v1-${"c".repeat(64)}-$hash")
        put("kind", "IMAGE"); put("mime_type", "image/png"); put("byte_count", 68L)
        put("sha256", hash); put("width", 1); put("height", 1); put("duration_ms", 0L)
    }

    private fun seedLegacy(f: Fixture, version: Int = 10, noAffinity: String? = null) {
        seedCore(f, version)
        f.raw().use { db ->
            createLegacyOutputs(db, noAffinity)
            db.insertOrThrow("generation_outputs", null, outputValues(f.binding))
        }
    }

    private fun assertRejectedUpgradeUnchanged(f: Fixture, preflight: Boolean = true) {
        val before = f.raw().use(::snapshot)
        val helper = f.helper()
        val failure = rejected { helper.writableDatabase }
        if (preflight) {
            // A destination CHECK/UNIQUE error would NOT prove rejection before affinity/copy.
            assertTrue(failure is IllegalStateException)
            assertEquals("生成数据类型无效，保留原数据库", failure.message)
        } else assertTrue(failure is SQLiteConstraintException)
        helper.close()
        f.raw().use { db ->
            assertEquals(before, snapshot(db))
            assertFalse(tableExists(db, "generation_outputs_v10"))
        }
    }

    fun testFreshInstallHasBothTablesOwnerIndexAndStrictNumericConstraints() {
        val f = fixture(); val helper = f.helper(); val db = helper.writableDatabase
        assertEquals(24, db.version)
        assertVideoSidecarSchema(db)
        assertEquals(0, count(db, "message_video_inputs"))
        assertTrue(tableExists(db, "generation_attempts")); assertTrue(tableExists(db, "generation_outputs"))
        assertEquals("generation_outputs", scalar(db,
            "SELECT tbl_name FROM sqlite_master WHERE name='generation_outputs_owner' AND type='index'"))
        db.insertOrThrow("generation_outputs", null, outputValues(f.binding))
        val before = snapshot(db)
        val invalid = listOf("output_index" to "32", "output_index" to "-1", "output_index" to "0.5",
            "byte_count" to "0", "byte_count" to "1.5", "width" to "2147483648",
            "height" to "4294967297", "duration_ms" to "-1", "duration_ms" to "0.5") +
            numericColumns.flatMap { listOf(it to "'bad'", it to "X'31'", it to "NULL") }
        invalid.forEach { (column, literal) ->
            assertTrue("$column=$literal", rejected { db.execSQL("UPDATE generation_outputs SET $column=$literal") }
                is SQLiteConstraintException)
            assertEquals(before, snapshot(db))
        }
        helper.close()
        val reopenedDb = f.helper().writableDatabase
        assertEquals(24, reopenedDb.version)
        assertVideoSidecarSchema(reopenedDb)
        assertEquals(before, snapshot(reopenedDb))
    }

    fun testEveryVersionOneThroughTenUpgradesThroughRealHelperAndReopens() {
        for (version in 1..10) {
            val f = fixture(); seedCore(f, version)
            f.raw().use { source ->
                assertEquals(version, source.version)
                assertFalse(tableExists(source, "message_video_inputs"))
            }
            val helper = f.helper(); val db = helper.writableDatabase
            assertEquals("source v$version", 24, db.version)
            assertVideoSidecarSchema(db)
            assertEquals(0, count(db, "message_video_inputs"))
            assertTrue(tableExists(db, "generation_attempts")); assertTrue(tableExists(db, "generation_outputs"))
            assertEquals(0, count(db, "generation_attempts")); assertEquals(0, count(db, "generation_outputs"))
            assertEquals("retained draft", helper.conversations().single().draft)
            assertEquals(if (version >= 3) "work" else "", helper.conversations().single().folder)
            assertEquals(version >= 3, helper.conversations().single().pinned)
            assertNull(helper.conversations().single().assistantId)
            assertEquals(if (version == 1) "" else "composer retained", helper.draft())
            assertEquals(f.binding.assistantMessageId, helper.messages(ConversationId("owner")).single().second.id.value)
            assertEquals("[]", scalar(db, "SELECT history_segments FROM messages"))
            assertEquals(GenerationReservation.Started, SQLiteGenerationAttemptStore({ db }, f.binding).reserve(f.attempt))
            val after = snapshot(db)
            helper.close()
            val reopenedDb = f.helper().writableDatabase
            assertEquals(24, reopenedDb.version)
            assertVideoSidecarSchema(reopenedDb)
            assertEquals(after, snapshot(reopenedDb))
        }
    }

    fun testStrictV11AddsOnlyVideoSidecarAndPreservesOutputsTombstoneAndBlobAcrossReopen() {
        val f = fixture()
        // v10 contributes only the unchanged core. Install the real strict v11 generation
        // schemas explicitly; never use the permissive legacy table or seedCore(11).
        seedCore(f, 10)
        val outcome = Submission.Completed(emptyList())
        val outcomeBytes = GenerationAttemptCodec.encode(outcome)
        val deletedBinding = GenerationAttemptBinding("deleted-owner", "deleted-assistant",
            "deleted-run", "deleted-call", "generate_media")
        val deletedAttempt = GenerationAttempt(deletedBinding.identity("item"), hash, f.attempt.protocol)
        f.raw().use { db ->
            assertEquals(10, db.version)
            assertFalse(tableExists(db, "message_video_inputs"))
            assertFalse(tableExists(db, "message_video_witnesses"))
            // A raw source connection must honor the helper's durability configuration too.
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA synchronous=FULL")
            SQLiteGenerationAttemptStore.installSchema(db)
            SQLiteGenerationOutputStore.installSchema(db)
            db.version = 11
            val attempts = SQLiteGenerationAttemptStore({ db }, f.binding)
            assertEquals(GenerationReservation.Started, attempts.reserve(f.attempt))
            attempts.finish(f.attempt, outcome)
            // Metadata-only fixture: no generated asset bytes or registration/network work.
            db.insertOrThrow("generation_outputs", null, outputValues(f.binding))
            addOwner(db, deletedBinding)
            val deletedAttempts = SQLiteGenerationAttemptStore({ db }, deletedBinding)
            assertEquals(GenerationReservation.Started, deletedAttempts.reserve(deletedAttempt))
            deletedAttempts.finish(deletedAttempt, outcome)
            assertEquals(1, db.delete("messages", "id=?", arrayOf(deletedBinding.assistantMessageId)))
            assertEquals(1, db.delete("conversations", "id=?", arrayOf(deletedBinding.conversationId)))
            assertEquals(2, count(db, "generation_attempts"))
            assertEquals(1, count(db, "generation_outputs"))
            assertEquals(1, count(db, "messages"))
            assertEquals("blob", scalar(db, "SELECT typeof(outcome) FROM generation_attempts LIMIT 1"))
            val strict = snapshot(db)
            assertTrue(rejected { db.execSQL("UPDATE generation_outputs SET width=2147483648") }
                is SQLiteConstraintException)
            assertTrue(rejected { db.execSQL("UPDATE generation_attempts SET outcome='not-a-blob'") }
                is SQLiteConstraintException)
            assertEquals(strict, snapshot(db))
        }
        val before = f.raw().use { db ->
            assertEquals(11, db.version)
            assertFalse(tableExists(db, "message_video_inputs"))
            assertFalse(tableExists(db, "message_video_witnesses"))
            snapshot(db)
        }
        val helper = f.helper(); val db = helper.writableDatabase
        assertEquals(24, db.version)
        assertVideoSidecarSchema(db)
        assertVideoWitnessSchema(db)
        assertEquals(0, count(db, "message_video_inputs"))
        val after = snapshot(db)
        val addedTables = setOf("message_video_inputs", "message_video_witnesses")
        assertEquals(before.keys + addedTables, after.keys)
        val addedSchema = after.getValue("schema").filter { it.split('\u0000')[2] in addedTables }
        assertEquals(listOf(
            listOf("index", "sqlite_autoindex_message_video_inputs_1", "message_video_inputs"),
            listOf("index", "sqlite_autoindex_message_video_witnesses_1", "message_video_witnesses"),
            listOf("table", "message_video_inputs", "message_video_inputs"),
            listOf("table", "message_video_witnesses", "message_video_witnesses"),
        ), addedSchema.map { it.split('\u0000').take(3) })
        addedSchema.filter { it.split('\u0000')[0] == "index" }.forEach { assertEquals("", it.split('\u0000')[3]) }
        assertEquals(before["schema"], after.getValue("schema").filterNot { it.split('\u0000')[2] in addedTables })
        before.filterKeys { it != "version" && it != "schema" }.forEach { (table, rows) ->
            assertEquals(table, rows, after[table])
        }
        val migratedMessage = helper.messages(ConversationId(f.binding.conversationId)).single().second
        val expectedWitness = java.security.MessageDigest.getInstance("SHA-256")
            .digest(MessageVideoInputCodec.encode(migratedMessage, emptyList()).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        db.rawQuery("SELECT message_id,reference_count,manifest_sha256 FROM message_video_witnesses", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(f.binding.assistantMessageId, c.getString(0))
            assertEquals(0, c.getInt(1))
            assertEquals(expectedWitness, c.getString(2))
            assertFalse(c.moveToNext())
        }
        val found = f.outputStore(helper).find(f.attempt, 0)!!
        assertEquals(receipt, found.receipt.assetId)
        assertEquals(GenerationReservation.Existing(hash, outcome),
            SQLiteGenerationAttemptStore({ db }, f.binding).reserve(f.attempt))
        helper.close()
        val reopened = f.helper(); val reopenedDb = reopened.writableDatabase
        assertEquals(24, reopenedDb.version)
        assertVideoSidecarSchema(reopenedDb)
        assertVideoWitnessSchema(reopenedDb)
        assertEquals(after, snapshot(reopenedDb))
        assertEquals(found, f.outputStore(reopened).find(f.attempt, 0))
        reopenedDb.rawQuery("SELECT outcome FROM generation_attempts", null).use { c ->
            var rows = 0
            while (c.moveToNext()) {
                assertTrue(outcomeBytes.contentEquals(c.getBlob(0)))
                rows++
            }
            assertEquals(2, rows)
        }
        // Recreating the deleted owner must not turn its retained v11 tombstone into Started.
        addOwner(reopenedDb, deletedBinding)
        assertEquals(GenerationReservation.Existing(hash, outcome),
            SQLiteGenerationAttemptStore({ reopenedDb }, deletedBinding).reserve(deletedAttempt))
        assertEquals(before["generation_attempts"], snapshot(reopenedDb)["generation_attempts"])
    }

    fun testValidV10CopyPreservesAllMetadataAttemptBlobAndStoreReadAcrossReopen() {
        val f = fixture(); seedLegacy(f)
        val outcome = Submission.Completed(emptyList())
        val outcomeBytes = GenerationAttemptCodec.encode(outcome)
        f.raw().use { db ->
            SQLiteGenerationAttemptStore.installSchema(db)
            db.insertOrThrow("generation_attempts", null, ContentValues().apply {
                put("operation_id", f.binding.operationId); put("item_id", "item")
                put("conversation_id", "owner"); put("assistant_id", "assistant"); put("run_id", "run")
                put("tool_call_id", "call"); put("tool_name", "generate_media")
                put("fingerprint", hash); put("protocol", f.attempt.protocol.name)
                put("outcome", outcomeBytes)
            })
            db.insertOrThrow("generation_outputs", null, outputValues(f.binding, 31).apply {
                put("byte_count", Long.MAX_VALUE); put("width", Int.MAX_VALUE); put("height", Int.MAX_VALUE)
            })
        }
        val before = f.raw().use(::snapshot)
        val helper = f.helper(); val db = helper.writableDatabase
        assertEquals(24, db.version)
        assertVideoSidecarSchema(db)
        listOf("generation_outputs", "generation_attempts", "conversations", "messages", "composer").forEach {
            assertEquals(it, before[it], snapshot(db)[it])
        }
        assertFalse(tableExists(db, "generation_outputs_v10"))
        val found = f.outputStore(helper).find(f.attempt, 0)!!
        assertEquals(f.attempt.identity, found.identity); assertEquals(receipt, found.receipt.assetId)
        assertEquals(hash, found.fingerprint); assertEquals(hash, found.sha256)
        assertEquals(68L, found.bytes); assertEquals(1, found.width); assertEquals(1, found.height)
        assertEquals(0L, found.durationMs); assertEquals("image/png", found.mimeType)
        assertEquals("offline-account", found.accountScope)
        assertEquals("owner", found.conversationId); assertEquals("assistant", found.assistantMessageId)
        assertEquals("run", found.runId); assertEquals("call", found.toolCallId); assertEquals("generate_media", found.toolName)
        assertEquals(f.attempt.protocol, found.protocol)
        val boundary = f.outputStore(helper).find(f.attempt, 31)!!
        assertEquals(31, boundary.outputIndex); assertEquals(Long.MAX_VALUE, boundary.bytes)
        assertEquals(Int.MAX_VALUE, boundary.width); assertEquals(Int.MAX_VALUE, boundary.height)
        assertTrue(rejected { db.execSQL("UPDATE generation_outputs SET width=4294967297") } is SQLiteConstraintException)
        helper.close()
        val reopened = f.helper()
        assertEquals(24, reopened.readableDatabase.version)
        assertVideoSidecarSchema(reopened.readableDatabase)
        assertEquals(found, f.outputStore(reopened).find(f.attempt, 0))
        reopened.readableDatabase.rawQuery("SELECT outcome FROM generation_attempts", null).use {
            assertTrue(it.moveToFirst()); assertTrue(outcomeBytes.contentEquals(it.getBlob(0)))
        }
        assertEquals(GenerationReservation.Existing(hash, outcome),
            SQLiteGenerationAttemptStore({ reopened.writableDatabase }, f.binding).reserve(f.attempt))
    }

    fun testIntegralRealNumericTextAndBlobAreRejectedBeforeDestinationAffinityCopy() {
        numericColumns.forEach { column ->
            val number = if (column == "output_index" || column == "duration_ms") "0" else "1"
            listOf("$number.0" to "real", "'$number'" to "text", "X'31'" to "blob").forEach { (literal, type) ->
                val f = fixture(); seedLegacy(f, noAffinity = column)
                f.raw().use { db ->
                    db.execSQL("UPDATE generation_outputs SET $column=$literal")
                    assertEquals(type, scalar(db, "SELECT typeof($column) FROM generation_outputs"))
                }
                assertRejectedUpgradeUnchanged(f)
            }
        }
    }

    fun testInvalidIntegerRangesIncludingIntOverflowRollbackWithoutTruncation() {
        listOf("output_index" to "-1", "output_index" to "32", "byte_count" to "0",
            "byte_count" to "-1", "width" to "0", "width" to "2147483648", "width" to "4294967297",
            "height" to "0", "height" to "4294967297", "duration_ms" to "-1").forEach { (column, literal) ->
            val f = fixture(); seedLegacy(f)
            f.raw().use { db ->
                db.execSQL("UPDATE generation_outputs SET $column=$literal")
                assertEquals("integer", scalar(db, "SELECT typeof($column) FROM generation_outputs"))
                assertEquals(literal, scalar(db, "SELECT quote($column) FROM generation_outputs"))
            }
            assertRejectedUpgradeUnchanged(f)
        }
    }

    fun testEveryTextColumnRejectsNumericAndBlobStorageBeforeCopy() {
        textColumns.forEach { column ->
            listOf("123" to "integer", "X'313233'" to "blob").forEach { (literal, type) ->
                val f = fixture(); seedLegacy(f, noAffinity = column)
                f.raw().use { db ->
                    db.execSQL("UPDATE generation_outputs SET $column=$literal")
                    assertEquals(type, scalar(db, "SELECT typeof($column) FROM generation_outputs"))
                }
                assertRejectedUpgradeUnchanged(f)
            }
        }
    }

    fun testCopyConstraintFailureRollsBackEarlierV9DdlRowsVersionAndCanRetry() {
        val f = fixture(); seedLegacy(f, version = 9)
        f.raw().use { db -> db.execSQL("UPDATE generation_outputs SET receipt='too-short'") }
        assertRejectedUpgradeUnchanged(f, preflight = false)
        f.raw().use { db ->
            assertEquals(9, db.version)
            assertFalse("assistant_id" in columns(db, "conversations"))
            assertFalse(tableExists(db, "assistants")); assertFalse(tableExists(db, "generation_attempts"))
            db.execSQL("UPDATE generation_outputs SET receipt=?", arrayOf(receipt))
        }
        val helper = f.helper(); val db = helper.writableDatabase
        assertEquals(24, db.version); assertEquals(1, count(db, "generation_outputs"))
        assertVideoSidecarSchema(db)
        assertTrue(tableExists(db, "assistants")); assertTrue(tableExists(db, "generation_attempts"))
        assertEquals("retained draft", helper.conversations().single().draft)
        assertEquals("composer retained", helper.draft())
        assertNull(helper.conversations().single().assistantId)
    }

    private fun currentOwner(f: Fixture): ConversationDatabase {
        val helper = f.helper(); val db = helper.writableDatabase
        addOwner(db, f.binding)
        assertEquals(GenerationReservation.Started, SQLiteGenerationAttemptStore({ db }, f.binding).reserve(f.attempt))
        db.insertOrThrow("generation_outputs", null, outputValues(f.binding))
        db.insertOrThrow("generation_outputs", null, outputValues(f.binding, 1))
        // Populate every existing delete branch, so a late failure must restore more than outputs.
        db.execSQL("INSERT INTO neutral_assets(id,message_id,kind,mime,bytes,blob_hash) " +
            "VALUES('asset','assistant','image','image/png',1,'retained-hash')")
        db.execSQL("INSERT INTO context_archives(id,conversation_id,prefix_count,revision,summary,blob_hash,created,active) " +
            "VALUES('archive','owner',1,'revision','summary','retained-hash',1,1)")
        db.execSQL("INSERT INTO context_branches(conversation_id,parent_id,through_message,parent_revision) " +
            "VALUES('owner','parent','assistant','revision')")
        db.execSQL("INSERT INTO context_usage(request_id,run_id,branch_id,input_tokens) VALUES('usage','run','owner',1)")
        return helper
    }

    fun testDeleteRemovesOnlyOwnedOutputsButRetainsTombstonesEvenAfterOwnerRecreation() {
        val f = fixture(); val helper = currentOwner(f); val db = helper.writableDatabase
        val other = GenerationAttemptBinding("other", "other-assistant", "other-run", "other-call", "generate_media")
        addOwner(db, other)
        val otherAttempt = GenerationAttempt(other.identity("item"), hash, f.attempt.protocol)
        assertEquals(GenerationReservation.Started, SQLiteGenerationAttemptStore({ db }, other).reserve(otherAttempt))
        db.insertOrThrow("generation_outputs", null, outputValues(other).apply {
            put("receipt", "genasset-v1-${"d".repeat(64)}-$hash")
        })
        val attempts = snapshot(db)["generation_attempts"]
        helper.delete(ConversationId("owner"))
        assertEquals(1, count(db, "generation_outputs")); assertEquals(1, count(db, "messages"))
        assertEquals("other", scalar(db, "SELECT id FROM conversations"))
        listOf("neutral_assets", "context_archives", "context_branches", "context_usage").forEach {
            assertEquals(it, 0, count(db, it))
        }
        assertEquals(attempts, snapshot(db)["generation_attempts"])
        helper.close()
        val reopened = f.helper(); val reopenedDb = reopened.writableDatabase
        addOwner(reopenedDb, f.binding)
        val result = SQLiteGenerationAttemptStore({ reopenedDb }, f.binding).reserve(f.attempt)
        assertTrue(result is GenerationReservation.Existing)
        assertEquals(hash, (result as GenerationReservation.Existing).fingerprint)
        assertEquals(attempts, snapshot(reopenedDb)["generation_attempts"])
        assertNull(f.outputStore(reopened).find(f.attempt, 0))
    }

    fun testDeleteIgnoreResidualCheckRollsBackDeletedSiblingAndTriggerSideEffects() {
        val f = fixture(); val helper = currentOwner(f); val db = helper.writableDatabase
        db.execSQL("CREATE TABLE delete_audit(value TEXT)")
        db.execSQL("CREATE TRIGGER audit_output_delete AFTER DELETE ON generation_outputs " +
            "BEGIN INSERT INTO delete_audit VALUES('deleted'); END")
        db.execSQL("CREATE TRIGGER ignore_one_output BEFORE DELETE ON generation_outputs " +
            "WHEN OLD.output_index=1 BEGIN SELECT RAISE(IGNORE); END")
        val before = snapshot(db)
        val failure = rejected { helper.delete(ConversationId("owner")) }
        assertTrue(failure is IllegalStateException)
        assertEquals("生成引用未删除，保留会话", failure.message)
        assertEquals(before, snapshot(db))
        helper.close()
        val reopened = f.helper(); val reopenedDb = reopened.writableDatabase
        assertEquals(before, snapshot(reopenedDb))
        reopenedDb.execSQL("DROP TRIGGER ignore_one_output")
        reopened.delete(ConversationId("owner"))
        assertEquals(0, count(reopenedDb, "generation_outputs")); assertEquals(0, count(reopenedDb, "messages"))
        assertEquals(0, count(reopenedDb, "conversations")); assertEquals(1, count(reopenedDb, "generation_attempts"))
        assertEquals(2, count(reopenedDb, "delete_audit"))
    }

    fun testLateDeleteFailureRestoresOutputsMessagesConversationAndAttemptTombstone() {
        val f = fixture(); val helper = currentOwner(f); val db = helper.writableDatabase
        db.execSQL("CREATE TRIGGER abort_conversation_delete BEFORE DELETE ON conversations " +
            "BEGIN SELECT RAISE(ABORT,'injected delete failure'); END")
        val before = snapshot(db)
        assertTrue(rejected { helper.delete(ConversationId("owner")) } is SQLiteConstraintException)
        assertEquals(before, snapshot(db))
        helper.close()
        val reopened = f.helper()
        assertEquals(before, snapshot(reopened.writableDatabase))
        assertNotNull(f.outputStore(reopened).find(f.attempt, 0))
        assertTrue(SQLiteGenerationAttemptStore({ reopened.writableDatabase }, f.binding).reserve(f.attempt)
            is GenerationReservation.Existing)
    }
}
