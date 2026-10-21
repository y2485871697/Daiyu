package app.daiyu.android

import android.app.Application
import android.content.Context
import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.context.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The real controller lane/SQLite/runner with only model transports and policy snapshots injected. */
@Suppress("DEPRECATION")
class ControllerContextPreparationTest : InstrumentationTestCase() {
    private class FixtureApplication(base: Context) : Application() {
        private val files = File(base.cacheDir,"controller-context-${UUID.randomUUID()}").apply { mkdirs() }
        init { attachBaseContext(base) }
        override fun getFilesDir(): File = files
        // Production factories normalize to applicationContext; keep their reads isolated too.
        override fun getApplicationContext(): Context = this
    }
    private data class Fixture(val app: FixtureApplication, val db: ConversationDatabase,
        val controller: ChatController, val alias: String)
    private val fixtures = mutableListOf<Fixture>()
    private val binding = SummaryModelBinding("summary-fixture", "summary-only")
    private fun main(work: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try {work()} catch (t: Throwable) {failure.set(t)} }
        failure.get()?.let {throw it}
    }
    private fun await(latch: CountDownLatch) = assertTrue("timed out", latch.await(10,TimeUnit.SECONDS))
    private fun fresh(config: CompressionSettings = CompressionSettings(),
        summary: SummaryModelResolver = SummaryModelResolver { error("unexpected summary resolution") },
        production: Boolean = false,
        afterSendRowsWritten: () -> Unit = {},
        factory: (ModelSettings) -> ModelPort): Fixture {
        val id = UUID.randomUUID().toString()
        val app = FixtureApplication(testContext(instrumentation.targetContext,"controller-context-$id-"))
        val db = ConversationDatabase(app, afterSendRowsWritten)
        val alias = "app.daiyu.android.test.context.$id"
        val settings = ModelSettingsStore(app,alias)
        settings.save(ModelSettings("https://example.invalid","context-fixture","synthetic-key",false))
        val controller = ChatController(app,db,settings,app.getSharedPreferences("conversation-ui",0),factory,
            contextPreparationFactory = { context, service ->
                if (production) ProductionContextPreparation.create(context, service)
                else ContextPreparationCoordinator(service, {
                    check(Looper.myLooper()!=Looper.getMainLooper())
                    config
                }, CompressionPolicyResolver { snapshot ->
                    // Explicit fixture policy, not a production message-to-turns conversion.
                    CompressionPolicy(snapshot.automaticWithKnownWindow,snapshot.contextWindowTokens,
                        snapshot.triggerPercent,snapshot.reserveTokens,2)
                }, object : SummaryModelResolver {
                    override fun resolve(binding: SummaryModelBinding) = summary.resolve(binding)
                    override fun resolve(binding: SummaryModelBinding, usage: BoundSummaryUsagePort?): BoundSummaryModel? {
                        val bound = summary.resolve(binding) ?: return null
                        return bound.copy(transport = SummaryModelTransport { history, deadline ->
                            usage?.claim()
                            val delegate = bound.transport.openWithoutTools(history, deadline)
                            object : ModelSession {
                                override fun cancel() = delegate.cancel()
                                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = delegate.next(outputs) { event ->
                                    if (event is ModelEvent.Usage) usage?.observe(event) else sink(event)
                                }
                            }
                        })
                    }
                })
            })
        val f = Fixture(app,db,controller,alias);fixtures.add(f)
        val ready = CountDownLatch(1)
        val listener: () -> Unit = {if(controller.ready) ready.countDown()}
        main {controller.observe(listener);listener()}
        await(ready)
        main {controller.unobserve(listener)}
        assertNull(controller.problem)
        return f
    }
    fun testSummaryCleanupFailureFencesNewSendsWithoutEscapingCleanupCaller() {
        val opens = AtomicInteger()
        val f = fresh { ModelPort { opens.incrementAndGet(); session() } }
        val reservation = RuntimeSummaryPreparation(BoundSummaryUsagePort("fixture", {}, {}),
            ContextPreparationCancellation(), {}, { throw android.database.sqlite.SQLiteException("fixture") })
        val close = ChatController::class.java.getDeclaredMethod("closeSummaryPreparationSafely", RuntimeSummaryPreparation::class.java)
            .apply { isAccessible = true }
        val idle = ChatController::class.java.getDeclaredMethod("idleAvailable").apply { isAccessible = true }
        main {
            close.invoke(f.controller, reservation)
            assertTrue(f.controller.problem?.contains("摘要用量清理未确认") == true)
            assertEquals(false, idle.invoke(f.controller))
        }
        assertEquals(0, opens.get())
    }

    private fun seed(f: Fixture, turns: Int = 6): Conversation {
        val owner = f.db.create("default")
        repeat(turns) { n ->
            val run = RunId("seed-$n")
            val user = Message(MessageId("u-$n"),owner.id,run,"user",
                listOf(MessageBlock.Text("body","事实$n ".repeat(100))),RunStatus.COMPLETED)
            val assistant = user.copy(id=MessageId("a-$n"),role="assistant",status=RunStatus.RUNNING)
            f.db.send(user,assistant);f.db.save(assistant.copy(status=RunStatus.COMPLETED))
        }
        return owner
    }
    private fun session() = object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            sink(ModelEvent.TextFinal("body","done"))
        }
        override fun cancel() {}
    }
    private fun send(f: Fixture, conversation: Conversation?, text: String,
        ticket: ChatController.TextSendTicket = ChatController.TextSendTicket()): ChatController.TextSendResult {
        val result = AtomicReference<ChatController.TextSendResult>()
        val done = CountDownLatch(1)
        main { assertTrue(f.controller.sendText(conversation,17,23,text,ticket=ticket) {
            assertEquals(Looper.getMainLooper(),Looper.myLooper())
            result.set(it);done.countDown()
        }) }
        await(done)
        return result.get().also {
            assertEquals(ChatController.TextSendOwner(conversation?.id,17,23),it.owner)
        }
    }
    override fun tearDown() {
        try {
            fixtures.forEach { f ->
                f.controller.stop();f.controller.close();f.db.close()
                f.app.deleteDatabase("conversations.db");f.app.filesDir.deleteRecursively()
                val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
                java.util.Collections.list(keys.aliases()).filter {it.startsWith("${f.alias}.")}
                    .forEach {keys.deleteEntry(it)}
            }
        } finally {super.tearDown()}
    }

    fun testRunnerReceivesPreparedSummaryAndExactlyOnePendingMessage() {
        val opened = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        val history = AtomicReference<List<Message>>()
        val summary = BoundSummaryModel(binding,SummaryModelTransport { _, _ -> object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.Usage(10, 20, 5))
                sink(ModelEvent.TextFinal("summary","已确认事实"))
            }
            override fun cancel() {}
        } },100000,90000,{SummaryRequestDeadline(60000)})
        val f = fresh(CompressionSettings(true,10000,1000,10,2,binding),SummaryModelResolver {summary}) {
            ModelPort { input ->
                history.set(input);opened.countDown()
                assertTrue(releaseMain.await(10, TimeUnit.SECONDS))
                session()
            }
        }
        val owner=seed(f);f.db.draft(owner.id,"pending-once")
        assertTrue(send(f,owner,"pending-once") is ChatController.TextSendResult.Accepted)
        await(opened)
        // sendText's completion ran AFTER finishPreparation; the run, not the editor token,
        // still owns the summary handle. It must remain HANDED rather than revoked.
        f.db.readableDatabase.rawQuery("SELECT state FROM runtime_preparations WHERE conversation_id=?", arrayOf(owner.id.value)).use {
            assertTrue(it.moveToFirst()); assertEquals("HANDED", it.getString(0))
        }
        releaseMain.countDown()
        val input=history.get()
        assertTrue(input.first().id.value.startsWith("summary-"))
        assertEquals(1,input.count {row -> row.blocks.filterIsInstance<MessageBlock.Text>().any {it.text=="pending-once"}})
        val service=ContextService(f.app,f.db)
        assertNotNull(service.prepareSend(owner.id).archiveId)
        // Seed originals remain intact; only the new user/assistant pair was appended.
        assertEquals(14,service.displayHistory(owner.id).size)
        val usage = service.usage(owner.id.value)
        assertEquals(10L, usage.inputTokens); assertEquals(20L, usage.outputTokens)
        assertEquals(1, usage.toJson().getJSONObject("request_sources").getInt("summary"))
    }

    fun testNewConversationCannotBypassKnownBudget() {
        val factories=AtomicInteger()
        val f=fresh(CompressionSettings(false,2048,100)) {
            factories.incrementAndGet();ModelPort {session()}
        }
        val draft="too large ".repeat(1000);f.db.draft(draft)
        assertTrue(send(f,null,draft) is ChatController.TextSendResult.Rejected)
        assertEquals(0,factories.get())
        assertEquals(draft,f.db.draft());assertTrue(f.db.conversations().isEmpty())
    }

    fun testFinalCasIncludesRawRowsOutsideTheUiPage() {
        lateinit var f: Fixture
        lateinit var owner: Conversation
        f=fresh {
            val first=f.db.messages(owner.id,limit=200).first {it.second.role=="assistant"}.second
            f.db.writableDatabase.execSQL("UPDATE messages SET blocks=? WHERE id=?",
                arrayOf<Any?>("[{\"type\":\"text\",\"item\":\"changed\",\"text\":\"concurrent original edit\"}]", first.id.value))
            f.db.readableDatabase.rawQuery("SELECT blocks FROM messages WHERE id=?", arrayOf(first.id.value)).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("concurrent original edit", org.json.JSONArray(c.getString(0)).getJSONObject(0).getString("text"))
            }
            ModelPort {error("CAS conflict must not open")}
        }
        owner=seed(f,32);f.db.draft(owner.id,"pending")
        assertTrue(send(f,owner,"pending") is ChatController.TextSendResult.Rejected)
        assertEquals("会话、原文或编辑草稿已变化；保留当前草稿，未启动聊天模型请求", f.controller.problem)
        assertEquals(64,ContextService(f.app,f.db).displayHistory(owner.id).size)
        assertEquals("pending",f.db.conversations().single().draft)
    }

    fun testFinalCasPreservesNewerDraft() {
        lateinit var f: Fixture
        lateinit var owner: Conversation
        f=fresh {
            f.db.draft(owner.id,"newer draft")
            ModelPort {error("draft conflict must not open")}
        }
        owner=seed(f,1);f.db.draft(owner.id,"pending")
        assertTrue(send(f,owner,"pending") is ChatController.TextSendResult.Rejected)
        assertEquals("newer draft",f.db.conversations().single().draft)
        assertEquals(2,ContextService(f.app,f.db).displayHistory(owner.id).size)
    }

    fun testFinalCasDoesNotRecreateDeletedOwner() {
        lateinit var f: Fixture
        lateinit var owner: Conversation
        f=fresh {
            f.db.delete(owner.id)
            ModelPort {error("owner conflict must not open")}
        }
        owner=seed(f,1);f.db.draft(owner.id,"pending")
        assertTrue(send(f,owner,"pending") is ChatController.TextSendResult.Rejected)
        assertTrue(f.db.conversations().isEmpty())
    }

    fun testStopCancelsActualSummaryButKeepsReservationUntilWorkerExits() {
        val entered=CountDownLatch(1);val cancelled=CountDownLatch(1);val release=CountDownLatch(1)
        val mainFactories=AtomicInteger()
        val bound=BoundSummaryModel(binding,SummaryModelTransport { _, _ -> object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS))
                sink(ModelEvent.TextFinal("summary","must not publish"))
            }
            override fun cancel() {cancelled.countDown()}
        } },100000,90000,{SummaryRequestDeadline(60000)})
        val f=fresh(CompressionSettings(true,10000,1000,10,2,binding),SummaryModelResolver {bound}) {
            mainFactories.incrementAndGet();ModelPort {session()}
        }
        val owner=seed(f);val service=ContextService(f.app,f.db)
        service.compress(owner.id,CompressionPolicy(keepTurns=2),ContextSummarizer {"旧摘要"})
        val before=service.prepareSend(owner.id)
        f.db.draft(owner.id,"pending")
        val result=AtomicReference<ChatController.TextSendResult>();val done=CountDownLatch(1)
        try {
            main {assertTrue(f.controller.sendText(owner,1,2,"pending") {result.set(it);done.countDown()})}
            await(entered)
            main {f.controller.stop()}
            await(cancelled)
            main {assertFalse(f.controller.sendText(owner,1,3,"duplicate") {fail("not admitted")})}
        } finally {release.countDown()}
        await(done)
        assertTrue(result.get() is ChatController.TextSendResult.Rejected)
        assertEquals(before,service.prepareSend(owner.id));assertEquals(0,mainFactories.get())
        assertEquals("pending",f.db.conversations().single().draft)
    }

    fun testSummaryRequestFailureDoesNotClaimNoNetworkRequest() {
        val requests=AtomicInteger();val factories=AtomicInteger()
        val bound=BoundSummaryModel(binding,SummaryModelTransport { _, _ -> object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                requests.incrementAndGet()
                throw java.io.IOException("summary transport failed after request started")
            }
            override fun cancel() {}
        } },100000,90000,{SummaryRequestDeadline(60000)})
        val f=fresh(CompressionSettings(true,10000,1000,10,2,binding),SummaryModelResolver {bound}) {
            factories.incrementAndGet();ModelPort {error("chat must not open")}
        }
        val owner=seed(f);val before=ContextService(f.app,f.db).prepareSend(owner.id)
        f.db.draft(owner.id,"pending")
        assertTrue(send(f,owner,"pending") is ChatController.TextSendResult.Rejected)
        assertEquals(1,requests.get());assertEquals(0,factories.get())
        assertEquals("上下文或摘要准备失败；草稿保留，未启动聊天模型请求",f.controller.problem)
        assertEquals(before,ContextService(f.app,f.db).prepareSend(owner.id))
        assertEquals("pending",f.db.conversations().single().draft)
        assertEquals(12,ContextService(f.app,f.db).displayHistory(owner.id).size)
    }

    fun testFinalCasAfterSummaryRequestDoesNotClaimNoNetworkRequest() {
        val requests=AtomicInteger();val opens=AtomicInteger()
        val bound=BoundSummaryModel(binding,SummaryModelTransport { _, _ -> object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                requests.incrementAndGet();sink(ModelEvent.TextFinal("summary","已确认事实"))
            }
            override fun cancel() {}
        } },100000,90000,{SummaryRequestDeadline(60000)})
        lateinit var f: Fixture
        lateinit var owner: Conversation
        f=fresh(CompressionSettings(true,10000,1000,10,2,binding),SummaryModelResolver {bound}) {
            // Summary has already run; only the final send CAS loses to a newer draft.
            f.db.draft(owner.id,"newer draft")
            ModelPort {opens.incrementAndGet();session()}
        }
        owner=seed(f);f.db.draft(owner.id,"pending")
        assertTrue(send(f,owner,"pending") is ChatController.TextSendResult.Rejected)
        assertEquals(1,requests.get());assertEquals(0,opens.get())
        // This assertion includes the companion production wording fix integrated by the parent.
        assertEquals("会话、原文或编辑草稿已变化；保留当前草稿，未启动聊天模型请求",f.controller.problem)
        assertEquals("newer draft",f.db.conversations().single().draft)
        assertEquals(12,ContextService(f.app,f.db).displayHistory(owner.id).size)
    }

    fun testCancelTextSendOnlyMatchingTicketKeepsReservationUntilSummaryWorkerExits() {
        val entered=CountDownLatch(1);val cancelled=CountDownLatch(1);val release=CountDownLatch(1)
        val factories=AtomicInteger()
        val bound=BoundSummaryModel(binding,SummaryModelTransport { _, _ -> object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS))
                sink(ModelEvent.TextFinal("summary","must not publish"))
            }
            override fun cancel() {cancelled.countDown()}
        } },100000,90000,{SummaryRequestDeadline(60000)})
        val f=fresh(CompressionSettings(true,10000,1000,10,2,binding),SummaryModelResolver {bound}) {
            factories.incrementAndGet();ModelPort {session()}
        }
        val owner=seed(f);val service=ContextService(f.app,f.db)
        val before=service.prepareSend(owner.id)
        f.db.draft(owner.id,"pending")
        val ticket=ChatController.TextSendTicket()
        val result=AtomicReference<ChatController.TextSendResult>();val done=CountDownLatch(1)
        try {
            main {assertTrue(f.controller.sendText(owner,1,2,"pending",ticket=ticket) {
                result.set(it);done.countDown()
            })}
            await(entered)
            main {
                assertFalse(f.controller.cancelTextSend(ChatController.TextSendTicket()))
                assertEquals(1L,cancelled.count)
                assertTrue(f.controller.cancelTextSend(ticket))
            }
            await(cancelled)
            main {
                val pending=checkNotNull(f.controller.textSendState())
                assertSame(ticket,pending.ticket);assertNull(pending.result)
                f.controller.acknowledgeTextSend(ticket)
                assertSame(pending,f.controller.textSendState())
                assertFalse(f.controller.sendText(owner,1,3,"duplicate") {fail("not admitted")})
            }
            // Legacy worker admission does not consult pendingTextState: this proves the
            // preparation reservation itself survives cancellation until the old worker exits.
            assertNull(f.controller.send(owner,"duplicate"))
            assertEquals(1L,done.count)
            assertEquals("pending",f.db.conversations().single().draft)
            assertEquals(before,service.prepareSend(owner.id))
        } finally {release.countDown()}
        await(done)
        assertTrue(result.get() is ChatController.TextSendResult.Rejected)
        assertEquals(before,service.prepareSend(owner.id));assertEquals(0,factories.get())
        assertEquals("pending",f.db.conversations().single().draft)
        assertEquals("上下文准备已取消；草稿保留，未启动聊天模型请求",f.controller.problem)
    }

    fun testCancelTextSendAfterCommitGateKeepsAcceptedReceipt() {
        val opens=AtomicInteger();val cancellations=AtomicInteger()
        val onWritten = AtomicReference<(() -> Unit)?>(null)
        val f=fresh(afterSendRowsWritten = { onWritten.get()?.invoke() }) {ModelPort {opens.incrementAndGet();session()}}
        val owner=seed(f,1);f.db.draft(owner.id,"pending")
        val ticket=ChatController.TextSendTicket()
        // The real SQLite insert runs after beginCommit but before returning its receipt.
        onWritten.set {
            main {
                assertTrue(f.controller.cancelTextSend(ticket));cancellations.incrementAndGet()
                assertNull(checkNotNull(f.controller.textSendState()).result)
            }
        }
        val result=send(f,owner,"pending",ticket)
        assertTrue(result is ChatController.TextSendResult.Accepted)
        assertEquals(1,cancellations.get());assertEquals(0,opens.get());assertNull(f.controller.active)
        assertEquals("",f.db.conversations().single().draft)
        val rows=ContextService(f.app,f.db).displayHistory(owner.id)
        assertEquals(4,rows.size)
        assertEquals(RunStatus.INTERRUPTED,rows.last().status)
        main {
            assertSame(result,checkNotNull(f.controller.textSendState()).result)
            assertFalse(f.controller.cancelTextSend(ticket))
            assertSame(result,checkNotNull(f.controller.textSendState()).result)
        }
    }

    fun testProductionFactoryRejectsMissingSummaryProfileWithoutChatFallback() {
        val factories=AtomicInteger()
        val f=fresh(production=true) {factories.incrementAndGet();ModelPort {session()}}
        // Real persisted v3 settings -> production policy -> independent profile store.
        // No injected coordinator/resolver in this test; missing credentials prevent transport I/O.
        ProductionCompressionSettingsStore(f.app).save(
            ProductionCompressionSettings(true,10000,1000,summaryModel = binding))
        val owner=seed(f);f.db.draft(owner.id,"pending")
        assertTrue(send(f,owner,"pending") is ChatController.TextSendResult.Rejected)
        assertEquals("独立摘要配置不可用（MISSING）；请重新保存，草稿保留，未回退聊天模型",f.controller.problem)
        assertEquals(0,factories.get());assertNull(f.controller.active)
        assertEquals("pending",f.db.conversations().single().draft)
        assertEquals(12,ContextService(f.app,f.db).displayHistory(owner.id).size)
    }

    fun testCloseAfterCommitGateReturnsReceiptWithoutStartingRunner() {
        val opens=AtomicInteger()
        val onWritten = AtomicReference<(() -> Unit)?>(null)
        val f=fresh(afterSendRowsWritten = { onWritten.get()?.invoke() }) {ModelPort {opens.incrementAndGet();session()}}
        val owner=seed(f,1);f.db.draft(owner.id,"pending")
        // Fires inside the real send transaction after beginCommit, before its receipt returns.
        onWritten.set { main {f.controller.close()} }
        assertTrue(send(f,owner,"pending") is ChatController.TextSendResult.Accepted)
        assertEquals(0,opens.get());assertNull(f.controller.active)
    }

    fun testLegacySynchronousMainThreadEntryRejectsWithoutIoAndWorkerRemainsCompatible() {
        val factories=AtomicInteger();val opened=CountDownLatch(1)
        val f=fresh {factories.incrementAndGet();ModelPort {opened.countDown();session()}}
        f.db.draft("legacy")
        main {assertNull(f.controller.send(null,"legacy"))}
        assertEquals(0,factories.get());assertEquals("legacy",f.db.draft())
        // Instrumentation test thread is a worker, not the main Looper.
        assertNotNull(f.controller.send(null,"legacy"))
        await(opened)
    }
}
