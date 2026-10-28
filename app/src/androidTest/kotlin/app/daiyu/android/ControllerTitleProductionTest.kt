package app.daiyu.android

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.context.ContextService
import app.daiyu.android.domain.*
import app.daiyu.android.storage.*
import app.daiyu.android.title.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real sendPrepared/SQLite/Runner, synthetic transports only. No provider requests. */
@Suppress("DEPRECATION")
class ControllerTitleProductionTest : InstrumentationTestCase() {
    private class FixtureApplication(base: Context) : Application() {
        val balances = fixtureProviderBalances()
        init { attachBaseContext(base) }
        private val files = File(base.cacheDir, "controller-title-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getFilesDir(): File = files
        override fun getApplicationContext(): Context = this
    }
    private class Fixture(val app: FixtureApplication, val db: ConversationDatabase, val controller: ChatController,
        val titleStore: TitleModelSettingsStore, val entered: CountDownLatch, val release: CountDownLatch,
        val cancelled: AtomicInteger, val calls: AtomicInteger)
    private val fixtures = mutableListOf<Fixture>()
    private fun main(work: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { work() } catch (t: Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }
    }
    private fun await(latch: CountDownLatch) = assertTrue("timeout", latch.await(10, TimeUnit.SECONDS))
    private fun state(f: Fixture, condition: () -> Boolean) {
        val done = CountDownLatch(1)
        val listener: () -> Unit = { if (condition()) done.countDown() }
        main { f.controller.observe(listener); listener() }
        try { await(done) } finally { main { f.controller.unobserve(listener) } }
    }
    private fun fresh(parentStatus: RunStatus = RunStatus.COMPLETED, customTitle: Boolean = true): Fixture {
        val id = UUID.randomUUID().toString()
        val app = FixtureApplication(testContext(instrumentation.targetContext, "controller-title-$id-"))
        val db = ConversationDatabase(app)
        val settings = ModelSettingsStore(app, "synthetic-title-main-$id")
        settings.save(ModelSettings("https://main.invalid", "synthetic-main", "synthetic-key", false))
        var stored: ModelSettings? = null
        val titleStore = TitleModelSettingsStore(app.getSharedPreferences("title-fixture", 0),
            { checkNotNull(stored) }, { p, _, _ -> stored = p }, "title-fixture-$id")
        if (customTitle) titleStore.save(TitleModelProfile(TitleModelBinding("synthetic-title", "fixed"),
            ModelSettings("https://title.invalid", "synthetic-title", "synthetic-key", false)))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cancelled = AtomicInteger(); val calls = AtomicInteger()
        val controller = ChatController(app, db, settings, app.getSharedPreferences("conversation-ui", 0),
            model = { CatalogFixturePort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (parentStatus == RunStatus.FAILED) error("synthetic parent failure")
                    sink(ModelEvent.TextFinal("a", "已保存回答"))
                }
                override fun cancel() { }
            } } }, titleModelFactory = { frozen ->
                assertEquals(if (customTitle) "synthetic-title" else "synthetic-main", frozen.model)
                CatalogFixturePort { object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        calls.incrementAndGet(); entered.countDown(); await(release)
                        sink(ModelEvent.Usage(7, null, 1))
                        sink(ModelEvent.TextFinal("t", "生产标题"))
                    }
                    override fun cancel() { cancelled.incrementAndGet() }
                } }
            }, titleSettingsOverride = titleStore, providerBalanceHost = { app.balances })
        return Fixture(app, db, controller, titleStore, entered, release, cancelled, calls).also {
            fixtures.add(it); state(it) { controller.ready }; assertNull(controller.problem)
        }
    }
    private fun send(f: Fixture, conversation: Conversation? = null): Conversation {
        if (conversation == null) f.db.draft("首问") else f.db.draft(conversation.id, "后问")
        val done = CountDownLatch(1)
        val result = AtomicReference<ChatController.TextSendResult>()
        val ticket = ChatController.TextSendTicket()
        main { assertTrue(f.controller.sendText(conversation, 1, 1, if (conversation == null) "首问" else "后问", ticket) {
            result.set(it); f.controller.acknowledgeTextSend(ticket); done.countDown()
        }) }
        await(done)
        return (result.get() as ChatController.TextSendResult.Accepted).conversation
    }
    override fun tearDown() {
        try { fixtures.forEach {
            it.release.countDown(); it.controller.close(); it.app.balances.close(); it.db.close()
            it.app.deleteDatabase("conversations.db"); it.app.filesDir.deleteRecursively()
        } } finally { super.tearDown() }
    }

    fun testUnconfiguredTitleUsesTheAcceptedChatModelAfterPersist() {
        val f = fresh(customTitle = false)
        send(f); await(f.entered)
        f.release.countDown(); state(f) { f.controller.titleRevision == 1L }
        assertEquals("生产标题", f.db.conversations().single().title)
        assertEquals(1, f.calls.get())
    }

    fun testNewFirstTurnRunsExactlyOnceAfterPersistAndSurvivesNormalCleanup() {
        val f = fresh(); val saved = send(f); await(f.entered)
        assertEquals(RunStatus.COMPLETED, f.db.messages(saved.id).last().second.status)
        state(f) { f.controller.active == null }
        // A separate title worker neither occupies the chat lane nor gets revoked by cleanupRun.
        send(f, f.db.conversations().single()); state(f) { f.controller.active == null }
        assertEquals(1, f.calls.get()); assertEquals(0, f.cancelled.get())
        f.release.countDown(); state(f) { f.controller.titleRevision == 1L }
        assertEquals("生产标题", f.db.conversations().single().title)
        assertEquals(1L, ContextService(f.app, f.db).usage(saved.id.value).requests)
    }

    fun testSameValueRenameWhileTitleHeldWinsButActualUsageIsRecorded() {
        val f = fresh(); val saved = send(f); await(f.entered)
        state(f) { f.controller.active == null }
        f.db.rename(saved.id, "新会话")
        f.release.countDown(); state(f) { f.controller.titleNotice != null }
        assertEquals("新会话", f.db.conversations().single().title)
        assertEquals(1L, ContextService(f.app, f.db).usage(saved.id.value).requests)
        assertEquals(0L, f.controller.titleRevision); assertEquals(1, f.calls.get())
    }

    fun testCloseDoesNotJoinUiAndKeepsStorageUntilHeldTitleWorkerActuallyExits() {
        val f = fresh(); send(f); await(f.entered); state(f) { f.controller.active == null }
        val sql = f.db.writableDatabase
        main { f.controller.close() } // cancel is deliberately not a fake drain acknowledgment
        assertTrue(f.cancelled.get() > 0)
        assertTrue("storage must remain alive for late usage persistence", sql.isOpen)
        f.release.countDown(); f.controller.close() // instrumentation worker may wait for drain
        assertFalse(sql.isOpen)
    }

    fun testDisableHeldProductionRequestCancelsWithoutChangingAcceptedChat() {
        val f = fresh(); val saved = send(f); await(f.entered)
        f.titleStore.disable(); assertTrue(f.cancelled.get() > 0)
        state(f) { f.controller.active == null }
        assertEquals(RunStatus.COMPLETED, f.db.messages(saved.id).last().second.status)
        f.release.countDown(); state(f) { f.controller.titleNotice != null }
        assertEquals("新会话", f.db.conversations().single().title)
        assertEquals(1L, ContextService(f.app, f.db).usage(saved.id.value).requests)
    }

    fun testPersistedEmptyConversationFirstTurnNamesOnceWithoutCreatingAnotherConversation() {
        val f = fresh(); val empty = f.db.create("default")
        assertEquals(0, f.calls.get())
        val accepted = send(f, empty); await(f.entered)
        state(f) { f.controller.active == null }
        assertEquals(empty.id, accepted.id)
        assertEquals(empty.id, f.db.conversations().single().id)
        send(f, f.db.conversations().single()); state(f) { f.controller.active == null }
        assertEquals(1, f.calls.get())
        f.release.countDown(); state(f) { f.controller.titleRevision == 1L }
        assertEquals("生产标题", f.db.conversations().single().title)
    }

    fun testRenamedEmptyConversationAndFailedFirstTurnNeverStartTitle() {
        val renamed = fresh(); val empty = renamed.db.create("default")
        renamed.db.rename(empty.id, "自定标题")
        send(renamed, empty); state(renamed) { renamed.controller.active == null }
        assertEquals(0, renamed.calls.get())
        val failed = fresh(RunStatus.FAILED); val accepted = send(failed)
        state(failed) { failed.controller.active == null }
        assertEquals(RunStatus.FAILED, failed.db.messages(accepted.id).last().second.status)
        assertEquals(0, failed.calls.get())
    }
}
