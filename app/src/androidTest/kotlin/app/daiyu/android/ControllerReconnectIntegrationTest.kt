package app.daiyu.android

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunnerOptions
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.reconnect.*
import app.daiyu.android.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real preparation/SQLite/Runner with synthetic transports only. Source added, not executed. */
@Suppress("DEPRECATION")
class ControllerReconnectIntegrationTest : InstrumentationTestCase() {
    private class App(base: Context) : Application() {
        private val root = File(base.cacheDir, "reconnect-${UUID.randomUUID()}").apply { mkdirs() }
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = root
    }
    private data class Fixture(val app: App, val db: ConversationDatabase,
        val controller: ChatController, val settings: ModelSettingsStore, val alias: String)
    private val fixtures = mutableListOf<Fixture>()

    private fun fresh(protocol: ProviderProtocol = ProviderProtocol.RESPONSES,
        factory: (ModelSettings) -> ModelPort): Fixture {
        val id = UUID.randomUUID().toString()
        val app = App(testContext(instrumentation.targetContext, "reconnect-$id-"))
        val db = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.reconnect.$id"
        val settings = ModelSettingsStore(app, alias)
        settings.save(ModelSettings("https://example.invalid", "fixture", "synthetic-key", false, protocol = protocol))
        val controller = ChatController(app, db, settings, app.getSharedPreferences("conversation-ui", 0), factory)
        val fixture = Fixture(app, db, controller, settings, alias)
        fixtures.add(fixture)
        awaitControl("controller ready") { controller.ready }
        assertNull(controller.problem)
        return fixture
    }
    private fun await(latch: CountDownLatch) = assertTrue("timed out", latch.await(10, TimeUnit.SECONDS))
    private fun send(f: Fixture, text: String = "pending"): ChatController.TextSendResult {
        f.db.draft(text)
        val result = AtomicReference<ChatController.TextSendResult>()
        val done = CountDownLatch(1)
        controlMain { assertTrue(f.controller.sendText(null, 1, 2, text) { result.set(it); done.countDown() }) }
        await(done)
        return result.get()
    }
    private fun runner(f: Fixture): ChatRunner = field(f.controller, "runner") as ChatRunner
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
    private fun diagnostic(protocol: ProviderProtocol) = ReconnectCapabilities.forProtocol(protocol).userMessage

