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
        factory: (ModelSettings) -> ModelPort): Fixture {
        val id = UUID.randomUUID().toString()
        val app = FixtureApplication(testContext(instrumentation.targetContext,"controller-context-$id-"))
        val db = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.context.$id"
        val settings = ModelSettingsStore(app,alias)
        settings.save(ModelSettings("https://example.invalid","context-fixture","synthetic-key",false))
        val controller = ChatController(app,db,settings,app.getSharedPreferences("conversation-ui",0),factory,
            contextPreparationFactory = { _, service ->
                ContextPreparationCoordinator(service, {
                    check(Looper.myLooper()!=Looper.getMainLooper())
                    config
                }, CompressionPolicyResolver { snapshot ->
                    // Explicit fixture policy, not a production message-to-turns conversion.
                    CompressionPolicy(snapshot.automaticWithKnownWindow,snapshot.contextWindowTokens,
                        snapshot.triggerPercent,snapshot.reserveTokens,2)
                },summary)
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
    private fun send(f: Fixture, conversation: Conversation?, text: String): ChatController.TextSendResult {
        val result = AtomicReference<ChatController.TextSendResult>()
        val done = CountDownLatch(1)
        main { assertTrue(f.controller.sendText(conversation,17,23,text) {
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
        val history = AtomicReference<List<Message>>()
        val summary = BoundSummaryModel(binding,SummaryModelTransport { _, _ -> object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.TextFinal("summary","已确认事实"))
            }
            override fun cancel() {}
        } },100000,90000,{SummaryRequestDeadline(60000)})
        val f = fresh(CompressionSettings(true,10000,1000,10,2,binding),SummaryModelResolver {summary}) {
            ModelPort { input -> history.set(input);opened.countDown();session() }
        }
        val owner=seed(f);f.db.draft(owner.id,"pending-once")
        assertTrue(send(f,owner,"pending-once") is ChatController.TextSendResult.Accepted)
        await(opened)
        val input=history.get()
        assertTrue(input.first().id.value.startsWith("summary-"))
        assertEquals(1,input.count {row -> row.blocks.filterIsInstance<MessageBlock.Text>().any {it.text=="pending-once"}})
        val service=ContextService(f.app,f.db)
        assertNotNull(service.prepareSend(owner.id).archiveId)
        // Seed originals remain intact; only the new user/assistant pair was appended.
        assertEquals(14,service.displayHistory(owner.id).size)
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
            f.db.save(first.copy(blocks=listOf(MessageBlock.Text("changed","concurrent original edit"))))
            ModelPort {error("CAS conflict must not open")}
        }
        owner=seed(f,32);f.db.draft(owner.id,"pending")
        assertTrue(send(f,owner,"pending") is ChatController.TextSendResult.Rejected)
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

    fun testCloseAfterCommitGateReturnsReceiptWithoutStartingRunner() {
        val opens=AtomicInteger()
        val f=fresh {ModelPort {opens.incrementAndGet();session()}}
        val owner=seed(f,1);f.db.draft(owner.id,"pending")
        // Fires inside the real send transaction after beginCommit, before its receipt returns.
        f.db.writableDatabase.addCustomFunction("close_prepared",0) { main {f.controller.close()} }
        f.db.writableDatabase.execSQL("CREATE TRIGGER close_after_accept AFTER INSERT ON messages " +
            "WHEN NEW.role='assistant' BEGIN SELECT close_prepared(); END")
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
