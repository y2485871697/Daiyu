package app.daiyu.android.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistorySegment
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.ToolCallId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.media.GenerationAssetFence
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.media.MessageGenerationAssets
import app.daiyu.android.media.PendingGenerationAsset
import app.daiyu.android.model.generation.*
import org.json.JSONArray
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Isolated real SQLite + static PNG. No app DB migration, HTTP, UI, paid generation or GC. */
@Suppress("DEPRECATION")
class SQLiteGenerationOutputStoreTest : InstrumentationTestCase() {
    private val helpers = mutableListOf<SQLiteOpenHelper>()
    private val roots = mutableListOf<File>()
    private val png get() = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kF9sAAAAASUVORK5CYII=")

    private fun helper(file: File): SQLiteOpenHelper = object : SQLiteOpenHelper(
        instrumentation.targetContext, file.absolutePath, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA synchronous=FULL")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL," +
                "role TEXT NOT NULL,status TEXT NOT NULL,blocks TEXT NOT NULL,history_segments TEXT NOT NULL)")
            SQLiteGenerationAttemptStore.installSchema(db)
            SQLiteGenerationOutputStore.installSchema(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("fixture only")
    }.also { helpers.add(it) }

    private inner class Fixture(val root: File, val helper: SQLiteOpenHelper, val owner: GenerationAssetOwner,
        val legacy: String, val history: String) {
        val binding get() = owner.binding
        val db get() = helper.writableDatabase
        val attempt get() = GenerationAttempt(binding.identity("item1"), "a".repeat(64), GenerationProtocol.OPENAI_IMAGES_JSON)
        val admissions = AtomicInteger()
        fun assets(assetOwner: GenerationAssetOwner = owner) = MessageGenerationAssets(File(root, "media"),
            GenerationAssetFence(assetOwner) {
                // Assert the API never holds a SQL transaction while asking the asset fence.
                check(!db.inTransaction()); admissions.incrementAndGet(); true
            })
        fun store(connection: SQLiteOpenHelper = helper, assetOwner: GenerationAssetOwner = owner,
            media: MessageGenerationAssets = assets(assetOwner)) =
            SQLiteGenerationOutputStore({ connection.writableDatabase }, assetOwner, media)
        fun publish(index: Int = 0): PendingGenerationAsset {
            val media = assets()
            val info = media.inspect(GenerationKind.IMAGE, png, "image/png")
            val receipt = media.commit(attempt.identity, index, GenerationKind.IMAGE, png, info)
            return media.pending(receipt)
        }
        fun reserve() = SQLiteGenerationAttemptStore({ db }, binding).reserve(attempt)
        fun count(table: String) = db.rawQuery("SELECT count(*) FROM $table", null).use {
            check(it.moveToFirst()); it.getInt(0)
        }
        fun change(column: String, value: String) {
            check(db.update("messages", ContentValues().apply { put(column, value) }, "id=?",
                arrayOf(binding.assistantMessageId)) == 1)
        }
    }

