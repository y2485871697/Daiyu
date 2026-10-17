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
import app.daiyu.android.storage.RecordedUsageBudget
import app.daiyu.android.storage.RecordedUsageHistory
import app.daiyu.android.storage.RunCheckpointCodec
import app.daiyu.android.storage.SQLiteRuntimeStore
import app.daiyu.android.storage.SQLiteText
import app.daiyu.android.domain.usage.UsageOrigin
import app.daiyu.android.testContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Real v20 helper and production additional-usage admission/observation; no network or checkpoint copies. */
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
        return Fixture(context, ConversationDatabase(context)).also { fixtures.add(it); assertEquals(20, it.db.writableDatabase.version) }
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
                .put("status", "RUNNING").put("text", "").put("detail", JSONObject.NULL)
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

    private fun request(id: String) = RequestCheckpoint(id, "attempt-$id", "owner-$id", RequestOperation.NEXT)
    private fun checkpoint(f: Fixture, p: Message, reports: List<RequestUsageCheckpoint>, admitted: List<RequestCheckpoint>,
        fallback: RequestCheckpoint? = null, large: Boolean = false) {
        // Source-only executable fixture. The production decoder validates it; none of this payload is imported.
        val history = if (large) listOf(p.copy(blocks = listOf(MessageBlock.Text("private", "RUNTIME_PRIVATE_HISTORY".repeat(110_000))))) else emptyList()
        val saved = RunCheckpoint(p, history, ExecutionPhase.TERMINAL, owner = "checkpoint-owner", revision = 1,
            request = fallback, usage = reports, usageRequests = admitted)
        f.tx { db -> BackupHistoryRows.insert(db, "runtime_checkpoints", JSONObject().put("message_id", p.id.value)
            .put("conversation_id", p.conversationId.value).put("run_id", p.runId.value).put("process_owner", "runtime-process")
            .put("owner", saved.owner).put("revision", 1).put("phase", "TERMINAL").put("payload", RunCheckpointCodec.encode(saved))) }
    }
    private fun preparation(f: Fixture, p: Message, message: String, run: String, state: String, req: RequestCheckpoint,
        presence: Boolean, usage: ModelEvent.Usage? = null) {
        f.tx { db -> BackupHistoryRows.insert(db, "runtime_preparations", JSONObject().put("message_id", message)
            .put("conversation_id", p.conversationId.value).put("run_id", run).put("owner", "preparation-owner")
            .put("process_owner", "preparation-process").put("revision", 1).put("state", state)
            .put("request_id", req.requestId).put("request_owner", req.owner).put("attempt_id", req.attemptId)
            .put("has_usage", if (presence) 1 else 0).put("input_tokens", usage?.inputTokens ?: JSONObject.NULL)
            .put("output_tokens", usage?.outputTokens ?: JSONObject.NULL).put("cached_tokens", usage?.cachedTokens ?: JSONObject.NULL)) }
    }
    private fun checkpointPayloads(f: Fixture): List<String?> = f.db.readableDatabase.rawQuery("SELECT message_id FROM runtime_checkpoints ORDER BY message_id", null)
        .use { c -> buildList { while (c.moveToNext()) add(SQLiteText.read(f.db.readableDatabase, "runtime_checkpoints", "payload", "message_id", c.getString(0))) } }
    private fun runtime(text: String) = payload(text).getJSONObject("runtime_usage_history")
    private fun runtimeRows(text: String) = runtime(text).getJSONArray("requests")
    private fun runtimeRow(text: String, id: String) = (0 until runtimeRows(text).length()).map { runtimeRows(text).getJSONObject(it) }
        .single { it.getString("request_id") == id }
    private fun runtimeSeed(f: Fixture): Pair<Message, Message> {
        val p = parent(f); val q = parent(f)
        checkpoint(f, p, listOf(RequestUsageCheckpoint(request("main-zero"), ModelEvent.Usage(0, 0, 0)),
            RequestUsageCheckpoint(request("main-null"), ModelEvent.Usage(null, null, null)),
            RequestUsageCheckpoint(request("summary-handed"), ModelEvent.Usage(11, 3, null), UsageRequestSource.SUMMARY)),
            listOf(request("main-zero"), request("main-null"), request("main-missing")), large = true)
        checkpoint(f, q, emptyList(), emptyList(), request("open").copy(operation = RequestOperation.OPEN))
        preparation(f, p, p.id.value, p.runId.value, "HANDED", request("summary-handed"), true)
        preparation(f, q, q.id.value, q.runId.value, "HANDED", request("summary-missing"), false)
        preparation(f, p, "unsent-null", "unsent-run-null", "TERMINAL", request("summary-null"), true)
        preparation(f, p, "unsent-missing", "unsent-run-missing", "ADMITTED", request("summary-unsent"), false)
        preparation(f, p, "unsent-zero", "unsent-run-zero", "READY", request("summary-zero"), true, ModelEvent.Usage(0, 0, 0))
        preparation(f, p, "deleted-parent", "deleted-run", "HANDED", request("summary-deleted"), true)
        return p to q
    }

    fun testRuntimeOnlyUsageIsStaticCompleteStableAndNeverRestoresExecution() {
        val source = fresh(); val (p, _) = runtimeSeed(source)
        val physicalBefore = checkpointPayloads(source)
        val exported = source.service().exportPackage()
        assertEquals(5, JSONObject(exported).getInt("version")); assertFalse(exported.contains("RUNTIME_PRIVATE_HISTORY"))
        assertEquals(2, runtime(exported).getJSONArray("checkpoints").length()); assertEquals(8, runtimeRows(exported).length())
        assertEquals(0L, runtimeRow(exported, "main-missing").getLong("has_usage"))
        assertEquals(1L, runtimeRow(exported, "main-null").getLong("has_usage"))
        assertTrue(runtimeRow(exported, "main-null").isNull("input_tokens"))
        assertTrue(runtimeRow(exported, "summary-null").isNull("parent_message_id"))
        assertEquals("CHECKPOINT", runtimeRow(exported, "summary-handed").getString("kind"))
        val target = fresh(); assertFalse(target.service().importPackage(exported).alreadyImported)
        target.reopen(); SQLiteRuntimeStore.acquire(target.db).use { it.recoverPending() }
        assertEquals(0, target.count("runtime_checkpoints")); assertEquals(0, target.count("runtime_preparations"))
        assertTrue(target.service().importPackage(exported).alreadyImported)
        assertEquals(runtime(exported).toString(), runtime(target.service().exportPackage()).toString())
        val budget = RecordedUsageBudget(maxPayloadBytes = 4L * 1024 * 1024, maxTotalPayloadBytes = 16L * 1024 * 1024)
        val before = DatabaseTransactions { source.db.writableDatabase }.run { RecordedUsageReader.readAttributed(it, budget = budget) }
        val after = DatabaseTransactions { target.db.writableDatabase }.run { RecordedUsageReader.readAttributed(it, budget = budget) }
        assertEquals(before.summary, after.summary); assertEquals(before.parentCoverage, after.parentCoverage)
        assertEquals(1, after.parentCoverage.mainRequestsWithoutUsage); assertEquals(2, after.parentCoverage.preparationsWithoutUsage)
        assertEquals(5, after.requests.size); assertEquals(11L, after.summary.main.input.reportedTokens)
        assertEquals(4, after.parentCoverage.totalPreparations)
        assertEquals(physicalBefore, checkpointPayloads(source))
        assertEquals(7, runtimeRows(source.service().exportConversation(p.conversationId)).length())
        // Same live host identity must not turn an imported observation into a second charge.
        checkpoint(target, p, listOf(RequestUsageCheckpoint(request("main-zero"), ModelEvent.Usage(0, 0, 0))), listOf(request("main-zero")))
        target.tx { db -> assertEquals(1, RecordedUsageReader.readAttributed(db).requests.count { it.request.requestId == "main-zero" }) }
        target.db.writableDatabase.delete("messages", "id=?", arrayOf(p.id.value))
        assertEquals(1, target.count("recorded_usage_checkpoints"))
        assertEquals(0, target.db.readableDatabase.rawQuery("SELECT count(*) FROM recorded_usage_requests WHERE kind='CHECKPOINT' AND message_id=?", arrayOf(p.id.value))
            .use { it.moveToFirst(); it.getInt(0) })
        target.db.writableDatabase.delete("conversations", null, null)
        assertEquals(0, target.count("recorded_usage_checkpoints")); assertEquals(0, target.count("recorded_usage_requests"))
    }

    fun testCompleteProjectionDoesNotUseReportRowLimit() {
        val source = fresh(); val p = parent(source)
        source.tx { db -> repeat(501) { index ->
            BackupHistoryRows.insert(db, "runtime_preparations", JSONObject().put("message_id", "unsent-$index")
                .put("conversation_id", p.conversationId.value).put("run_id", "unsent-run-$index")
                .put("owner", "preparation-owner").put("process_owner", "preparation-process").put("revision", 1).put("state", "TERMINAL")
                .put("request_id", "request-$index").put("request_owner", "owner-$index").put("attempt_id", "attempt-$index")
                .put("has_usage", 0).put("input_tokens", JSONObject.NULL).put("output_tokens", JSONObject.NULL).put("cached_tokens", JSONObject.NULL))
        } }
        source.tx { db -> assertEquals(500, RecordedUsageReader.read(db).inspectedPreparations) }
        val text = source.service().exportPackage(); assertEquals(501, runtimeRows(text).length())
        val target = fresh(); target.service().importPackage(text)
        assertEquals(501, target.count("recorded_usage_requests")); assertEquals(0, target.count("runtime_preparations"))
        assertEquals(runtime(text).toString(), runtime(target.service().exportPackage()).toString())
        target.tx { db -> val stats = RecordedUsageReader.read(db)
            assertEquals(501, stats.totalPreparations); assertEquals(500, stats.inspectedPreparations); assertFalse(stats.scanComplete) }
    }

    fun testRuntimeProjectionTamperingBadRowsBoundsAndLateRollback() {
        val source = fresh(); runtimeSeed(source); val text = source.service().exportPackage()
        fun edit(change: (JSONObject) -> Unit) = mutate(text) { change(it.getJSONObject("runtime_usage_history").getJSONArray("requests").getJSONObject(0)) }
        val invalid = listOf(edit { it.put("usage_source", "TITLE") }, edit { it.put("has_usage", 0.5) },
            edit { it.put("input_tokens", -1) }, edit { it.put("attempt_id", JSONObject.NULL) },
            edit { it.put("conversation_id", "foreign") }, edit { it.put("run_id", "foreign") },
            edit { it.put("operation", "OPEN") }, edit { it.put("checkpoint", "forbidden") },
            edit { it.put("input_tokens", 0).put("cached_tokens", 1) },
            mutate(text) { it.getJSONObject("runtime_usage_history").getJSONArray("requests").put(runtimeRows(text).getJSONObject(0)) },
            mutate(text) { it.getJSONObject("inventory").put("runtime_usage_requests", 0) },
            edit { it.put("has_usage", 0).put("input_tokens", 0) })
        val target = fresh(); val before = target.db.exportBackup()
        invalid.forEach { bad -> rejects { target.service().preflight(bad) }; rejects { target.service().importPackage(bad) }
            assertEquals(before, target.db.exportBackup()); assertEquals(0, target.count("recorded_usage_requests")) }
        val over = mutate(text) { data ->
            val rows = JSONArray()
            val placeholder = JSONObject()
            repeat(BackupHistoryRows.MAX_ROWS + 1) { rows.put(placeholder) }
            assertTrue(rows.toString().toByteArray(Charsets.UTF_8).size < BackupHistoryRows.MAX_METADATA_BYTES)
            rejects { BackupHistoryRows.rows(rows) }
            data.getJSONObject("runtime_usage_history").put("requests", rows)
            data.getJSONObject("inventory").put("runtime_usage_requests", rows.length())
        }
        rejects { target.service().preflight(over) }
        target.db.writableDatabase.execSQL("""CREATE TRIGGER corrupt_runtime_usage AFTER INSERT ON neutral_imports
            BEGIN UPDATE recorded_usage_requests SET attempt_id='corrupted'; END""")
        rejects { target.service().importPackage(text) }
        assertEquals(before, target.db.exportBackup()); assertEquals(0, target.count("neutral_imports"))
        assertEquals(0, target.count("recorded_usage_checkpoints")); assertEquals(0, target.count("recorded_usage_requests"))
        target.db.writableDatabase.execSQL("DROP TRIGGER corrupt_runtime_usage")
        target.service().importPackage(text)
        target.db.writableDatabase.execSQL("UPDATE recorded_usage_requests SET input_tokens=0.5 WHERE request_id='main-zero'")
        rejects { target.service().importPackage(text) }; rejects { target.service().exportPackage() }
        source.db.writableDatabase.execSQL("UPDATE runtime_preparations SET has_usage=0.5 WHERE request_id='summary-unsent'")
        rejects { source.service().exportPackage() }
        source.db.writableDatabase.execSQL("UPDATE runtime_preparations SET has_usage=0 WHERE request_id='summary-unsent'")
        source.db.writableDatabase.execSQL("UPDATE runtime_checkpoints SET payload='{}'")
        rejects { source.service().exportPackage() }
    }

    fun testLateInsertedUsageRowsIncludingEmptySectionRollbackExactly() {
        val source = fresh(); runtimeSeed(source)
        val populated = source.service().exportPackage()
        val emptySource = fresh(); parent(emptySource)
        val empty = emptySource.service().exportPackage()
        for (text in listOf(populated, empty)) {
            val payload = JSONObject(JSONObject(text).getString("payload"))
            val textEnvelope = JSONObject(payload.getString("text_backup"))
            val rows = JSONObject(textEnvelope.getString("payload")).getJSONArray("messages")
            val assistant = (0 until rows.length()).map { rows.getJSONObject(it) }
                .first { it.getString("role") == "assistant" }
            val conversation = assistant.getString("conversation_id")
            val message = assistant.getString("id")
            val run = assistant.getString("run_id")
            val insertions = listOf(
                "INSERT INTO recorded_usage_requests VALUES('PREPARATION','extra-message','$conversation','extra-run',NULL," +
                    "'extra-owner','extra-request','extra-attempt','NEXT','SUMMARY',1,7,3,0);",
                "INSERT INTO recorded_usage_requests VALUES('CHECKPOINT','$message','$conversation','$run','$message'," +
                    "'extra-owner','extra-request','extra-attempt','NEXT','MAIN',1,7,3,0);",
                "INSERT OR IGNORE INTO recorded_usage_checkpoints VALUES('$message','$conversation','$run');")
            for ((index, insertion) in insertions.withIndex()) {
                if (text == populated && index == 2) continue // Existing exact parent is not an extra row.
                val target = fresh(); val before = target.db.exportBackup()
                target.db.writableDatabase.execSQL("CREATE TRIGGER extra_usage AFTER INSERT ON neutral_imports BEGIN $insertion END")
                rejects { target.service().importPackage(text) }
                assertEquals(before, target.db.exportBackup())
                assertEquals(0, target.count("neutral_imports"))
                assertEquals(0, target.count("recorded_usage_checkpoints"))
                assertEquals(0, target.count("recorded_usage_requests"))
            }
        }
    }

    fun testV4RuntimeUsageRemainsCompatibleAfterV5StaticSectionAddition() {
        val source = fresh(); runtimeSeed(source)
        val current = source.service().exportPackage()
        val old = JSONObject(mutate(current) { data ->
            data.remove("static_generation_history"); data.getJSONObject("inventory").remove("static_generation_outputs")
        }).put("version", 4).toString()
        val target = fresh(); target.service().preflight(old)
        assertFalse(target.service().importPackage(old).alreadyImported)
        assertTrue(target.service().importPackage(old).alreadyImported)
        assertEquals(runtime(current).toString(), runtime(target.service().exportPackage()).toString())
    }

    fun testV19MigrationAndV3CompatibilityAndLegacyRequestFallback() {
        val source = fresh(); val p = parent(source)
        checkpoint(source, p, emptyList(), emptyList(), request("legacy-missing"))
        source.db.writableDatabase.execSQL("UPDATE runtime_checkpoints SET payload=?", arrayOf(
            JSONObject(source.db.readableDatabase.rawQuery("SELECT payload FROM runtime_checkpoints", null).use { it.moveToFirst(); it.getString(0) })
                .put("version", 5).apply { remove("usage_requests") }.toString()))
        val current = source.service().exportPackage()
        val retained = checkpointPayloads(source)
        source.db.writableDatabase.execSQL("DROP TABLE recorded_usage_requests")
        source.db.writableDatabase.execSQL("DROP TABLE recorded_usage_checkpoints")
        source.db.writableDatabase.version = 19; source.reopen()
        assertEquals(20, source.db.writableDatabase.version); assertEquals(retained, checkpointPayloads(source))
        assertEquals(runtime(current).toString(), runtime(source.service().exportPackage()).toString())
        assertEquals(0L, runtimeRow(current, "legacy-missing").getLong("has_usage"))
        val old = JSONObject(mutate(current) { data ->
            data.remove("static_generation_history"); data.getJSONObject("inventory").remove("static_generation_outputs")
            data.remove("runtime_usage_history"); data.getJSONObject("inventory").remove("runtime_usage_checkpoints")
            data.getJSONObject("inventory").remove("runtime_usage_requests")
        }).put("version", 3).toString()
        val target = fresh(); target.service().importPackage(old)
        assertEquals(0, target.count("recorded_usage_checkpoints")); assertTrue(target.service().importPackage(old).alreadyImported)
        target.db.writableDatabase.execSQL("DROP TABLE recorded_usage_requests")
        target.db.writableDatabase.execSQL("DROP TABLE recorded_usage_checkpoints")
        target.db.writableDatabase.version = 19; target.reopen()
        assertEquals(20, target.db.writableDatabase.version)
        assertEquals(0, target.count("recorded_usage_requests")); assertEquals(0, target.count("recorded_usage_checkpoints"))
        assertTrue(target.service().importPackage(old).alreadyImported)
        val imported = fresh(); imported.service().importPackage(current)
        imported.tx { db -> assertEquals(1, RecordedUsageReader.read(db).mainRequestsWithoutUsage) }
    }

    fun testTypedManualAndChildRoundTripIsClosedStableAndDoesNotMutateSource() {
        val source = fresh(); val p = seed(source); val original = physical(source)
        val exported = source.service().exportPackage()
        assertEquals(5, JSONObject(exported).getInt("version")); assertEquals(original, physical(source))
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
                data.remove("static_generation_history"); data.getJSONObject("inventory").remove("static_generation_outputs")
                data.remove("runtime_usage_history")
                data.getJSONObject("inventory").remove("runtime_usage_checkpoints")
                data.getJSONObject("inventory").remove("runtime_usage_requests")
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
