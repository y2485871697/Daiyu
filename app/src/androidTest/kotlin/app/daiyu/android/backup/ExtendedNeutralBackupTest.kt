package app.daiyu.android.backup

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.context.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.media.*
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.*
import app.daiyu.android.testContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real application SQLite, real static PNG decoder and immutable files. No HTTP or paid call. */
@Suppress("DEPRECATION")
class ExtendedNeutralBackupTest : InstrumentationTestCase() {
    private val fixtures = mutableListOf<Fixture>()
    private val png get() = android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kF9sAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
    private inner class Fixture(val context: Context, var db: ConversationDatabase) {
        fun service(assets: BackupGenerationAssets? = null) = NeutralBackupService(context, db, null, null, assets)
        fun count(table: String) = db.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { check(it.moveToFirst()); it.getInt(0) }
        fun reopen() { db.close(); db = ConversationDatabase(context) }
    }
    private fun fresh(): Fixture {
        val base = testContext(instrumentation.targetContext, "extended-backup-${UUID.randomUUID()}-")
        val files = File(base.cacheDir, "extended-backup-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = files }
        return Fixture(context, ConversationDatabase(context)).also { fixtures.add(it) }
    }
    override fun tearDown() {
        try { fixtures.forEach { it.db.close(); it.context.deleteDatabase("conversations.db"); it.context.filesDir.deleteRecursively() } }
        finally { super.tearDown() }
    }
    private data class Generated(val binding: GenerationAttemptBinding, val attempt: GenerationAttempt,
        val plan: GenerationRequestPlan, val media: MessageGenerationAssets, val reference: GenerationOutputReference)
    private fun generate(f: Fixture, conversation: ConversationId = f.db.create().id, closeTool: Boolean = false): Generated {
        val run = RunId("run-${UUID.randomUUID()}"); val assistantId = MessageId("a-${UUID.randomUUID()}")
        val binding = GenerationAttemptBinding(conversation.value, assistantId.value, run.value, "image-call", "generate_images")
        val call = MessageBlock.Tool(ToolInvocation(ToolCallId(binding.toolCallId), binding.toolName, "{}"))
        val assistant = Message(assistantId, conversation, run, "assistant", listOf(call), RunStatus.RUNNING,
            historySegments = listOf(MessageHistorySegment(assistantId, "assistant", listOf(call))))
        f.db.send(Message(MessageId("u-${UUID.randomUUID()}"), conversation, run, "user", listOf(MessageBlock.Text("t", "fixture")), RunStatus.COMPLETED), assistant)
        val owner = GenerationAssetOwner("offline-account", binding)
        val media = MessageGenerationAssets(File(f.context.filesDir, "media"), GenerationAssetFence(owner) {
            check(!f.db.writableDatabase.inTransaction()); true
        })
        val plan = GenerationRequestPlan.prepare(GenerationRequest(binding.identity("item"), GenerationProtocol.OPENAI_IMAGES_JSON,
            "https://provider.invalid/v1/images/generations", "SECRET_DO_NOT_EXPORT", "fixture-model", "fixture-prompt"), media)
        val attempt = plan.attempt
        val attempts = SQLiteGenerationAttemptStore({ f.db.writableDatabase }, binding)
        assertSame(GenerationReservation.Started, attempts.reserve(attempt))
        val info = media.inspect(GenerationKind.IMAGE, png, "image/png")
        val receipt = media.commit(attempt.identity, 0, GenerationKind.IMAGE, png, info)
        val reference = SQLiteGenerationOutputStore({ f.db.writableDatabase }, owner, media).register(attempt, receipt)
        attempts.finish(attempt, Submission.Completed(listOf(GenerationOutput(0,
            GenerationSource.Remote("https://provider.invalid/signed-original?signature=PRIVATE_AUTH_MATERIAL")))))
        // Close both authoritative segments and the legacy projection on the real RUNNING ->
        // COMPLETED transition. A late edit of an already completed receipt is rightly rejected.
        val finalBlocks = if (closeTool) listOf(call.copy(result = "UNKNOWN; historical evidence only")) else assistant.blocks
        f.db.save(assistant.copy(status = RunStatus.COMPLETED, blocks = finalBlocks,
            historySegments = assistant.historySegments.map { it.copy(blocks = finalBlocks) }))
        return Generated(binding, attempt, plan, media, reference)
    }
    private fun children(f: Fixture, parent: Generated): ChildTaskCommand {
        val b = parent.binding
        val source = ChildTaskRecord(ChildRunOwner(b.conversationId, b.runId, "group-source", 1, "child-source", "child-assistant-source"),
            b.assistantMessageId, "original-parent-owner", 0, "historical-profile", "source prompt", ChildTaskStatus.COMPLETED, "source result")
        val command = ChildTaskCommand("command-call", source, ChildTaskCommandKind.APPEND, "historical-profile", "follow-up")
        val successor = ChildTaskRecord(ChildRunOwner(b.conversationId, b.runId, "group-successor", 2, "child-successor", "child-assistant-successor"),
            b.assistantMessageId, "original-parent-owner", 0, command.profileId, command.instruction, ChildTaskStatus.RUNNING,
            sourceTaskId = source.owner.childRunId, commandKind = command.kind)
        fun row(t: ChildTaskRecord) = JSONObject().put("task_id", t.owner.childRunId).put("conversation_id", t.owner.conversationId)
            .put("parent_run_id", t.owner.parentRunId).put("parent_message_id", t.parentMessageId).put("parent_owner_id", t.parentOwnerId)
            .put("task_group_id", t.owner.taskGroupId).put("generation", t.owner.generation).put("task_index", t.index)
            .put("assistant_message_id", t.owner.assistantMessageId).put("profile_id", t.profileId).put("prompt", t.prompt)
            .put("status", t.status.name).put("text", t.text).put("detail", t.detail ?: JSONObject.NULL).put("control_revision", t.revision)
            .put("source_task_id", t.sourceTaskId ?: JSONObject.NULL).put("command_kind", t.commandKind?.name ?: JSONObject.NULL)
        BackupHistoryRows.insert(f.db.writableDatabase, "child_tasks", row(source.copy(revision = 1)))
        BackupHistoryRows.insert(f.db.writableDatabase, "child_tasks", row(successor))
        BackupHistoryRows.insert(f.db.writableDatabase, "child_task_commands", JSONObject().put("call_id", command.callId)
            .put("fingerprint", command.fingerprint()).put("source_id", source.owner.childRunId).put("successor_id", successor.owner.childRunId)
            .put("expected_revision", 0).put("acknowledged", 0))
        BackupHistoryRows.insert(f.db.writableDatabase, "child_request_usage", JSONObject().put("task_id", successor.owner.childRunId)
            .put("request_id", "request").put("request_owner", "historical-request-owner").put("attempt_id", "attempt")
            .put("operation", "OPEN").put("checkpoint_revision", 1).put("input_tokens", 7).put("output_tokens", 3).put("cached_tokens", JSONObject.NULL))
        return command
    }
    private fun payload(text: String) = JSONObject(JSONObject(text).getString("payload"))
    private fun mutate(text: String, block: (JSONObject) -> Unit): String {
        val envelope = JSONObject(text); val data = payload(text); block(data)
        val raw = data.toString(); return envelope.put("payload", raw).put("sha256", PrivateBlobs.digest(raw.toByteArray(Charsets.UTF_8))).toString()
    }
    private fun rejects(action: () -> Unit) {
        try { action() } catch (_: Exception) { return }
        fail("must fail closed")
    }
    fun testGenerationAndChildHistorySurviveReopenWithTombstonesAndZeroNetwork() {
        val source = fresh(); val g = generate(source); val command = children(source, g)
        val sourceText = source.db.exportBackup()
        val access = MessageGenerationBackupAssets { reference -> assertEquals(g.reference, reference); g.media }
        rejects { source.service().exportPackage() } // Unsupported full export is explicit, not a reference-only success.
        val exporter = source.service(access); val text = exporter.exportPackage()
        assertEquals(2, JSONObject(text).getInt("version")); assertEquals(sourceText, source.db.exportBackup())
        for (privateValue in listOf("provider.invalid", "SECRET_DO_NOT_EXPORT", "PRIVATE_AUTH_MATERIAL")) assertFalse(text.contains(privateValue))
        val inventory = exporter.preflight(text)
        assertEquals(1, inventory.generationOutputs); assertEquals(1, inventory.generationAttempts)
        assertEquals(2, inventory.childTasks); assertEquals(1, inventory.childCommands); assertEquals(1, inventory.childRequests)
        val target = fresh(); val version = target.db.writableDatabase.version
        assertFalse(target.service().importPackage(text).alreadyImported)
        assertEquals(version, target.db.writableDatabase.version)
        assertEquals(0, target.count("generation_outputs")); assertEquals(1, target.count("generation_attempts")); assertEquals(0, target.count("runtime_checkpoints"))
        target.reopen()
        val importer = target.service()
        assertTrue(importer.importPackage(text).alreadyImported)
        val restored = importer.historicalGenerationOutputs().single()
        assertEquals(g.reference, restored.source); assertTrue(restored.historicalOnly)
        assertTrue(png.contentEquals(importer.historicalGenerationOriginal(restored)))
        val existing = SQLiteGenerationAttemptStore({ target.db.writableDatabase }, g.binding).reserve(g.attempt)
        assertEquals(GenerationReservation.Existing(g.attempt.fingerprint, null), existing)
        val calls = AtomicInteger()
        val io = GenerationIo(g.media, SQLiteGenerationAttemptStore({ target.db.writableDatabase }, g.binding),
            { calls.incrementAndGet(); false }, GenerationHttp { calls.incrementAndGet(); error("network forbidden") })
        assertEquals(Submission.Unknown(GenerationFailure.ALREADY_ATTEMPTED), io.submit(g.plan) { error("parser forbidden") })
        assertEquals(0, calls.get())
        val childStore = SQLiteChildTaskStore(target.db)
        val receipt = childStore.commandReceipt(command) as ChildTaskCommandReceipt.Accepted
        assertEquals("child-successor", receipt.task.owner.childRunId); assertEquals(ChildTaskStatus.UNKNOWN, receipt.task.status)
        assertEquals("original-parent-owner", receipt.task.parentOwnerId); assertEquals("child-source", receipt.task.sourceTaskId)
        assertTrue(childStore.list(g.binding.conversationId).none { it.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING) })
        val reexported = importer.exportPackage() // Does not need a live owner/fence or generation capability.
        assertEquals(payload(text).getJSONObject("generation_history").toString(), payload(reexported).getJSONObject("generation_history").toString())
        assertEquals(payload(text).getJSONObject("child_task_history").toString(), payload(reexported).getJSONObject("child_task_history").toString())
    }
    fun testDuplicateRejectsMissingOrCorruptGenerationOriginalWithoutRepair() {
        val source = fresh(); val g = generate(source)
        val text = source.service(MessageGenerationBackupAssets { g.media }).exportPackage()
        for (missing in listOf(false, true)) {
            val target = fresh(); val importer = target.service(); importer.importPackage(text); target.reopen()
            val blob = File(target.context.filesDir, "neutral-context-blobs/${g.reference.sha256}")
            if (missing) assertTrue(blob.delete()) else blob.writeBytes(byteArrayOf(4, 5, 6))
            rejects { target.service().importPackage(text) }
            assertEquals(1, target.count("neutral_imports")); assertEquals(0, target.count("generation_outputs"))
            if (missing) assertFalse(blob.exists()) else assertTrue(byteArrayOf(4, 5, 6).contentEquals(blob.readBytes()))
        }
    }
    fun testUnknownFieldsOwnersBrokenCommandChainsAndMissingOriginalFailBeforePublication() {
        val source = fresh(); val g = generate(source); children(source, g)
        val text = source.service(MessageGenerationBackupAssets { g.media }).exportPackage()
        val bad = listOf(
            mutate(text) { it.getJSONObject("generation_history").getJSONArray("outputs").getJSONObject(0).put("outcome", "SECRET") },
            mutate(text) { it.getJSONObject("generation_history").getJSONArray("outputs").getJSONObject(0).put("assistant_id", "foreign") },
            mutate(text) { it.getJSONObject("generation_history").getJSONArray("attempts").getJSONObject(0).put("fingerprint", "secret-not-a-hash") },
            mutate(text) { it.getJSONObject("child_task_history").getJSONArray("commands").getJSONObject(0).put("source_id", "child-successor") },
            mutate(text) { it.getJSONObject("child_task_history").getJSONArray("tasks").getJSONObject(1).put("parent_run_id", "foreign") },
            mutate(text) { it.getJSONArray("blobs").remove(0) })
        bad.forEach { input ->
            val target = fresh(); rejects { target.service().importPackage(input) }
            assertEquals(0, target.count("neutral_imports")); assertEquals(0, target.count("generation_attempts")); assertEquals(0, target.count("child_tasks"))
            assertFalse(File(target.context.filesDir, "neutral-context-blobs").listFiles().orEmpty().any { it.isFile })
        }
        val target = fresh(); target.service().importPackage(text)
        target.db.writableDatabase.execSQL("UPDATE child_task_commands SET fingerprint=?", arrayOf("0".repeat(64)))
        rejects { target.service().importPackage(text) }
        assertEquals(1, target.count("neutral_imports"))
        val wrongHistory = fresh(); wrongHistory.service().importPackage(text)
        val userId = wrongHistory.db.readableDatabase.rawQuery("SELECT id FROM messages WHERE role='user' LIMIT 1", null)
            .use { check(it.moveToFirst()); it.getString(0) }
        wrongHistory.db.writableDatabase.execSQL("UPDATE neutral_backup_history SET owner_message_id=? WHERE kind='generation_output'", arrayOf(userId))
        rejects { wrongHistory.service().importPackage(text) }
        val forgedLive = fresh(); forgedLive.service().importPackage(text)
        BackupHistoryRows.insert(forgedLive.db.writableDatabase, "generation_outputs", payload(text).getJSONObject("generation_history").getJSONArray("outputs").getJSONObject(0))
        rejects { forgedLive.service().importPackage(text) }
        assertEquals(1, forgedLive.count("generation_outputs")) // Rejection does not silently delete/repair the bad destination.
    }
    fun testOrdinaryAttachmentsAndArchivesAreReverifiedOnDuplicateWithoutOverwrite() {
        val source = fresh(); val conversation = source.db.create().id
        repeat(5) { index ->
            val run = RunId("archive-run-$index")
            val assistant = Message(MessageId("archive-a-$index"), conversation, run, "assistant", listOf(MessageBlock.Text("t", "answer $index ".repeat(200))), RunStatus.RUNNING)
            source.db.send(Message(MessageId("archive-u-$index"), conversation, run, "user", listOf(MessageBlock.Text("t", "question $index ".repeat(200))), RunStatus.COMPLETED), assistant)
            source.db.save(assistant.copy(status = RunStatus.COMPLETED))
        }
        val exporter = source.service()
        val archive = ContextService(source.context, source.db).compress(conversation, CompressionPolicy(keepTurns = 1), ContextSummarizer { "fixture summary" })!!
        exporter.attach("attachment", "archive-u-4", "application/octet-stream", byteArrayOf(1, 2, 3))
        val text = exporter.exportPackage(); val tables = payload(text).getJSONObject("tables")
        val archiveHash = tables.getJSONArray("context_archives").getJSONObject(0).getString("blob_hash")
        val attachmentHash = tables.getJSONArray("neutral_assets").getJSONObject(0).getString("blob_hash")
        for (hash in listOf(archiveHash, attachmentHash)) {
            val target = fresh(); target.service().importPackage(text); target.reopen()
            val blob = File(target.context.filesDir, "neutral-context-blobs/$hash"); blob.writeBytes(byteArrayOf(9))
            rejects { target.service().importPackage(text) }
            assertTrue(byteArrayOf(9).contentEquals(blob.readBytes())); assertEquals(1, target.count("neutral_imports"))
        }
        val target = fresh(); target.service().importPackage(text)
        target.db.writableDatabase.execSQL("UPDATE neutral_assets SET message_id='archive-a-4' WHERE id='attachment'")
        rejects { target.service().importPackage(text) }
        assertEquals(1, target.count("neutral_imports"))
        assertNotNull(archive)
    }
    fun testDeletedGenerationOwnerStillExportsTheNecessaryNoOutcomeTombstone() {
        val source = fresh(); val g = generate(source); val ownerText = source.db.exportBackup()
        source.db.delete(ConversationId(g.binding.conversationId))
        assertEquals(0, source.count("generation_outputs")); assertEquals(1, source.count("generation_attempts"))
        val text = source.service().exportPackage()
        assertEquals(0, source.service().preflight(text).generationOutputs)
        assertEquals(1, source.service().preflight(text).generationAttempts)
        val target = fresh(); target.service().importPackage(text); target.reopen()
        target.db.importBackup(ownerText) // Recreating the old identity must not reopen its paid request.
        val reservation = SQLiteGenerationAttemptStore({ target.db.writableDatabase }, g.binding).reserve(g.attempt)
        assertEquals(GenerationReservation.Existing(g.attempt.fingerprint, null), reservation)
        assertEquals(0, target.count("generation_outputs"))
    }
    fun testFinalReceiptMutationRollsBackTextTombstonesChildrenAndMarker() {
        val source = fresh(); val g = generate(source); children(source, g)
        val text = source.service(MessageGenerationBackupAssets { g.media }).exportPackage()
        val target = fresh()
        target.db.writableDatabase.execSQL("CREATE TRIGGER corrupt_imported_authorization AFTER INSERT ON neutral_imports " +
            "BEGIN UPDATE generation_attempts SET outcome=X'01'; END")
        rejects { target.service().importPackage(text) }
        target.reopen()
        assertEquals(0, target.count("neutral_imports")); assertEquals(0, target.count("generation_attempts"))
        assertEquals(0, target.count("child_tasks")); assertEquals(0, target.count("child_task_commands")); assertEquals(0, target.count("messages"))
    }
    // A closed UNKNOWN tool result is evidence, never an authorization to invoke the tool.
    // generate(closeTool=true) reaches the asset check, not the incomplete-tool-batch guard.
    private fun assertGenerationBranchRefused(f: Fixture, g: Generated, reason: String) {
        val actions = MessageActionStore(f.db, ContextService(f.context, f.db),
            MessageImageAssetStore(File(f.context.filesDir, "branch-images")))
        val target = actions.snapshot(ConversationId(g.binding.conversationId)).targets
            .getValue(g.binding.assistantMessageId)
        try {
            actions.branch(target)
            fail("a branch without its generation originals must not report success")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(reason))
        }
    }
    fun testLiveGenerationPrefixStillRefusesRatherThanForgingANewAttempt() {
        val source = fresh(); val g = generate(source, closeTool = true)
        val exporter = source.service(MessageGenerationBackupAssets { reference ->
            assertEquals(g.reference, reference); g.media
        })
        val before = exporter.exportPackage()
        val conversations = source.db.conversations()
        val history = ContextService(source.context, source.db).displayHistory(ConversationId(g.binding.conversationId))
        assertGenerationBranchRefused(source, g, "生成资产尚无安全")
        assertEquals(conversations, source.db.conversations())
        assertEquals(before, exporter.exportPackage())
        assertEquals(1, source.count("generation_outputs")); assertEquals(1, source.count("generation_attempts"))
        assertEquals(0, source.count("context_branches"))
        assertTrue(png.contentEquals(g.media.openOriginal(g.reference.receipt).use { it.readBytes() }))
        history.forEach { assertTrue(SQLiteMessageVideoStore.readVerified(source.db.writableDatabase, it).isEmpty()) }
    }
    fun testBackupReadonlyGenerationPrefixRefusesWithPresentOrMissingOriginal() {
        val source = fresh(); val g = generate(source, closeTool = true)
        val text = source.service(MessageGenerationBackupAssets { g.media }).exportPackage()
        for (missing in listOf(false, true)) {
            val target = fresh(); target.service().importPackage(text); target.reopen()
            val service = target.service()
            val historical = service.historicalGenerationOutputs().single()
            assertTrue(png.contentEquals(service.historicalGenerationOriginal(historical)))
            val blob = File(target.context.filesDir, "neutral-context-blobs/${g.reference.sha256}")
            if (missing) assertTrue(blob.delete())
            val before = target.db.exportBackup()
            val conversations = target.db.conversations()
            val history = ContextService(target.context, target.db).displayHistory(ConversationId(g.binding.conversationId))
            assertGenerationBranchRefused(target, g, "只读生成原件尚无安全")
            assertEquals(before, target.db.exportBackup())
            assertEquals(conversations, target.db.conversations())
            assertEquals(listOf(historical), service.historicalGenerationOutputs())
            assertEquals(1, target.count("neutral_backup_history")); assertEquals(1, target.count("generation_attempts"))
            assertEquals(0, target.count("generation_outputs")); assertEquals(0, target.count("context_branches"))
            assertEquals(GenerationReservation.Existing(g.attempt.fingerprint, null),
                SQLiteGenerationAttemptStore({ target.db.writableDatabase }, g.binding).reserve(g.attempt))
            history.forEach { assertTrue(SQLiteMessageVideoStore.readVerified(target.db.writableDatabase, it).isEmpty()) }
            if (missing) {
                rejects { service.historicalGenerationOriginal(historical) }
                assertFalse(blob.exists()) // Rejection must not repair or manufacture a missing original.
            } else assertTrue(png.contentEquals(service.historicalGenerationOriginal(historical)))
        }
    }
    fun testReadonlyGenerationOutsideTheCopiedPrefixDoesNotBlockTextBranch() {
        val source = fresh(); val owner = source.db.create().id
        val run = RunId("early-${UUID.randomUUID()}")
        val user = Message(MessageId("early-u-${UUID.randomUUID()}"), owner, run, "user",
            listOf(MessageBlock.Text("t", "earlier text")), RunStatus.COMPLETED, createdAt = 1_700_000_000_000L)
        val assistant = user.copy(id = MessageId("early-a-${UUID.randomUUID()}"), role = "assistant",
            blocks = listOf(MessageBlock.Text("a", "earlier answer")), status = RunStatus.RUNNING,
            createdAt = 1_700_000_000_001L)
        source.db.send(user, assistant); source.db.save(assistant.copy(status = RunStatus.COMPLETED))
        val g = generate(source, owner, closeTool = true)
        val text = source.service(MessageGenerationBackupAssets { g.media }).exportPackage()
        val target = fresh(); target.service().importPackage(text); target.reopen()
        val service = target.service()
        val historical = service.historicalGenerationOutputs().single()
        val context = ContextService(target.context, target.db)
        val original = context.displayHistory(owner)
        val actions = MessageActionStore(target.db, context, MessageImageAssetStore(File(target.context.filesDir, "branch-images")))
        val branch = actions.branch(actions.snapshot(owner).targets.getValue(user.id.value))
        val copied = context.displayHistory(branch.id)
        assertEquals(original.take(2).map { it.blocks }, copied.map { it.blocks })
        assertEquals(original.take(2).map { it.createdAt }, copied.map { it.createdAt })
        assertTrue(copied.all { it.conversationId == branch.id && it.runId != run })
        assertEquals(original, context.displayHistory(owner))
        assertTrue(service.historicalGenerationOutputs(branch.id).isEmpty())
        assertEquals(listOf(historical), service.historicalGenerationOutputs(owner))
        assertTrue(png.contentEquals(service.historicalGenerationOriginal(historical)))
        assertEquals(0, target.count("generation_outputs")); assertEquals(1, target.count("generation_attempts"))
        assertEquals(1, target.count("context_branches"))
        copied.forEach { assertTrue(SQLiteMessageVideoStore.readVerified(target.db.writableDatabase, it).isEmpty()) }
    }
    fun testVersionOnePackageStillImportsAndFutureVersionsFailClosed() {
        val source = fresh(); source.db.create()
        val current = source.service().exportPackage()
        val old = JSONObject(mutate(current) {
            it.remove("generation_history"); it.remove("child_task_history")
            val inventory = it.getJSONObject("inventory")
            listOf("generation_outputs", "generation_attempts", "child_tasks", "child_commands", "child_requests").forEach { key -> inventory.remove(key) }
        }).put("version", 1).toString()
        val target = fresh(); assertFalse(target.service().importPackage(old).alreadyImported)
        target.reopen(); assertTrue(target.service().importPackage(old).alreadyImported)
        rejects { target.service().preflight(JSONObject(current).put("version", 99).toString()) }
    }
}
