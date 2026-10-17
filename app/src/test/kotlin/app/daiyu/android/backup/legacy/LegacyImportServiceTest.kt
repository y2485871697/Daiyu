package app.daiyu.android.backup.legacy

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.StrictMode
import app.daiyu.android.storage.ConversationDatabase
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyImportServiceTest {
    private lateinit var store: ConversationDatabase
    private lateinit var db: SQLiteDatabase
    private lateinit var service: LegacyImportService
    private lateinit var context: Context
    private val prefix = "li-" // The Robolectric dataDir already isolates each method.

    @Before fun setUp() {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getDatabasePath(name: String): File = super.getDatabasePath(prefix + name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                baseContext.openOrCreateDatabase(prefix + name, mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?): SQLiteDatabase =
                baseContext.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)
        }
        store = ConversationDatabase(context); db = store.writableDatabase; service = LegacyImportService(store)
        db.execSQL("INSERT INTO conversations(id,title,draft,updated) VALUES('existing','untouched','draft',123)")
        db.execSQL("INSERT INTO messages(id,conversation_id,run_id,role,blocks,status,protocol,created_at) VALUES('existing-message','existing','old-run','USER','[]','COMPLETE','OPENAI',456)")
    }
    @After fun tearDown() { store.close(); context.getDatabasePath("conversations.db").delete() }
    private fun input(bytes: ByteArray) = ByteArrayInputStream(bytes)
    private fun import(bytes: ByteArray): LegacyImportResult { val preview = service.preview(input(bytes)); return service.commit(input(bytes), preview.sha256) }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected atomic rejection") } catch (_: Exception) {} }
    private fun tables(): List<String> = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }
    private fun snapshot(names: List<String>): Map<String, List<String>> = names.associateWith { table ->
        require(table.matches(Regex("[A-Za-z0-9_]+")))
        db.rawQuery("SELECT * FROM $table", null).use { c -> buildList {
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { column -> when(c.getType(column)) {
                Cursor.FIELD_TYPE_NULL -> "NULL"
                Cursor.FIELD_TYPE_BLOB -> "BLOB:" + digest(c.getBlob(column))
                else -> "${c.getType(column)}:" + c.getString(column)
            } })
        }.sorted() }
    }
    @Test fun previewIsReadOnlyAndCopiesHaveFreshIdsAndCanReopenAcrossServiceInstances() {
        val bytes = LegacyFixtures.bytes(LegacyFixtures.single())
        val names = tables(); val before = snapshot(names)
        val preview = service.preview(input(bytes))
        assertEquals(names, tables()); assertEquals(before, snapshot(names)); assertTrue(service.histories().isEmpty())
        assertEquals(0, preview.report.nativeMessagesCreated); assertEquals(0, preview.report.runtimeRequestsCreated)
        val first = service.commit(input(bytes), preview.sha256); val second = service.commit(input(bytes), preview.sha256)
        assertNotEquals(first.importId, second.importId); assertNotEquals(first.conversationIds.single(), second.conversationIds.single())
        val reopened = LegacyImportService(store).reopen(first.conversationIds.single())
        val other = service.reopen(second.conversationIds.single())
        assertTrue(reopened.readOnly); assertEquals("source", reopened.summary.sourceId)
        assertNotEquals("ui-1", reopened.messages.single().id); assertNotEquals(reopened.messages.single().id, other.messages.single().id)
        assertEquals("user", reopened.messages.single().type); assertEquals(900L, reopened.messages.single().generatedAtMillis)
        assertNull(reopened.messages.single().createdAt); assertEquals(12L, reopened.summary.sourceCreatedAt)
        assertEquals(before, snapshot(names)) // every original runtime/context/action/usage table remains identical
    }
    @Test fun failedFirstInstallAndFailedSecondCopyRollbackOnTheExistingHelper() {
        val (doc, original) = LegacyFixtures.attached(); val bytes = LegacyFixtures.zip(doc, original)
        val names = tables(); val before = snapshot(names)
        val failing = LegacyImportService({ store.writableDatabase }) { throw IllegalStateException("Synthetic failure after all inserts") }
        val preview = failing.preview(input(bytes))
        rejects { failing.commit(input(bytes), preview.sha256) }
        assertEquals(names, tables()); assertEquals(before, snapshot(names)); assertTrue(service.histories().isEmpty())
        val accepted = service.commit(input(bytes), preview.sha256)
        val allNames = tables(); val acceptedState = snapshot(allNames)
        rejects { failing.commit(input(bytes), preview.sha256) }
        assertEquals(acceptedState, snapshot(allNames)); assertEquals(1, service.histories().size)
        assertArrayEquals(original.second, service.original(accepted.importId, original.first))
        assertEquals(before, snapshot(names))
    }
    @Test fun inputChangedTamperedOwnerOrInvalidCentralDirectoryNeverChangesDatabase() {
        val bytes = LegacyFixtures.bytes(LegacyFixtures.single()); val preview = service.preview(input(bytes))
        val names = tables(); val before = snapshot(names)
        val changed = LegacyFixtures.bytes(LegacyFixtures.single().put("exportedAt", 1001L))
        rejects { service.commit(input(changed), preview.sha256) }
        val wrongOwner = LegacyFixtures.single(); wrongOwner.getJSONArray("messages").getJSONObject(0).put("conversationId", "other")
        val invalid = LegacyFixtures.zip(wrongOwner)
        rejects { service.commit(input(invalid), digest(invalid)) }
        val truncated = LegacyFixtures.zip(LegacyFixtures.single()).dropLast(10).toByteArray()
        rejects { service.commit(input(truncated), digest(truncated)) }
        assertEquals(names, tables()); assertEquals(before, snapshot(names))
    }
    @Test fun noModelToolNetworkPermissionOrRuntimeResurrectionAndLiteralRawHistoryIsPreserved() {
        val doc = LegacyFixtures.single()
        val types = listOf("thinking", "tool", "tool_summary", "system_notice", "error_reconnect", "context_compacted", "question")
        doc.put("messages", JSONArray(types.mapIndexed { index, type -> LegacyFixtures.message("m-$index", type = type, index = index) }))
        val history = "[{\"role\":\"assistant\",\"toolCallsJson\":\"[{\\\"id\\\":\\\"executed\\\"}]\"},{\"role\":\"tool\",\"toolCallId\":\"executed\",\"content\":\"DONE\"}]"
        doc.getJSONObject("conversation").put("historyJson", history).put("appliedRuntimeRunIdsJson", "[\"executed-run\"]")
        doc.getJSONArray("messages").getJSONObject(0).put("imagesJson", "[\"https://invalid.example/NEVER-FETCH\"]")
        val names = tables(); val before = snapshot(names)
        val oldPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build())
        try {
            val accepted = import(LegacyFixtures.bytes(doc)); val reopened = service.reopen(accepted.conversationIds.single())
            assertEquals(types, reopened.messages.map { it.type }); assertTrue(reopened.modelHistoryJson.contains("executed"))
            assertTrue(reopened.compatibilityJson.contains("executed-run")); assertFalse(reopened.media.single().available)
            assertEquals(0, accepted.report.runtimeRequestsCreated); assertEquals(0, accepted.report.nativeMessagesCreated)
        } finally { StrictMode.setThreadPolicy(oldPolicy) }
        assertEquals(before, snapshot(names)) // no request, tool, checkpoint, accepted context, usage, permission or preference writes
    }
    @Test fun chunkedOriginalIsVerifiedOnReadAndUnreferencedV1OriginalRemainsDiscoverable() {
        val bytes = ByteArray(150000) { (it % 251).toByte() }
        val doc = LegacyFixtures.single(1).put("attachmentCount", 1)
        val entry = "attachments/imports/unreferenced.bin"
        val result = import(LegacyFixtures.zip(doc, entry to bytes))
        assertEquals(entry, service.originals(result.importId).single().entry)
        assertArrayEquals(bytes, service.original(result.importId, entry))
        db.execSQL("UPDATE legacy_r12_chunks SET data=zeroblob(length(data)) WHERE owner=? AND name=? AND part=0", arrayOf(result.importId, "asset:$entry"))
        rejects { service.original(result.importId, entry) }
    }
    @Test fun ignoredInsertAndLatePayloadMutationNeverReturnSuccess() {
        val bytes = LegacyFixtures.bytes(LegacyFixtures.single())
        val previous = import(bytes)
        val names = tables()
        val before = snapshot(names)
        db.execSQL("CREATE TRIGGER ignore_legacy_chunk BEFORE INSERT ON legacy_r12_chunks BEGIN SELECT RAISE(IGNORE); END")
        rejects { import(bytes) }
        assertEquals(before, snapshot(names))
        db.execSQL("DROP TRIGGER ignore_legacy_chunk")
        db.execSQL("CREATE TRIGGER alter_legacy_view AFTER INSERT ON legacy_r12_chunks WHEN NEW.name='view' BEGIN UPDATE legacy_r12_chunks SET data=X'00' WHERE owner=NEW.owner AND name=NEW.name AND part=NEW.part; END")
        rejects { import(bytes) }
        assertEquals(before, snapshot(names))
        assertEquals("source", service.reopen(previous.conversationIds.single()).summary.sourceId)
        db.execSQL("DROP TRIGGER alter_legacy_view")
        db.execSQL("CREATE TRIGGER alter_legacy_metadata AFTER INSERT ON legacy_r12_chunks WHEN NEW.name='view' BEGIN UPDATE legacy_r12_histories SET source_created=12.5 WHERE id=NEW.owner; END")
        rejects { import(bytes) }
        assertEquals(before, snapshot(names))
        db.execSQL("DROP TRIGGER alter_legacy_metadata")
        db.execSQL("CREATE TRIGGER alter_live AFTER INSERT ON legacy_r12_chunks BEGIN UPDATE conversations SET title='tampered'; END")
        rejects { import(bytes) }
        assertEquals(before, snapshot(names))
        db.execSQL("DROP TRIGGER alter_live")
        db.execSQL("CREATE TEMP TRIGGER alter_old AFTER INSERT ON main.legacy_r12_chunks BEGIN UPDATE legacy_r12_histories SET title='tampered'; END")
        rejects { import(bytes) }
        assertEquals(before, snapshot(names))
        assertEquals("source", service.reopen(previous.conversationIds.single()).summary.sourceId)
        db.execSQL("DROP TRIGGER alter_old")
        store.close()
        store = ConversationDatabase(context)
        db = store.writableDatabase
        assertEquals(before, snapshot(names))
    }
    @Test fun beforeCommitMutationIsReadBackAndRollsBackWithoutTriggers() {
        val (doc, original) = LegacyFixtures.attached()
        val bytes = LegacyFixtures.zip(doc, original)
        val previous = import(bytes)
        val names = tables()
        val before = snapshot(names)
        val mutations = listOf(
            "UPDATE legacy_r12_chunks SET data=X'00' WHERE name='view' AND owner NOT IN (SELECT id FROM legacy_r12_histories WHERE import_id='${previous.importId}')",
            "UPDATE legacy_r12_histories SET source_created=12.5 WHERE import_id<>'${previous.importId}'",
            "DELETE FROM legacy_r12_chunks WHERE owner IN (SELECT id FROM legacy_r12_histories WHERE import_id<>'${previous.importId}')",
            "UPDATE legacy_r12_assets SET byte_count=byte_count+1 WHERE import_id<>'${previous.importId}'",
        )
        mutations.forEach { sql ->
            var reached = false
            val corrupting = LegacyImportService({ store.writableDatabase }) {
                reached = true
                db.execSQL(sql)
                db.rawQuery("SELECT changes()", null).use { c ->
                    assertTrue(c.moveToFirst()); assertTrue(c.getLong(0) > 0)
                }
            }
            val preview = corrupting.preview(input(bytes))
            rejects { corrupting.commit(input(bytes), preview.sha256) }
            assertTrue(reached)
            assertEquals(before, snapshot(names))
        }
        store.close()
        store = ConversationDatabase(context); db = store.writableDatabase
        assertEquals(before, snapshot(names))
        assertArrayEquals(original.second, LegacyImportService(store).original(previous.importId, original.first))
    }
    @Test fun oversizedAndUnexpectedZipEntriesFailBeforeAnySchemaInstallation() {
        val names = tables(); val before = snapshot(names)
        val data = LegacyFixtures.zip(LegacyFixtures.single(), "unlisted.txt" to "synthetic".toByteArray())
        rejects { service.preview(input(data)) }; rejects { service.commit(input(data), digest(data)) }
        val oversized = ByteArray(LegacyArchive.TEXT_LIMIT + 1) { ' '.code.toByte() }
        rejects { service.preview(input(oversized)) }
        assertEquals(names, tables()); assertEquals(before, snapshot(names))
    }
}