    private class FailingSession : ModelSession {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            reads.incrementAndGet()
            sink(ModelEvent.TextDelta("body", "retained partial"))
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS))
            throw ModelRequestException(ModelFailure.NETWORK)
        }
        override fun cancel() { release.countDown() }
    }

    override fun tearDown() {
        try { fixtures.forEach { f ->
            f.controller.stop(); f.controller.close(); f.db.close()
            f.app.deleteDatabase("conversations.db"); f.app.filesDir.deleteRecursively()
            // Only synthetic test-owned aliases are removed; never use a production prefix.
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it.startsWith("${f.alias}.") }
                .forEach { keys.deleteEntry(it) }
        } } finally { super.tearDown() }
    }

    fun testEveryPresetAndOrdinaryProtocolKeepsZeroRetriesAndPartialHistory() {
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            for (preset in ReconnectDurationPreset.entries) {
                val session = FailingSession()
                val opens = AtomicInteger()
                val f = fresh(protocol) { ModelPort { opens.incrementAndGet(); session } }
                f.controller.reconnectSettings.save(ReconnectSettings(preset))
                try {
                    assertTrue(send(f) is ChatController.TextSendResult.Accepted)
                    await(session.entered)
                    controlMain {
                        val options = field(runner(f), "options") as RunnerOptions
                        assertEquals(0, options.reconnect.maxAttempts)
                    }
                } finally { session.release.countDown() }
                awaitControl("run cleaned up") { f.controller.active == null }
                assertEquals(1, opens.get())
                assertEquals(1, session.reads.get())
                assertEquals(if (preset == ReconnectDurationPreset.NONE) null else diagnostic(protocol), f.controller.problem)
                val rows = f.db.messages(f.db.conversations().single().id).map { it.second }
                val answer = rows.single { it.role == "assistant" }
                assertEquals(RunStatus.FAILED, answer.status)
                assertTrue(answer.blocks.filterIsInstance<MessageBlock.Text>().any { "retained partial" in it.text })
            }
        }
    }

    fun testPreferenceAndProviderAreFrozenBeforeModelAssemblyAndNotRereadOnFailure() {
        for (initial in listOf(ReconnectDurationPreset.NONE, ReconnectDurationPreset.WINDOW_5M)) {
            val entered = CountDownLatch(1)
            val assemble = CountDownLatch(1)
            val session = FailingSession()
            val f = fresh(ProviderProtocol.MESSAGES) { config ->
                assertEquals(ProviderProtocol.MESSAGES, config.protocol)
                entered.countDown(); check(assemble.await(10, TimeUnit.SECONDS))
                ModelPort { session }
            }
            f.controller.reconnectSettings.save(ReconnectSettings(initial))
            f.db.draft("pending")
            val done = CountDownLatch(1)
            controlMain { assertTrue(f.controller.sendText(null, 1, 2, "pending") {
                assertTrue(it is ChatController.TextSendResult.Accepted); done.countDown()
            }) }
            try {
                await(entered)
                f.controller.reconnectSettings.save(ReconnectSettings(
                    if (initial == ReconnectDurationPreset.NONE) ReconnectDurationPreset.CONTINUOUS else ReconnectDurationPreset.NONE))
                f.settings.save(ModelSettings("https://example.invalid", "new-provider", "synthetic-key", false))
                assemble.countDown(); await(done); await(session.entered)
                // A late damaged global record cannot change or abort this already-frozen run.
                f.app.getSharedPreferences("daiyu-model-reconnect", 0).edit()
                    .putInt("reconnect-v1", 7).commit()
            } finally { assemble.countDown(); session.release.countDown() }
            awaitControl("frozen run completes") { f.controller.active == null }
            assertEquals(if (initial == ReconnectDurationPreset.NONE) null else diagnostic(ProviderProtocol.MESSAGES),
                f.controller.problem)
        }
    }

    fun testCorruptPreferenceRejectsBeforeFactoryAcceptanceOrModelOpenAndKeepsDraft() {
        val factories = AtomicInteger()
        val f = fresh { factories.incrementAndGet(); error("must not build a model") }
        f.app.getSharedPreferences("daiyu-model-reconnect", 0).edit().putInt("reconnect-v1", 7).commit()
        assertTrue(send(f) is ChatController.TextSendResult.Rejected)
        assertEquals(0, factories.get())
        assertTrue(f.db.conversations().isEmpty())
        assertEquals("pending", f.db.draft())
        assertTrue(f.controller.problem!!.contains("错误重连配置"))
    }

    fun testQueuedDiagnosticRechecksEveryOriginalIdentityAndLifetimeGateOnHandler() {
        val held = FailingSession()
        val opens = AtomicInteger()
        val f = fresh { ModelPort {
            // The first open is the real Runner. Subsequent calls are deliberate local probes
            // of its installed outer observer, with no provider IO, authorization or tool work.
            if (opens.incrementAndGet() == 1) held else throw ModelRequestException(ModelFailure.TIMEOUT)
        } }
        try {
            assertTrue(send(f) is ChatController.TextSendResult.Accepted)
            await(held.entered)
            lateinit var original: Message
            lateinit var resources: Any
            lateinit var owned: ChatRunner
            lateinit var observed: ModelPort
            controlMain {
                original = checkNotNull(f.controller.active)
                resources = checkNotNull(field(f.controller, "resources"))
                owned = runner(f)
                observed = field(owned, "model") as ModelPort
            }
            val faults: List<() -> Unit> = listOf(
                { set(f.controller, "active", original.copy(id = MessageId("replacement-message"))) },
                { set(f.controller, "active", original.copy(runId = RunId("replacement-run"))) },
                { set(f.controller, "active", original.copy(conversationId = ConversationId("replacement-conversation"))) },
                { set(f.controller, "resources", null) },
                { set(resources, "fenced", true) },
                { set(f.controller, "closed", true) },
                { set(f.controller, "runner", null) },
            )
            for (fault in faults) {
                controlMain {
                    try { observed.open(emptyList()); fail("probe must fail") }
                    catch (_: ModelRequestException) { }
                    // Observer posted, but cannot execute until this main-thread task exits.
                    fault()
                }
                try {
                    instrumentation.waitForIdleSync()
                    assertNull("stale diagnostic must not reach another owner", f.controller.problem)
                } finally { controlMain {
                    set(f.controller, "active", original); set(f.controller, "resources", resources)
                    set(resources, "fenced", false); set(f.controller, "closed", false)
                    set(f.controller, "runner", owned)
                } }
            }
            controlMain {
                try { observed.open(emptyList()); fail("probe must fail") }
                catch (_: ModelRequestException) { }
            }
            instrumentation.waitForIdleSync()
            assertEquals(diagnostic(ProviderProtocol.RESPONSES), f.controller.problem)
        } finally { held.release.countDown() }
        awaitControl("original run completes") { f.controller.active == null }
    }
}
