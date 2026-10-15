package app.daiyu.android

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ControllerModelPreparationTest : InstrumentationTestCase() {
    private class FixtureApplication(base: Context) : Application() {
        init { attachBaseContext(base) }
    }
    private data class Fixture(val app: FixtureApplication, val db: ConversationDatabase,
        val settings: ModelSettingsStore, val alias: String, val controller: ChatController)
    private val fixtures = mutableListOf<Fixture>()
    private fun main(work: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { work() } catch (t: Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }
    }
    private fun awaitState(f: Fixture, predicate: () -> Boolean) {
        val done = CountDownLatch(1)
        val listener: () -> Unit = { if (predicate()) done.countDown() }
        main { f.controller.observe(listener); listener() }
        try {
            assertTrue("controller state timeout", done.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        } finally { main { f.controller.unobserve(listener) } }
    }
    private fun fresh(factory: (ModelSettings) -> ModelPort): Fixture {
        val id = UUID.randomUUID().toString()
        val app = FixtureApplication(testContext(instrumentation.targetContext, "controller-model-$id-"))
        val db = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.model.$id"
        val settings = ModelSettingsStore(app, alias)
        settings.save(ModelSettings("https://example.invalid", "fixture-model", "fixture-key", true))
        val controller = ChatController(app, db, settings, app.getSharedPreferences("conversation-ui", 0), factory)
        val f = Fixture(app, db, settings, alias, controller)
        fixtures.add(f)
        awaitState(f) { controller.ready }
        assertNull(controller.problem)
        return f
    }
    private fun session(text: String) = object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            sink(ModelEvent.TextDelta("body", text))
        }
        override fun cancel() {}
    }
    override fun tearDown() {
        try {
            fixtures.forEach { f ->
                f.controller.stop(); f.controller.close(); f.db.close()
                f.app.deleteDatabase("conversations.db")
                f.app.getSharedPreferences("model-settings", 0).edit().clear().commit()
                f.app.getSharedPreferences("conversation-ui", 0).edit().clear().commit()
                val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                java.util.Collections.list(keys.aliases()).filter { it.startsWith("${f.alias}.") }
                    .forEach { keys.deleteEntry(it) }
            }
        } finally { super.tearDown() }
    }

    fun testFactoryFailurePreservesBothDraftsAndCanRetryWithoutGhostMessages() {
        var reject = true
        val factories = AtomicInteger(); val opens = AtomicInteger()
        val f = fresh {
            factories.incrementAndGet()
            if (reject) error("synthetic-secret-must-not-escape")
            ModelPort { opens.incrementAndGet(); session("accepted") }
        }
        val conversation = f.db.create()
        f.db.draft(conversation.id, "retained conversation draft"); f.db.draft("retained composer draft")
        val before = f.db.conversations()
        main {
            assertNull(f.controller.send(conversation, "retained conversation draft"))
            assertNull(f.controller.send(null, "retained composer draft"))
        }
        assertEquals(before, f.db.conversations())
        assertTrue(f.db.messages(conversation.id).isEmpty())
        assertEquals("retained composer draft", f.db.draft())
        assertNull(f.controller.active)
        assertEquals(2, factories.get()); assertEquals(0, opens.get())
        assertTrue(f.controller.problem!!.contains("草稿保留"))
        assertFalse(f.controller.problem!!.contains("synthetic-secret"))
        reject = false
        main { assertNotNull(f.controller.send(conversation, "accepted")) }
        awaitState(f) { f.controller.active == null }
        assertEquals(3, factories.get()); assertEquals(1, opens.get())
        assertEquals(2, f.db.messages(conversation.id).size)
        assertEquals(RunStatus.COMPLETED, f.db.messages(conversation.id).last().second.status)
        assertEquals("", f.db.conversations().single().draft)
    }

    fun testPreparationPrecedesReceiptAndUsesFrozenConfigurationAndTools() {
        lateinit var f: Fixture
        lateinit var conversation: Conversation
        val factories = AtomicInteger(); val opens = AtomicInteger()
        f = fresh { config ->
            factories.incrementAndGet()
            assertTrue(f.db.messages(conversation.id).isEmpty())
            assertEquals("keep until accepted", f.db.conversations().single().draft)
            assertNull(f.controller.send(conversation, "reentrant duplicate"))
            f.settings.save(config.copy(model="later-model", apiKey="later-key", batteryEnabled=false, deviceStatusEnabled=true))
            object : ModelPort {
                override fun open(history: List<Message>): ModelSession = error("explicit frozen tools required")
                override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession {
                    opens.incrementAndGet()
                    assertEquals(setOf(ReadOnlyTool.BATTERY), tools)
                    assertEquals("fixture-model", config.model)
                    assertEquals("fixture-key", config.apiKey)
                    return session(config.model)
                }
            }
        }
        conversation = f.db.create(); f.db.draft(conversation.id, "keep until accepted")
        main { assertNotNull(f.controller.send(conversation, "accepted")) }
        awaitState(f) { f.controller.active == null }
        assertEquals(1, factories.get()); assertEquals(1, opens.get())
        assertEquals("later-model", f.settings.load().model)
        val messages = f.db.messages(conversation.id)
        assertEquals(2, messages.size)
        assertEquals("fixture-model", messages.last().second.blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertEquals(RunStatus.COMPLETED, messages.last().second.status)
    }

    fun testTransactionFailureNeverOpensPreparedPort() {
        val factories = AtomicInteger(); val opens = AtomicInteger()
        val f = fresh {
            factories.incrementAndGet()
            ModelPort { opens.incrementAndGet(); session("must not run") }
        }
        val conversation = f.db.create(); f.db.draft(conversation.id, "retained")
        f.db.writableDatabase.execSQL("CREATE TRIGGER reject_send BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        main { assertNull(f.controller.send(conversation, "retained")) }
        assertEquals(1, factories.get()); assertEquals(0, opens.get())
        assertTrue(f.db.messages(conversation.id).isEmpty())
        assertEquals("retained", f.db.conversations().single().draft)
        assertNull(f.controller.active)
    }
}
