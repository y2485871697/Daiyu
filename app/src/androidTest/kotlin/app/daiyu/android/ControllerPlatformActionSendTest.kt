package app.daiyu.android

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.ExecutionReceipt
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.integration.tools.PlatformActionScope
import app.daiyu.android.integration.tools.RunCatalogTools
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
    private data class CapturedRun(
        val catalog: RunCatalogTools,
        val owner: RunExecutionOwner,
        val authorization: TaskAuthorization,
        val platformAction: PlatformActionScope?,
    )
    private val capturedRun = AtomicReference<CapturedRun>()
    private val exactApprovalText = AtomicReference<String>()
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
                val catalog = field(controller, "runTools") as RunCatalogTools
                capturedRun.set(CapturedRun(catalog,
                    field(catalog, "owner") as RunExecutionOwner,
                    field(catalog, "boundAuthorization") as TaskAuthorization,
                    field(catalog, "platformAction") as PlatformActionScope?))
                val lastUserText = history.lastOrNull { it.role == Message.USER_ROLE }
                    ?.blocks?.filterIsInstance<MessageBlock.Text>()?.singleOrNull()?.text
                val approved = lastUserText == exactApprovalText.get() &&
                    tools.any { it.name == PlatformActionKind.VOLUME.toolName }
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
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun assertOrdinaryRun(run: CapturedRun, assistant: Message,
        preview: ControllerPlatformActionPreparation) {
        assertTrue(run.owner.owns(assistant))
        assertEquals(assistant.runId.value, run.authorization.sessionId)
        assertEquals(assistant.runId.value, run.authorization.taskId)
        assertFalse(preview.runId == assistant.runId)
        assertNull(run.platformAction)
        assertTrue(run.authorization.allowedWrites.isEmpty())
        assertFalse(preview.action.effect in run.authorization.allowedWrites)
        preview.scope?.let { assertNotSame(it.authorization, run.authorization) }
    }

    private fun prepare(): ControllerPlatformActionPreparation {
        val ref = AtomicReference<ControllerPlatformActionPreparation>()
        controlMain { ref.set(controller.preparePlatformAction(null, text, PlatformActionKind.VOLUME, json)) }
        return checkNotNull(ref.get()).also { exactApprovalText.set(it.messageText) }
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
        val captured = checkNotNull(capturedRun.get())
        assertSame(checkNotNull(preview.scope), captured.platformAction)
        assertSame(checkNotNull(preview.scope).authorization, captured.authorization)
        assertEquals(preview.runId.value, captured.authorization.sessionId)
        assertEquals(preview.runId.value, captured.authorization.taskId)
        assertEquals(setOf(preview.action.effect), captured.authorization.allowedWrites)
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
        assertTrue(preview.cancelled)
        assertFalse(preview.submitted)
        assertNull(preview.scope)
        assertTrue(definitions.get().any { it.name == PlatformActionKind.VOLUME.toolName && it.sideEffect })
        assertEquals(1, opens.get())
        val rows = db.messages(checkNotNull(saved).id).map { it.second }
        val user = rows.first { it.role == Message.USER_ROLE }
        assertOrdinaryRun(checkNotNull(capturedRun.get()),
            rows.single { it.role == Message.ASSISTANT_ROLE }, preview)
        assertEquals(text, user.blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertFalse(user.runId == preview.runId)
        db.readableDatabase.rawQuery("SELECT 1 FROM tool_ledger WHERE call_id=?", arrayOf("exact-call")).use {
            assertFalse("rejected exact invocation must not enter the ledger", it.moveToFirst())
        }
        assertEquals(0, backendCalls.get())
    }

    fun testOrdinaryRunAfterConsumedApprovalHasFreshAuthorizationAndNoExactScope() {
        val preview = prepare()
        val saved = checkNotNull(confirm(preview))
        awaitControl("approved settled") {
            controller.active == null && field(controller, "preparation") == null
        }
        val original = checkNotNull(capturedRun.get())
        val originalScope = checkNotNull(preview.scope)
        assertSame(originalScope, original.platformAction)
        assertSame(originalScope.authorization, original.authorization)
        assertEquals(setOf(preview.action.effect), original.authorization.allowedWrites)
        assertEquals(1, opens.get())
        assertEquals(1, backendCalls.get())

        db.draft(saved.id, text)
        assertNotNull(controller.send(saved, text))
        awaitControl("ordinary after approval settled") {
            controller.active == null && field(controller, "preparation") == null
        }
        val ordinary = checkNotNull(capturedRun.get())
        assertNotSame(original.catalog, ordinary.catalog)
        assertNotSame(original.owner, ordinary.owner)
        assertNotSame(original.authorization, ordinary.authorization)
        val rows = db.messages(saved.id).map { it.second }
        assertOrdinaryRun(ordinary, rows.last { it.role == Message.ASSISTANT_ROLE }, preview)
        assertEquals(text, rows.last { it.role == Message.USER_ROLE }
            .blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertTrue(definitions.get().any { it.name == PlatformActionKind.VOLUME.toolName && it.sideEffect })
        assertEquals(2, opens.get())
        controlMain { assertFalse(controller.confirmPlatformAction(preview) {}) }
        assertEquals(1, backendCalls.get())
    }
}