    private fun fixture(withHistory: Boolean = false): Fixture {
        val root = File(instrumentation.targetContext.cacheDir, "output-store-${UUID.randomUUID()}")
        check(root.mkdirs()); roots.add(root)
        val db = helper(File(root, "owner.sqlite"))
        val binding = GenerationAttemptBinding(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), "tool-call", "generate_media")
        val owner = GenerationAssetOwner("offline-account", binding)
        val call = MessageBlock.Tool(ToolInvocation(ToolCallId(binding.toolCallId), binding.toolName, "{}"))
        val history = MessageHistoryCodec.encode(listOf(
            MessageHistorySegment(MessageId(binding.assistantMessageId), "assistant", listOf(call)))).toString()
        val legacy = JSONArray(history).getJSONObject(0).getJSONArray("blocks").toString()
        val connection = db.writableDatabase
        connection.insertOrThrow("conversations", null, ContentValues().apply { put("id", binding.conversationId) })
        connection.insertOrThrow("messages", null, ContentValues().apply {
            put("id", binding.assistantMessageId); put("conversation_id", binding.conversationId)
            put("run_id", binding.runId); put("role", "assistant"); put("status", "RUNNING")
            put("blocks", legacy); put("history_segments", if (withHistory) history else "[]")
        })
        return Fixture(root, db, owner, legacy, history)
    }

    private fun rejects(action: () -> Unit) {
        try { action() } catch (failure: IllegalStateException) {
            assertEquals("generation_output_store_unavailable", failure.message)
            assertNull(failure.cause)
            return
        }
        fail("Expected fail-closed output rejection")
    }

    override fun tearDown() {
        try {
            helpers.forEach { it.close() }
            roots.forEach { it.deleteRecursively() }
        } finally { super.tearDown() }
    }

    fun testImmutableIdempotentReferencesAndPrivateOriginalSurviveIndependentHelperAndReopen() {
        val f = fixture(withHistory = true)
        f.reserve()
        val later = f.publish(2); val first = f.publish(0)
        val store = f.store()
        assertNull(store.find(f.attempt, 0))
        val registered = store.register(f.attempt, later)
        assertEquals(registered, store.register(f.attempt, later.receipt))
        // Different owner object, same exact scalar ownership, is a valid recovery descriptor.
        assertEquals(registered, store.register(f.attempt, later.copy(owner = GenerationAssetOwner(
            f.owner.accountScope, GenerationAttemptBinding(f.binding.conversationId, f.binding.assistantMessageId,
                f.binding.runId, f.binding.toolCallId, f.binding.toolName)))))
        assertEquals(later.bytes, registered.bytes); assertEquals(later.sha256, registered.sha256)
        assertEquals(later.width, registered.width); assertEquals(later.height, registered.height)
        assertEquals(later.durationMs, registered.durationMs); assertEquals(later.mimeType, registered.mimeType)
        assertEquals(later.kind, registered.kind); assertEquals(later.receipt, registered.receipt)
        assertEquals(f.binding.toolCallId, registered.toolCallId); assertEquals(f.binding.toolName, registered.toolName)
        store.register(f.attempt, first)
        val observer = helper(File(f.root, "owner.sqlite"))
        assertEquals(registered, f.store(observer).find(f.attempt, 2))
        f.helper.close(); observer.close()
        val reopened = f.store(helper(File(f.root, "owner.sqlite")))
        assertEquals(registered, reopened.find(f.attempt, 2))
        assertTrue(png.contentEquals(reopened.open(f.attempt, 0)!!.use { it.readBytes() }))
        assertNull(reopened.open(f.attempt, 1)); assertEquals(2, f.count("generation_outputs"))
        // Recovery after completion does not reopen or mutate the attempt.
        f.change("status", "COMPLETED")
        assertEquals(registered, reopened.register(f.attempt, later))
        assertEquals(1, f.count("generation_attempts"))
    }

    fun testMissingAttemptFingerprintProtocolAndForgedPendingNeverRegister() {
        val f = fixture(); val pending = f.publish(); val store = f.store()
        rejects { store.register(f.attempt, pending) }
        f.reserve()
        rejects { store.register(f.attempt.copy(fingerprint = "b".repeat(64)), pending) }
        rejects { store.register(f.attempt.copy(protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART), pending) }
        val bad = listOf(
            pending.copy(bytes = pending.bytes + 1), pending.copy(sha256 = "0".repeat(64)),
            pending.copy(width = 2), pending.copy(height = 2), pending.copy(durationMs = 1),
            pending.copy(kind = GenerationKind.VIDEO), pending.copy(mimeType = "image/jpeg"),
            pending.copy(outputIndex = 1), pending.copy(identity = f.binding.identity("foreign")),
            pending.copy(receipt = GenerationAssetReceipt("not-an-asset")),
            pending.copy(owner = GenerationAssetOwner("foreign-account", f.binding)))
        bad.forEach { rejects { store.register(f.attempt, it) } }
        assertEquals(0, f.count("generation_outputs"))
        store.register(f.attempt, pending)
        rejects { store.find(f.attempt.copy(fingerprint = "b".repeat(64)), 0) }
        rejects { store.open(f.attempt.copy(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART), 0) }
        assertEquals(1, f.count("generation_outputs"))
    }

    fun testRealOwnerFieldsAndAuthoritativeHistoryAreCheckedEvenForExistingRows() {
        val f = fixture(withHistory = true); f.reserve(); val pending = f.publish(); val store = f.store()
        // A stale legacy projection cannot veto the authoritative real tool.
        f.change("blocks", "[]")
        val registered = store.register(f.attempt, pending)
        f.change("blocks", f.legacy)
        listOf("conversation_id" to f.binding.conversationId, "run_id" to f.binding.runId,
            "role" to "assistant").forEach { (column, original) ->
            f.change(column, "foreign")
            rejects { store.register(f.attempt, pending) }; rejects { store.find(f.attempt, 0) }
            f.change(column, original)
        }
        val invalidHistories = listOf(
            f.history.replace(f.binding.toolName, "foreign"),
            f.history.replace(f.binding.toolCallId, "foreign"),
            f.history.replace(f.binding.assistantMessageId, UUID.randomUUID().toString()),
            "[" + f.history.removePrefix("[").removeSuffix("]") + "," +
                f.history.removePrefix("[").removeSuffix("]") + "]", "malformed")
        invalidHistories.forEach {
            f.change("history_segments", it)
            rejects { store.register(f.attempt, pending) }; rejects { store.open(f.attempt, 0) }
        }
        f.change("history_segments", f.history)
        assertEquals(registered, store.find(f.attempt, 0))
        f.db.delete("conversations", "id=?", arrayOf(f.binding.conversationId))
        rejects { store.find(f.attempt, 0) }; rejects { store.open(f.attempt, 0) }
        assertEquals(1, f.count("generation_attempts")); assertEquals(1, f.count("generation_outputs"))
    }

    fun testLegacyCallAndForeignAccountAssistantCallNameCannotAuthorize() {
        val f = fixture(); f.reserve(); val pending = f.publish(); f.store().register(f.attempt, pending)
        f.change("blocks", f.legacy.replace(f.binding.toolName, "foreign"))
        rejects { f.store().find(f.attempt, 0) }
        f.change("blocks", f.legacy)
        val b = f.binding
        val foreignBindings = listOf(
            GenerationAttemptBinding("foreign", b.assistantMessageId, b.runId, b.toolCallId, b.toolName),
            GenerationAttemptBinding(b.conversationId, "foreign", b.runId, b.toolCallId, b.toolName),
            GenerationAttemptBinding(b.conversationId, b.assistantMessageId, "foreign", b.toolCallId, b.toolName),
            GenerationAttemptBinding(b.conversationId, b.assistantMessageId, b.runId, "foreign", b.toolName),
            GenerationAttemptBinding(b.conversationId, b.assistantMessageId, b.runId, b.toolCallId, "foreign"))
        foreignBindings.forEach { binding ->
            val foreign = f.store(assetOwner = GenerationAssetOwner(f.owner.accountScope, binding))
            val attempt = f.attempt.copy(identity = binding.identity("item1"))
            rejects { foreign.find(attempt, 0) }; rejects { foreign.register(attempt, pending) }
        }
        val otherAccount = f.store(assetOwner = GenerationAssetOwner("other-account", b))
        rejects { otherAccount.find(f.attempt, 0) }; rejects { otherAccount.open(f.attempt, 0) }
        rejects { otherAccount.register(f.attempt, pending) }
    }

    fun testAllStoredFieldsMustMatchAndFileCorruptionCannotOpen() {
        val f = fixture(); f.reserve(); val pending = f.publish(); val store = f.store()
        val registered = store.register(f.attempt, pending)
        // Simulate host corruption; an idempotent call must not overwrite even a single field.
        val changes = listOf("fingerprint" to "b".repeat(64), "protocol" to "OPENAI_IMAGE_EDIT_MULTIPART",
            "account_scope" to "foreign", "conversation_id" to "foreign", "assistant_id" to "foreign",
            "run_id" to "foreign", "tool_call_id" to "foreign", "tool_name" to "foreign",
            "receipt" to ("genasset-v1-" + "0".repeat(64) + "-" + pending.sha256),
            "kind" to "VIDEO", "mime_type" to "image/jpeg", "byte_count" to "999", "sha256" to "0".repeat(64),
            "width" to "2", "height" to "2", "duration_ms" to "1")
        changes.forEach { (column, changed) ->
            val original = f.db.rawQuery("SELECT $column FROM generation_outputs", null).use {
                check(it.moveToFirst()); it.getString(0)
            }
            try {
                f.db.execSQL("UPDATE generation_outputs SET $column=?", arrayOf(changed))
                rejects { store.register(f.attempt, pending) }
            } finally { f.db.execSQL("UPDATE generation_outputs SET $column=?", arrayOf(original)) }
        }
        assertEquals(registered, store.find(f.attempt, 0))
        File(f.root, "media").walkTopDown().single { it.name == "original" }.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(registered, store.find(f.attempt, 0)) // Metadata lookup makes no file-validity claim.
        rejects { store.open(f.attempt, 0) }; rejects { store.register(f.attempt, pending) }
    }

    // SQL literals deliberately bypass ContentValues' Kotlin numeric types.
    private fun invalidNumericSql(byteCount: Long): List<Pair<String, String>> =
        listOf("output_index", "byte_count", "width", "height", "duration_ms").flatMap { column ->
            listOf("NULL", "'not-an-integer'", "X'31'", "-1", "0.5", "9223372036854775808")
                .map { column to it }
        } + listOf("output_index" to "32", "output_index" to "4294967297",
            "byte_count" to "0", "byte_count" to "$byteCount.5",
            "width" to "0", "width" to "2147483648", "width" to "4294967297", "width" to "1.5",
            "height" to "0", "height" to "2147483648", "height" to "4294967297", "height" to "1.5")

    fun testSchemaRejectsInvalidStorageClassesAndNumericRanges() {
        val f = fixture(); f.reserve(); val pending = f.publish(); val store = f.store()
        val registered = store.register(f.attempt, pending)
        invalidNumericSql(pending.bytes).forEach { (column, literal) ->
            var rejected = false
            try { f.db.execSQL("UPDATE generation_outputs SET $column=$literal") }
            catch (_: SQLiteConstraintException) { rejected = true }
            assertTrue("Schema must reject $column=$literal", rejected)
            assertEquals(registered, store.find(f.attempt, 0))
        }
        assertEquals(registered, store.register(f.attempt, pending))
        assertEquals(1, f.count("generation_outputs")); assertEquals(1, f.count("generation_attempts"))
    }

    fun testExistingIntegerOverflowAndRealRowsFailClosedWithoutRepair() {
        val f = fixture(); f.reserve(); val pending = f.publish(); val store = f.store()
        val registered = store.register(f.attempt, pending)
        val corruptions = invalidNumericSql(pending.bytes).filter { it.first != "output_index" && it.second != "NULL" } +
            ("duration_ms" to "1") // INTEGER, but violates the image duration contract.
        corruptions.forEach { (column, literal) ->
            val original = f.db.rawQuery("SELECT $column FROM generation_outputs", null).use {
                check(it.moveToFirst()); it.getLong(0)
            }
            try {
                // Simulate rows written by an older/lax schema, then re-enable constraints before reads.
                f.db.execSQL("PRAGMA ignore_check_constraints=ON")
                try { f.db.execSQL("UPDATE generation_outputs SET $column=$literal") }
                finally { f.db.execSQL("PRAGMA ignore_check_constraints=OFF") }
                val corrupt = f.db.rawQuery("SELECT typeof($column),quote($column) FROM generation_outputs", null).use {
                    check(it.moveToFirst()); it.getString(0) to it.getString(1)
                }
                if (literal == "4294967297") {
                    f.db.rawQuery("SELECT $column FROM generation_outputs", null).use {
                        check(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_INTEGER, it.getType(0))
                        assertEquals(4294967297L, it.getLong(0))
                    }
                }
                if (literal == "1.5" || literal == "0.5" || literal == "${pending.bytes}.5") {
                    assertEquals("real", corrupt.first)
                }
                rejects { store.find(f.attempt, 0) }
                rejects { store.open(f.attempt, 0) }
                rejects { store.register(f.attempt, pending) }
                rejects { store.register(f.attempt, pending.receipt) }
                f.db.rawQuery("SELECT typeof($column),quote($column) FROM generation_outputs", null).use {
                    check(it.moveToFirst()); assertEquals(corrupt, it.getString(0) to it.getString(1))
                }
                assertEquals(1, f.count("generation_outputs")); assertEquals(1, f.count("generation_attempts"))
                assertEquals(pending, f.assets().pending(pending.receipt))
            } finally { f.db.execSQL("UPDATE generation_outputs SET $column=?", arrayOf(original)) }
            assertEquals(registered, store.find(f.attempt, 0))
        }
    }

    fun testLegacyRealIndexCannotHideBehindRequestedIntegerIndex() {
        val f = fixture(); f.reserve(); val pending = f.publish(); val store = f.store()
        store.register(f.attempt, pending)
        // An old/incompatible table may preserve 0.0 as REAL, yet WHERE output_index=0 still matches.
        // Copy all other declarations verbatim so this case specifically exercises the stored index.
        val columns = f.db.rawQuery("PRAGMA table_info(generation_outputs)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    add("$name ${if (name == "output_index") "REAL" else cursor.getString(2)}")
                }
            }
        }
        f.db.execSQL("CREATE TABLE legacy_outputs(${columns.joinToString(",")})")
        f.db.execSQL("INSERT INTO legacy_outputs SELECT * FROM generation_outputs")
        f.db.execSQL("DROP TABLE generation_outputs")
        f.db.execSQL("ALTER TABLE legacy_outputs RENAME TO generation_outputs")
        f.db.rawQuery("SELECT output_index FROM generation_outputs WHERE output_index=?", arrayOf("0")).use {
            assertTrue(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_FLOAT, it.getType(0))
            assertEquals(0.0, it.getDouble(0), 0.0)
        }
        rejects { store.find(f.attempt, 0) }; rejects { store.open(f.attempt, 0) }
        rejects { store.register(f.attempt, pending) }; rejects { store.register(f.attempt, pending.receipt) }
        assertEquals(1, f.count("generation_outputs")); assertEquals(1, f.count("generation_attempts"))
        assertEquals(pending, f.assets().pending(pending.receipt))
    }

    fun testNumericAfterInsertTriggersFailAndRollBackEvenWithChecksBypassed() {
        val f = fixture(); f.reserve(); val pending = f.publish(); val store = f.store()
        val corruptions = listOf("width" to "4294967297", "height" to "4294967297",
            "width" to "1.5", "height" to "1.5", "byte_count" to "${pending.bytes}.5",
            "duration_ms" to "0.5")
        listOf(false, true).forEach { bypassChecks ->
            f.db.execSQL("PRAGMA ignore_check_constraints=${if (bypassChecks) "ON" else "OFF"}")
            try {
                corruptions.forEach { (column, literal) ->
                    f.db.execSQL("CREATE TRIGGER corrupt_numeric AFTER INSERT ON generation_outputs " +
                        "BEGIN UPDATE generation_outputs SET $column=$literal; END")
                    try { rejects { store.register(f.attempt, pending) } }
                    finally { f.db.execSQL("DROP TRIGGER corrupt_numeric") }
                    assertEquals(0, f.count("generation_outputs")); assertEquals(1, f.count("generation_attempts"))
                    assertNull(store.find(f.attempt, 0))
                    assertEquals(pending, f.assets().pending(pending.receipt))
                }
            } finally { f.db.execSQL("PRAGMA ignore_check_constraints=OFF") }
        }
        assertEquals(pending.receipt, store.register(f.attempt, pending).receipt)
        assertTrue(png.contentEquals(store.open(f.attempt, 0)!!.use { it.readBytes() }))
    }

    fun testASecondGenuineAssetForTheSameSlotCannotReplaceRegisteredReceipt() {
        val f = fixture(); f.reserve(); val original = f.publish()
        val registered = f.store().register(f.attempt, original)
        // Add a valid ancillary PNG tEXt chunk before IEND: same pixels, different original digest.
        val body = "fixture\u0000alternate".toByteArray(Charsets.US_ASCII)
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val crc = java.util.zip.CRC32().apply { update(type); update(body) }
        val chunk = java.nio.ByteBuffer.allocate(body.size + 12).putInt(body.size).put(type).put(body)
            .putInt(crc.value.toInt()).array()
        val originalBytes = png
        val alternateBytes = originalBytes.copyOfRange(0, originalBytes.size - 12) + chunk +
            originalBytes.copyOfRange(originalBytes.size - 12, originalBytes.size)
        val alternate = MessageGenerationAssets(File(f.root, "alternate-media"),
            GenerationAssetFence(f.owner) { check(!f.db.inTransaction()); true })
        val info = alternate.inspect(GenerationKind.IMAGE, alternateBytes, "image/png")
        val receipt = alternate.commit(f.attempt.identity, 0, GenerationKind.IMAGE, alternateBytes, info)
        assertFalse(receipt == original.receipt)
        rejects { f.store(media = alternate).register(f.attempt, receipt) }
        assertEquals(registered, f.store().find(f.attempt, 0))
        assertEquals(receipt, alternate.pending(receipt).receipt)
        assertEquals(1, f.count("generation_outputs"))
    }

    fun testNestedTransactionRejectedBeforeFenceAndWeakDurabilityRejected() {
        val f = fixture(); f.reserve(); val pending = f.publish(); val store = f.store()
        f.admissions.set(0)
        f.db.beginTransaction()
        try {
            rejects { store.register(f.attempt, pending) }; rejects { store.register(f.attempt, pending.receipt) }
            rejects { store.find(f.attempt, 0) }; rejects { store.open(f.attempt, 0) }
            assertEquals(0, f.admissions.get())
        } finally { f.db.endTransaction() }
        f.db.execSQL("PRAGMA synchronous=NORMAL")
        rejects { store.register(f.attempt, pending) }
        f.db.execSQL("PRAGMA synchronous=FULL")
        assertEquals(0, f.count("generation_outputs"))
        store.register(f.attempt, pending)
    }

    fun testIgnoredAbortedMutatingTriggersAndCommitFailureNeverReportSuccess() {
        val f = fixture(); f.reserve(); val pending = f.publish(); val store = f.store()
        val triggers = listOf(
            "BEFORE INSERT ON generation_outputs BEGIN SELECT RAISE(IGNORE); END",
            "BEFORE INSERT ON generation_outputs BEGIN SELECT RAISE(ABORT,'private'); END",
            "AFTER INSERT ON generation_outputs BEGIN UPDATE generation_outputs SET width=2; END",
            "AFTER INSERT ON generation_outputs BEGIN UPDATE messages SET role='user'; END",
            "AFTER INSERT ON generation_outputs BEGIN UPDATE generation_attempts SET fingerprint='private'; END")
        triggers.forEach { body ->
            f.db.execSQL("CREATE TRIGGER fail_output $body")
            rejects { store.register(f.attempt, pending) }
            f.db.execSQL("DROP TRIGGER fail_output")
            assertEquals(0, f.count("generation_outputs"))
        }
        f.db.execSQL("CREATE TABLE commit_guard(owner TEXT REFERENCES conversations(id) DEFERRABLE INITIALLY DEFERRED)")
        f.db.execSQL("CREATE TRIGGER fail_output AFTER INSERT ON generation_outputs BEGIN INSERT INTO commit_guard VALUES('missing'); END")
        rejects { store.register(f.attempt, pending) }
        f.db.execSQL("DROP TRIGGER fail_output")
        assertEquals(0, f.count("generation_outputs")); assertEquals(1, f.count("generation_attempts"))
        assertEquals(pending, f.assets().pending(pending.receipt)) // Files were NOT cleaned up on DB failure.
        assertEquals(pending.receipt, store.register(f.attempt, pending).receipt)
    }

    fun testIndependentHelpersRaceToOneIdempotentRow() {
        val f = fixture(); f.reserve(); val pending = f.publish()
        val second = helper(File(f.root, "owner.sqlite")); second.writableDatabase
        val gate = CountDownLatch(1); val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable>()
        val results = listOf(AtomicReference<GenerationOutputReference>(), AtomicReference<GenerationOutputReference>())
        listOf(f.store(), f.store(second)).forEachIndexed { index, store ->
            Thread {
                try { check(gate.await(5, TimeUnit.SECONDS)); results[index].set(store.register(f.attempt, pending)) }
                catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
            }.start()
        }
        gate.countDown(); assertTrue(done.await(10, TimeUnit.SECONDS)); failure.get()?.let { throw it }
        assertNotNull(results[0].get()); assertEquals(results[0].get(), results[1].get())
        assertEquals(1, f.count("generation_outputs")); assertEquals(1, f.count("generation_attempts"))
    }
}
