package app.daiyu.android.integration.tools

import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.media.*
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.*
import app.daiyu.android.testContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real contextual adapter + both SQLite stores + Android image inspector/private file store.
 * Only HTTP is replaced. Run explicitly as targeted instrumentation; no live provider charges.
 */
@Suppress("DEPRECATION")
class RunGenerationToolsDeviceTest : InstrumentationTestCase() {
    private val fixtures = mutableListOf<Fixture>()

    private fun owner(message: Message): RunExecutionOwner {
        var token: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { token = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}.run(message, emptyList())
        return checkNotNull(token)
    }

    private inner class Fixture(blockRegistration: Boolean = false, invalidSibling: Boolean = true,
        ledger: ToolLedger = ToolLedger(InMemoryToolLedgerStore()), callId: String = "original-call") {
        val context = testContext(instrumentation.targetContext, "generation-leaf-${UUID.randomUUID()}-")
        val helper = ConversationDatabase(context)
        val db = helper.writableDatabase
        val root = File(context.filesDir, "generation-leaf-${UUID.randomUUID()}")
        val call = ToolInvocation(ToolCallId(callId), RunGenerationTools.IMAGE_TOOL, """{"batch_id":"batch"}""")
        val assistant: Message
        val token: RunExecutionOwner
        val config = GenerationRunConfig(listOf(GenerationRunConfig.Batch("batch", GenerationProtocol.OPENAI_IMAGES_JSON,
            "https://provider.example/v1/images/generations", "secret", "image-model",
            listOf(GenerationRunConfig.Item("item", "draw a square")))))
        val task: TaskAuthorization
        lateinit var assets: MessageGenerationAssets
        lateinit var assetOwner: GenerationAssetOwner
        val databaseCalls = AtomicInteger()
        val registrationEntered = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val posts = AtomicInteger()
        val leaf: RunGenerationTools
        val dispatcher: ToolDispatcher

        init {
            SQLiteGenerationAttemptStore.installSchema(db)
            SQLiteGenerationOutputStore.installSchema(db)
            val conversation = helper.create(assistantId = helper.selectedAssistantId())
            val user = Message(MessageId(UUID.randomUUID().toString()), conversation.id, RunId(UUID.randomUUID().toString()),
                "user", listOf(MessageBlock.Text("prompt", "generate")), RunStatus.COMPLETED)
            assistant = user.copy(id = MessageId(UUID.randomUUID().toString()), role = "assistant",
                blocks = listOf(MessageBlock.Tool(call)), status = RunStatus.RUNNING)
            helper.send(user, assistant)
            token = owner(assistant)
            task = TaskAuthorization("session", assistant.runId.value,
                setOf(RunGenerationTools.writeEffect("test-account", token, config, config.batches.single())))
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
            }
            bitmap.recycle()
            val inline = Base64.getEncoder().encodeToString(png)
            val response = if (invalidSibling) """{"data":[{"b64_json":"$inline"},{"bad":true}]}"""
                else """{"data":[{"b64_json":"$inline"}]}"""
            leaf = RunGenerationTools(config, token, task, "test-account", {
                // reserve + finish precede registration. Blocking here models a registration worker
                // that has entered but not returned, not a fake successful SQL transaction.
                if (blockRegistration && databaseCalls.incrementAndGet() == 3) {
                    registrationEntered.countDown()
                    check(releaseRegistration.await(10, TimeUnit.SECONDS))
                }
                db
            }, { fence ->
                assetOwner = fence.owner
                MessageGenerationAssets(root, fence).also { assets = it }
            }, { candidate ->
                candidate.accountScope == "test-account" &&
                    candidate.binding.conversationId == assistant.conversationId.value &&
                    candidate.binding.assistantMessageId == assistant.id.value &&
                    candidate.binding.runId == assistant.runId.value &&
                    candidate.binding.toolCallId == call.id.value &&
                    db.rawQuery("SELECT id FROM messages WHERE id=? AND run_id=? AND status='RUNNING'",
                        arrayOf(assistant.id.value, assistant.runId.value)).use { it.moveToFirst() }
            }, GenerationRunApproval { review ->
                check(review.context.owner === token && review.context.invocation == call)
                check(review.config === config && review.batch === config.batches.single())
                GenerationRunDecision.APPROVED
            }, GenerationDownloadAuthorization { _, _ -> false }, GenerationHttp { request ->
                check(request.method == "POST")
                posts.incrementAndGet()
                GenerationHttpResponse(200, response.toByteArray())
            })
            dispatcher = ToolDispatcher(ToolRegistry().apply { leaf.registeredTools.forEach { register(it) } }.snapshot(),
                SessionCapabilities("session", setOf(RunGenerationTools.CAPABILITY)), ledger)
        }
        fun dispatch(id: String = call.id.value, permission: TaskAuthorization = task) =
            dispatcher.dispatchOwned(permission, listOf(call.copy(id = ToolCallId(id))), token).single().outcome
        fun close() {
            releaseRegistration.countDown(); leaf.close()
            check(leaf.awaitClose(5000) == GenerationToolsCloseResult.Drained)
            helper.close(); context.deleteDatabase("conversations.db"); root.deleteRecursively()
        }
    }

    override fun tearDown() {
        try { fixtures.forEach { it.close() } } finally { super.tearDown() }
    }

    fun testPartialSuccessIsRegisteredInSameDatabaseAndInvalidSiblingIsRetained() {
        val f = Fixture().also { fixtures.add(it) }
        assertTrue(f.dispatch() is DispatchOutcome.Unknown)
        val outcome = f.leaf.outcomes().single()
        val item = outcome.items.single()
        assertTrue(item.submission is Submission.Completed)
        assertEquals(GenerationFailure.PROTOCOL, item.failures[1])
        val reference = item.references.single()
        assertEquals(0, reference.outputIndex)
        assertEquals(f.assistant.id.value, reference.assistantMessageId)
        assertEquals(f.call.id.value, reference.toolCallId)
        val request = f.config.batches.single().requests(outcome.binding::identity).single()
        val plan = GenerationRequestPlan.prepare(request, f.assets)
        val attempt = GenerationAttempt(plan.identity, plan.fingerprint, plan.protocol)
        val store = SQLiteGenerationOutputStore({ f.db }, f.assetOwner, f.assets)
        assertEquals(reference, store.find(attempt, 0))
        assertNotNull(store.open(attempt, 0)?.use { assertTrue(it.read() >= 0); true })
        f.dispatch()
        assertEquals(1, f.posts.get())
    }

    fun testCompletedPayloadContainsOnlyPostRegistrationReceipt() {
        val f = Fixture(invalidSibling = false).also { fixtures.add(it) }
        val result = f.dispatch() as DispatchOutcome.Confirmed
        val reference = f.leaf.outcomes().single().items.single().references.single()
        assertTrue(result.payload.contains(reference.receipt.assetId))
        assertFalse(result.payload.contains("provider.example"))
        assertFalse(result.payload.contains("secret"))
        f.db.rawQuery("SELECT receipt FROM generation_outputs", null).use {
            assertTrue(it.moveToFirst()); assertEquals(reference.receipt.assetId, it.getString(0))
        }
    }

    fun testSharedCentralLedgerConfirmedNeverReplaysAnotherRealOwnersAssetIds() {
        val ledger = ToolLedger(InMemoryToolLedgerStore())
        val a = Fixture(invalidSibling = false, ledger = ledger, callId = "a-call").also { fixtures.add(it) }
        val b = Fixture(invalidSibling = false, ledger = ledger, callId = "b-call").also { fixtures.add(it) }
        assertNotSame(a.token, b.token)
        assertEquals(a.config.fingerprint, b.config.fingerprint)
        assertFalse(a.task.allowedWrites == b.task.allowedWrites)
        val first = a.dispatch() as DispatchOutcome.Confirmed
        val second = b.dispatch() as DispatchOutcome.Confirmed
        assertFalse(first.replayed); assertFalse(second.replayed)
        assertEquals(1, a.posts.get()); assertEquals(1, b.posts.get())
        val aRef = a.leaf.outcomes().single().items.single().references.single()
        val bRef = b.leaf.outcomes().single().items.single().references.single()
        assertEquals(a.assistant.id.value, aRef.assistantMessageId)
        assertEquals(b.assistant.id.value, bRef.assistantMessageId)
        assertFalse(aRef.receipt.assetId == bRef.receipt.assetId)
        assertTrue(second.payload.contains(bRef.receipt.assetId))
        assertFalse(second.payload.contains(aRef.receipt.assetId))
        // Reference-equal task authorization remains mandatory, even for a cached result.
        val copied = TaskAuthorization(b.task.sessionId, b.task.taskId, b.task.allowedWrites)
        assertEquals(DispatchOutcome.NotExecuted("generation_authorization_mismatch"),
            b.dispatch("b-copy", copied))
        val replayA = a.dispatch("a-new-call") as DispatchOutcome.Confirmed
        val replayB = b.dispatch("b-new-call") as DispatchOutcome.Confirmed
        assertTrue(replayA.replayed); assertTrue(replayB.replayed)
        assertEquals(first.payload, replayA.payload); assertEquals(second.payload, replayB.payload)
        assertEquals(1, a.posts.get()); assertEquals(1, b.posts.get())
    }

    fun testSharedCentralLedgerUnknownNeverBlocksAnotherRealOwner() {
        val ledger = ToolLedger(InMemoryToolLedgerStore())
        val a = Fixture(ledger = ledger, callId = "a-call").also { fixtures.add(it) }
        val b = Fixture(ledger = ledger, callId = "b-call").also { fixtures.add(it) }
        assertNotSame(a.token, b.token)
        assertEquals(a.config.fingerprint, b.config.fingerprint)
        assertEquals(DispatchOutcome.Unknown("generation_pending_or_unconfirmed"), a.dispatch())
        assertEquals(DispatchOutcome.Unknown("generation_pending_or_unconfirmed"), b.dispatch())
        assertEquals(1, a.posts.get()); assertEquals(1, b.posts.get())
        val aRef = a.leaf.outcomes().single().items.single().references.single()
        val bRef = b.leaf.outcomes().single().items.single().references.single()
        assertFalse(aRef.receipt.assetId == bRef.receipt.assetId)
        assertEquals(b.assistant.id.value, bRef.assistantMessageId)
        val copied = TaskAuthorization(b.task.sessionId, b.task.taskId, b.task.allowedWrites)
        assertEquals(DispatchOutcome.NotExecuted("generation_authorization_mismatch"),
            b.dispatch("b-copy", copied))
        assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), a.dispatch("a-new-call"))
        assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), b.dispatch("b-new-call"))
        assertEquals(1, a.posts.get()); assertEquals(1, b.posts.get())
    }

    fun testIgnoredRegistrationNeverExportsTheLocalFileReceipt() {
        val f = Fixture(invalidSibling = false).also { fixtures.add(it) }
        f.db.execSQL("CREATE TRIGGER ignore_generated_output BEFORE INSERT ON generation_outputs BEGIN SELECT RAISE(IGNORE); END")
        assertTrue(f.dispatch() is DispatchOutcome.Unknown)
        val item = f.leaf.outcomes().single().items.single()
        assertTrue(item.references.isEmpty())
        assertEquals(GenerationFailure.ASSET_REJECTED, item.failures[0])
        // Publication really happened, but is still only a private pending file, not a DB reference.
        assertNotNull(f.assets.pending(item.identity, 0))
        f.db.rawQuery("SELECT COUNT(*) FROM generation_outputs", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    fun testCloseDoesNotReportDrainWhileRegistrationWorkerIsBlocked() {
        val f = Fixture(blockRegistration = true, invalidSibling = false).also { fixtures.add(it) }
        val result = AtomicReference<DispatchOutcome>()
        val worker = Thread { result.set(f.dispatch()) }.apply { start() }
        try {
            assertTrue(f.registrationEntered.await(10, TimeUnit.SECONDS))
            f.leaf.revokeAdmission()
            assertEquals(GenerationToolsCloseResult.Unknown("generation_cleanup_not_started"), f.leaf.awaitClose(0))
            f.leaf.close()
            assertTrue(f.leaf.awaitClose(0) is GenerationToolsCloseResult.Unknown)
        } finally { f.releaseRegistration.countDown(); worker.join(10000) }
        assertFalse(worker.isAlive)
        assertTrue(result.get() is DispatchOutcome.Unknown)
        assertEquals(GenerationToolsCloseResult.Drained, f.leaf.awaitClose(1000))
        assertTrue(f.leaf.outcomes().single().items.single().references.isEmpty())
        f.db.rawQuery("SELECT COUNT(*) FROM generation_outputs", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }
}
