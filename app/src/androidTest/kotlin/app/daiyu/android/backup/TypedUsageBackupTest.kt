package app.daiyu.android.backup

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.context.DatabaseTransactions
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.model.usage.SQLiteAdditionalUsageStore
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.RecordedUsageReader
import app.daiyu.android.domain.usage.UsageOrigin
import app.daiyu.android.testContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Real v19 helper and production additional-usage admission/observation; no network or checkpoint copies. */
@Suppress("DEPRECATION")
class TypedUsageBackupTest : InstrumentationTestCase() {
    private val fixtures = mutableListOf<Fixture>()
    private inner class Fixture(val context: Context, var db: ConversationDatabase) {
        fun service() = NeutralBackupService(context, db)
        fun count(table: String) = db.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { check(it.moveToFirst()); it.getInt(0) }
        fun reopen() { db.close(); db = ConversationDatabase(context) }
        fun tx(action: (android.database.sqlite.SQLiteDatabase) -> Unit) = DatabaseTransactions { db.writableDatabase }.run(action)
    }
    private fun fresh(): Fixture {
        val base = testContext(instrumentation.targetContext, "typed-backup-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "typed-backup-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = files }
        return Fixture(context, ConversationDatabase(context)).also { fixtures.add(it); assertEquals(19, it.db.writableDatabase.version) }
    }
    override fun tearDown() {
        try { fixtures.forEach { it.db.close(); it.context.deleteDatabase("conversations.db"); it.context.filesDir.deleteRecursively() } }
        finally { super.tearDown() }
    }
    private fun parent(f: Fixture): Message {
        val conversation = f.db.create("default").id; val run = RunId("run-${UUID.randomUUID()}")
        val assistant = Message(MessageId("assistant-${UUID.randomUUID()}"), conversation, run, "assistant",
            listOf(MessageBlock.Text("t", "answer")), RunStatus.RUNNING)
        f.db.send(Message(MessageId("user-${UUID.randomUUID()}"), conversation, run, "user",
            listOf(MessageBlock.Text("t", "question")), RunStatus.COMPLETED), assistant)
        f.db.save(assistant.copy(status = RunStatus.COMPLETED))
        return assistant.copy(status = RunStatus.COMPLETED)
    }
    private fun additional(f: Fixture, parent: Message, id: String, source: UsageRequestSource,
        usage: ModelEvent.Usage?, terminal: Boolean = true) {
        val request = RequestCheckpoint(id, "attempt-$id", "owner-$id", RequestOperation.NEXT)
        val manual = source == UsageRequestSource.MANUAL_SUMMARY
        f.tx { db ->
            val run = if (manual) null else parent.runId.value
            val message = if (manual) null else parent.id.value
            val operation = if (manual) "operation-$id" else null
            SQLiteAdditionalUsageStore.admit(db, parent.conversationId.value, run, message, request, source, "process-$id", operation)
            if (usage != null || terminal) SQLiteAdditionalUsageStore.observe(db, parent.conversationId.value, run, message,
                request, source, "process-$id", usage, terminal, operation, if (manual && terminal) "SUCCEEDED" else null)
        }
    }
    private fun children(f: Fixture, p: Message) {
        f.tx { db ->
            // Physical child history, not a child runnable runtime checkpoint or lease.
            BackupHistoryRows.insert(db, "child_tasks", JSONObject().put("task_id", "child")
                .put("conversation_id", p.conversationId.value).put("parent_run_id", p.runId.value)
                .put("parent_message_id", p.id.value).put("parent_owner_id", "parent-owner")
                .put("task_group_id", "group").put("generation", 1).put("task_index", 0)
                .put("assistant_message_id", "child-assistant").put("profile_id", "profile").put("prompt", "task")
                .put("status", "RUNNING").put("text", "partial").put("detail", JSONObject.NULL)
                .put("control_revision", 0).put("source_task_id", JSONObject.NULL).put("command_kind", JSONObject.NULL))
            listOf("MAIN", "SUMMARY").forEach { source ->
                BackupHistoryRows.insert(db, "child_request_usage", JSONObject().put("task_id", "child")
                    .put("request_id", "child-$source").put("request_owner", "child-owner").put("attempt_id", "child-attempt-$source")
                    .put("operation", "NEXT").put("checkpoint_revision", 1).put("usage_source", source)
                    .put("input_tokens", if (source == "MAIN") 0 else JSONObject.NULL)
                    .put("output_tokens", JSONObject.NULL).put("cached_tokens", JSONObject.NULL))
            }
        }
    }
    private fun seed(f: Fixture): Message {
        val p = parent(f)
        additional(f, p, "title-zero", UsageRequestSource.TITLE, ModelEvent.Usage(0, 0, 0))
        additional(f, p, "vision-null", UsageRequestSource.AUXILIARY_VISION, ModelEvent.Usage(null, null, null))
        additional(f, p, "manual-success", UsageRequestSource.MANUAL_SUMMARY, ModelEvent.Usage(7, 2, null))
        additional(f, p, "manual-open", UsageRequestSource.MANUAL_SUMMARY, null, false)
        additional(f, p, "title-open", UsageRequestSource.TITLE, ModelEvent.Usage(1, null, null), false)
        additional(f, p, "vision-open", UsageRequestSource.AUXILIARY_VISION, null, false)
        children(f, p)
        return p
    }
    private fun payload(text: String) = JSONObject(JSONObject(text).getString("payload"))
    private fun usage(text: String) = payload(text).getJSONObject("tables").getJSONArray("context_usage")
    private fun row(text: String, request: String): JSONObject = (0 until usage(text).length()).map { usage(text).getJSONObject(it) }
        .single { it.getString("request_id") == request }
    private fun mutate(text: String, edit: (JSONObject) -> Unit): String {
        val envelope = JSONObject(text); val data = payload(text); edit(data)
        val raw = data.toString(); return envelope.put("payload", raw).put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))).toString()
    }
    private fun rawPayload(text: String, edit: (String) -> String): String {
        val envelope = JSONObject(text); val raw = edit(envelope.getString("payload"))
        return envelope.put("payload", raw).put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))).toString()
    }
    private fun editUsage(text: String, request: String, edit: (JSONObject) -> Unit) = mutate(text) { data ->
        val rows = data.getJSONObject("tables").getJSONArray("context_usage")
        repeat(rows.length()) { if (rows.getJSONObject(it).getString("request_id") == request) edit(rows.getJSONObject(it)) }
    }
    private fun rejects(action: () -> Unit) { try { action() } catch (_: Exception) { return }; fail("must fail closed") }
    private fun physical(f: Fixture) = JSONArray(BackupHistoryRows.read(f.db.readableDatabase, "context_usage", BackupUsageCodec.columns, order = "request_id")).toString()

    fun testTypedManualAndChildRoundTripIsClosedStableAndDoesNotMutateSource() {
        val source = fresh(); val p = seed(source); val original = physical(source)
        val exported = source.service().exportPackage()
        assertEquals(3, JSONObject(exported).getInt("version")); assertEquals(original, physical(source))
        assertEquals(exported, source.service().exportPackage())
        assertEquals(2, payload(exported).getJSONObject("child_task_history").getInt("version"))
        assertEquals(0L, row(exported, "title-zero").getLong("input_tokens"))
        assertEquals(1L, row(exported, "vision-null").getLong("has_usage"))
        assertTrue(row(exported, "vision-null").isNull("input_tokens"))
        for (request in listOf("manual-open", "title-open", "vision-open")) {
            assertEquals("TERMINAL", row(exported, request).getString("usage_state"))
            assertEquals("UNKNOWN", row(exported, request).getString("terminal_reason"))
            assertEquals("owner-$request", row(exported, request).getString("request_owner"))
        }
        assertTrue(row(exported, "manual-success").isNull("run_id")); assertTrue(row(exported, "manual-success").isNull("parent_message_id"))
        val target = fresh(); assertFalse(target.service().importPackage(exported).alreadyImported)
        target.reopen(); assertTrue(target.service().importPackage(exported).alreadyImported)
        assertEquals(0, target.count("runtime_checkpoints")); assertEquals(0, target.count("runtime_preparations"))
        assertEquals(usage(exported).toString(), usage(target.service().exportPackage()).toString())
        assertEquals(payload(exported).getJSONObject("child_task_history").toString(),
            payload(target.service().exportPackage()).getJSONObject("child_task_history").toString())
        assertEquals("UNKNOWN", target.db.readableDatabase.rawQuery("SELECT status FROM child_tasks", null).use { check(it.moveToFirst()); it.getString(0) })
        assertEquals(usage(exported).toString(), usage(source.service().exportConversation(p.conversationId)).toString())
        assertEquals(original, physical(source))
        target.tx { db ->
            val stats = RecordedUsageReader.readAttributed(db, p.conversationId)
            assertEquals(0, stats.childCoverage.unreadableTasks)
            val children = stats.requests.filter { it.origin is UsageOrigin.Child }
            assertEquals(setOf(UsageRequestSource.MAIN, UsageRequestSource.SUMMARY), children.map { it.source }.toSet())
            assertEquals(2L, stats.summary.child.requestSnapshotCount)
            assertEquals(0L, stats.summary.child.input.reportedTokens)
            assertEquals(1L, stats.summary.child.input.unknownRequestCount)
            assertEquals(0, stats.parentCoverage.unreadableTitleRequests)
            assertEquals(0, stats.parentCoverage.unreadableAuxiliaryRequests)
            assertEquals(0, stats.parentCoverage.unreadableManualSummaryRequests)
            assertEquals(7L, stats.summary.conversationPreparation.input.reportedTokens)
        }
    }

    fun testV1AndV2KeepLegacyAttributionAndChildV1DefaultsToMain() {
        for (version in listOf(1, 2)) {
            val source = fresh(); val p = parent(source)
            source.tx { db -> BackupHistoryRows.insert(db, "context_usage", JSONObject().put("request_id", "legacy")
                .put("run_id", p.runId.value).put("branch_id", p.conversationId.value).put("input_tokens", 0)
                .put("output_tokens", JSONObject.NULL).put("cached_tokens", JSONObject.NULL).put("reasoning_tokens", JSONObject.NULL)) }
            if (version == 2) children(source, p)
            val current = source.service().exportPackage()
            val oldPayload = mutate(current) { data ->
                val rows = data.getJSONObject("tables").getJSONArray("context_usage")
                repeat(rows.length()) { val r = rows.getJSONObject(it); r.keys().asSequence().toList().filter { it !in BackupUsageCodec.legacyColumns }.forEach { r.remove(it) } }
                if (version == 1) {
                    data.remove("generation_history"); data.remove("child_task_history")
                    listOf("generation_outputs", "generation_attempts", "child_tasks", "child_commands", "child_requests").forEach { data.getJSONObject("inventory").remove(it) }
                } else {
                    val child = data.getJSONObject("child_task_history").put("version", 1)
                    val childUsage = child.getJSONArray("usage")
                    repeat(childUsage.length()) { childUsage.getJSONObject(it).remove("usage_source") }
                }
            }
            val old = JSONObject(oldPayload).put("version", version).toString()
            val target = fresh(); target.service().preflight(old); target.service().importPackage(old)
            assertTrue(target.service().importPackage(old).alreadyImported)
            val reexported = target.service().exportPackage()
            assertEquals("LEGACY", row(reexported, "legacy").getString("usage_source"))
            assertTrue(row(reexported, "legacy").isNull("request_owner"))
            if (version == 2) {
                val childUsage = payload(reexported).getJSONObject("child_task_history").getJSONArray("usage")
                assertEquals(2, childUsage.length()); repeat(childUsage.length()) { assertEquals("MAIN", childUsage.getJSONObject(it).getString("usage_source")) }
            }
        }
    }

    fun testTamperingAndInvalidStorageClassesAreRejectedWithoutPublishing() {
        val source = fresh(); val p = seed(source); val foreign = parent(source); val text = source.service().exportPackage()
        val user = source.db.readableDatabase.rawQuery("SELECT id FROM messages WHERE conversation_id=? AND role='user'", arrayOf(p.conversationId.value))
            .use { check(it.moveToFirst()); it.getString(0) }
        val invalid = listOf(
            editUsage(text, "title-zero") { it.put("parent_message_id", "absent") },
            editUsage(text, "title-zero") { it.put("parent_message_id", user) },
            editUsage(text, "title-zero") { it.put("parent_message_id", foreign.id.value).put("run_id", foreign.runId.value) },
            editUsage(text, "title-zero") { it.put("run_id", "wrong-run") },
            editUsage(text, "manual-success") { it.put("run_id", row(text, "title-zero").getString("run_id")) },
            editUsage(text, "manual-success") { it.put("parent_message_id", row(text, "title-zero").getString("parent_message_id")) },
            editUsage(text, "manual-success") { it.put("operation_id", JSONObject.NULL) },
            editUsage(text, "manual-open") { it.put("operation_id", "operation-manual-success") },
            editUsage(text, "manual-success") { it.put("usage_source", "MAIN") },
            editUsage(text, "manual-open") { it.put("input_tokens", 0) },
            editUsage(text, "title-zero") { it.put("has_usage", 4294967297L) },
            editUsage(text, "title-zero") { it.put("has_usage", 0.5) },
            rawPayload(text) { it.replace("\"has_usage\":1", "\"has_usage\":1.0") },
            rawPayload(text) { it.replace("\"checkpoint_revision\":1", "\"checkpoint_revision\":1.0") },
            editUsage(text, "title-zero") { it.put("usage_revision", "2") },
            editUsage(text, "title-zero") { it.put("usage_revision", 0) },
            editUsage(text, "title-zero") { it.put("input_tokens", -1) },
            editUsage(text, "title-zero") { it.put("input_tokens", 1).put("cached_tokens", 2) },
            editUsage(text, "title-zero") { it.put("input_tokens", "0") },
            editUsage(text, "title-zero") { it.put("usage_state", "ADMITTED") },
            mutate(text) { it.getJSONObject("child_task_history").getJSONArray("usage").getJSONObject(0).put("usage_source", "TITLE") },
            mutate(text) { it.getJSONObject("child_task_history").getJSONArray("usage").getJSONObject(0).put("input_tokens", 1).put("cached_tokens", 2) },
            mutate(text) { it.getJSONObject("child_task_history").getJSONArray("usage").getJSONObject(0).put("checkpoint_revision", 1.5) })
        val target = fresh(); val before = target.db.exportBackup()
        invalid.forEach { bad ->
            rejects { target.service().preflight(bad) }; rejects { target.service().importPackage(bad) }
            assertEquals(before, target.db.exportBackup()); assertEquals(0, target.count("context_usage")); assertEquals(0, target.count("neutral_imports"))
        }
        source.db.writableDatabase.execSQL("UPDATE context_usage SET input_tokens=0.5 WHERE request_id='title-zero'")
        rejects { source.service().exportPackage() }
    }

    fun testDuplicateChecksEveryTypedColumnAndRollsBackLateReceiptCorruption() {
        val source = fresh(); seed(source); val text = source.service().exportPackage()
        val target = fresh(); target.service().importPackage(text)
        // Includes NULL versus a literal string and same-affinity non-integer values.
        val corruptions = linkedMapOf("branch_id" to "'different'", "usage_source" to "'LEGACY'", "request_owner" to "'different'", "attempt_id" to "'different'",
            "parent_message_id" to "NULL", "run_id" to "NULL", "operation_id" to "'null'", "has_usage" to "0",
            "usage_revision" to "2.5", "usage_state" to "'ADMITTED'", "terminal_reason" to "'UNKNOWN'", "process_owner" to "'different'",
            "input_tokens" to "NULL", "output_tokens" to "0.5", "cached_tokens" to "NULL", "reasoning_tokens" to "0")
        val original = row(text, "title-zero")
        corruptions.forEach { (column, value) ->
            target.db.writableDatabase.execSQL("UPDATE context_usage SET $column=$value WHERE request_id='title-zero'")
            rejects { target.service().importPackage(text) }
            val saved = original.get(column)
            target.db.writableDatabase.execSQL("UPDATE context_usage SET $column=? WHERE request_id='title-zero'", arrayOf(if (saved == JSONObject.NULL) null else saved))
            assertTrue(target.service().importPackage(text).alreadyImported)
        }
        target.db.writableDatabase.execSQL("UPDATE context_usage SET request_id='renamed' WHERE request_id='title-zero'")
        rejects { target.service().importPackage(text) }
        target.db.writableDatabase.execSQL("UPDATE context_usage SET request_id='title-zero' WHERE request_id='renamed'")
        assertTrue(target.service().importPackage(text).alreadyImported)
        target.db.writableDatabase.execSQL("UPDATE child_request_usage SET usage_source='MAIN' WHERE request_id='child-SUMMARY'")
        rejects { target.service().importPackage(text) }
        val late = fresh(); val before = late.db.exportBackup()
        late.db.writableDatabase.execSQL("""CREATE TRIGGER corrupt_typed_receipt AFTER INSERT ON neutral_imports
            BEGIN UPDATE context_usage SET attempt_id='trigger-corrupted' WHERE request_id='manual-success'; END""")
        rejects { late.service().importPackage(text) }
        assertEquals(before, late.db.exportBackup()); assertEquals(0, late.count("neutral_imports"))
        assertEquals(0, late.count("context_usage")); assertEquals(0, late.count("child_tasks")); assertEquals(0, late.count("child_request_usage"))
        assertEquals(0, late.count("runtime_checkpoints"))
        late.db.writableDatabase.execSQL("DROP TRIGGER corrupt_typed_receipt")
        assertFalse(late.service().importPackage(text).alreadyImported)
        val childLate = fresh()
        childLate.db.writableDatabase.execSQL("""CREATE TRIGGER corrupt_child_usage AFTER INSERT ON neutral_imports
            BEGIN UPDATE child_request_usage SET usage_source='MAIN' WHERE request_id='child-SUMMARY'; END""")
        rejects { childLate.service().importPackage(text) }
        assertEquals(0, childLate.count("neutral_imports")); assertEquals(0, childLate.count("context_usage"))
        assertEquals(0, childLate.count("child_tasks")); assertEquals(0, childLate.count("runtime_preparations"))
    }
}
