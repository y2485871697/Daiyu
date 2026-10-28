package app.daiyu.android

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.ExecutionReceipt
import app.daiyu.android.platform.actions.*
import app.daiyu.android.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real original Controller/SQLite/CAS/Runner/catalog/receipt. No device writes or permission grants. */
@Suppress("DEPRECATION")
class ControllerPlatformActionSendTest : InstrumentationTestCase() {
    private class App(base: Context) : Application() {
        val balances = fixtureProviderBalances()
        private val root = File(base.cacheDir, "platform-actions-${UUID.randomUUID()}").apply { mkdirs() }
        init { attachBaseContext(base) }
        override fun getFilesDir() = root
    }
    private lateinit var app: App
    private lateinit var db: ConversationDatabase
    private lateinit var controller: ChatController
    private lateinit var alias: String
    private val backendCalls = AtomicInteger()
    private val opens = AtomicInteger()
    private val definitions = AtomicReference<List<ToolDefinition>>()
    private val text = "  exact platform action draft\n  "
    private val json = """{"stream":"music","index":3}"""
    override fun setUp() {
        super.setUp()
        app = App(testContext(instrumentation.targetContext, "platform-actions-${UUID.randomUUID()}-"))
        db = ConversationDatabase(app)
        alias = "app.daiyu.android.test.platform.${UUID.randomUUID()}"
        val settings = ModelSettingsStore(app, alias)
        settings.save(ModelSettings("https://example.invalid", "offline-fixture", "synthetic-key", false))
        val backend = object : PlatformActionBackend {
            override fun unavailable(kind: PlatformActionKind): String? = null
            override fun execute(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt {
                backendCalls.incrementAndGet()
                assertEquals(PlatformActionKind.VOLUME, action.kind)
                assertEquals(3L, action.arguments.integer("index"))
                return ExecutionReceipt.Confirmed("{\"stream\":\"music\",\"index\":3,\"read_back\":true}")
            }
        }
        val port = object : CatalogFixturePort {
            override fun open(history: List<Message>): ModelSession = error("catalog definitions required")
            override fun open(history: List<Message>, tools: List<ToolDefinition>): ModelSession {
                opens.incrementAndGet(); definitions.set(tools)
                val approved = tools.any { it.name == PlatformActionKind.VOLUME.toolName }
                return object : ModelSession {
                    private var turn = 0
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        if (approved && turn++ == 0) sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("exact-call"),
                            PlatformActionKind.VOLUME.toolName, json)))
                        else sink(ModelEvent.TextDelta("done", "offline complete"))
                    }
                    override fun cancel() {}
                }
            }
        }
        controller = ChatController(app, db, settings, app.getSharedPreferences("conversation-ui", 0),
            { port }, platformActions = backend, providerBalanceHost = { app.balances })
        awaitControl("ready") { controller.ready }
        assertNull(controller.problem)
        db.draft(text)
    }
    override fun tearDown() {
        try {
            if (::controller.isInitialized) { controller.stop(); controller.close() }
            if (::app.isInitialized) app.balances.close()
            if (::db.isInitialized) { db.close(); app.deleteDatabase("conversations.db") }
            if (::app.isInitialized) app.filesDir.deleteRecursively()
            if (::alias.isInitialized) {
                val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                java.util.Collections.list(keys.aliases()).filter { it == alias || it.startsWith("$alias.") }.forEach(keys::deleteEntry)
            }
        } finally { super.tearDown() }
    }
    private fun prepare(): ControllerPlatformActionPreparation {
        val ref = AtomicReference<ControllerPlatformActionPreparation>()
        controlMain { ref.set(controller.preparePlatformAction(null, text, PlatformActionKind.VOLUME, json)) }
        return checkNotNull(ref.get())
    }
    private fun confirm(preview: ControllerPlatformActionPreparation): Conversation? {
        val done = CountDownLatch(1)
        val saved = AtomicReference<Conversation?>()
        controlMain { assertTrue(controller.confirmPlatformAction(preview) { saved.set(it); done.countDown() }) }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return saved.get()
    }
    fun testExactApprovalUsesOriginalCasThenCatalogAndDurableReceipt() {
        val preview = prepare()
        assertFalse(preview.submitted); assertNull(preview.scope)
        assertEquals(text, db.draft()); assertTrue(db.conversations().isEmpty())
        assertEquals(0, opens.get()); assertEquals(0, backendCalls.get())
        val saved = checkNotNull(confirm(preview))
        assertEquals(preview.conversationId, saved.id)
        awaitControl("parent settled") { controller.active == null && ChatController::class.java.getDeclaredField("preparation").apply { isAccessible = true }.get(controller) == null }
        assertEquals("", db.draft()); assertEquals(1, opens.get()); assertEquals(1, backendCalls.get())
        assertTrue(definitions.get().any { it.name == PlatformActionKind.VOLUME.toolName && it.sideEffect })
        val rows = db.messages(saved.id).map { it.second }
        assertEquals(preview.messageText, rows.first().blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertEquals(preview.runId, rows.first().runId)
        db.readableDatabase.rawQuery("SELECT state,task_id FROM tool_ledger WHERE call_id=?", arrayOf("exact-call")).use {
            assertTrue(it.moveToFirst()); assertEquals("CONFIRMED", it.getString(0)); assertEquals(preview.runId.value, it.getString(1))
        }
        controlMain { assertFalse(controller.confirmPlatformAction(preview) {}) }
        assertEquals(1, backendCalls.get())
    }
    fun testChangedPersistedDraftFailsOriginalCasWithoutModelOpenOrDeviceAction() {
        val preview = prepare()
        db.draft("new draft must survive")
        assertNull(confirm(preview))
        awaitControl("preparation released") { ChatController::class.java.getDeclaredField("preparation").apply { isAccessible = true }.get(controller) == null }
        assertEquals("new draft must survive", db.draft())
        assertTrue(db.conversations().isEmpty()); assertEquals(0, opens.get()); assertEquals(0, backendCalls.get())
    }
    fun testRejectAndOrdinarySendDoNotInheritPlatformApproval() {
        val preview = prepare()
        controlMain { controller.rejectPlatformAction(preview) }
        awaitControl("reject released") { ChatController::class.java.getDeclaredField("preparation").apply { isAccessible = true }.get(controller) == null }
        controlMain { assertFalse(controller.confirmPlatformAction(preview) {}) }
        assertEquals(text, db.draft()); assertEquals(0, opens.get()); assertEquals(0, backendCalls.get())
        val saved = controller.send(null, text)
        assertNotNull(saved)
        awaitControl("ordinary settled") { controller.active == null && ChatController::class.java.getDeclaredField("preparation").apply { isAccessible = true }.get(controller) == null }
        assertFalse(definitions.get().any { it.name.startsWith("platform_set_") || it.name == "platform_media_key" })
        assertEquals(0, backendCalls.get())
    }
}
