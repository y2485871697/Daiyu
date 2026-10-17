package app.daiyu.android

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.mcp.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual Controller admission, SQLite CAS, Runner, pause/resume/stop and startup recovery.
 * HTTP wire only is synthetic; no alternate runner, owner, authorization or dispatcher is used.
 */
@Suppress("DEPRECATION")
class ControllerMcpSendTest : InstrumentationTestCase() {
    private lateinit var fixture: RunControlFixture
    private lateinit var root: File
    private lateinit var store: McpSettingsStore
    private val connections = AtomicInteger()
    private val requests = AtomicInteger()
    private val controller get() = fixture.controller
    private val owner = ChatController.TextSendOwner(null, 7, 11)
    private val text = "  exact MCP draft\nwith trailing whitespace  "
    override fun setUp() {
        super.setUp()
        root = File(instrumentation.targetContext.cacheDir, "mcp-parent-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(testContext(instrumentation.targetContext, "mcp-${UUID.randomUUID()}-")) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        }
        fixture = RunControlFixture(isolated, mcpTransport = { server ->
            connections.incrementAndGet()
            // Transport factory is reached only on Runner schema setup AFTER durable acceptance.
            fixture.database.readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null).use {
                check(it.moveToFirst() && it.getInt(0) == 2)
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests.incrementAndGet()
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                val input = McpJson.objectValue(buffer.readUtf8())
                val result = when (input.getString("method")) {
                    "initialize" -> JSONObject("""{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}""")
                    "notifications/initialized" -> null
                    "tools/list" -> JSONObject("""{"tools":[{"name":"erase","inputSchema":{"type":"object"}}]}""")
                    else -> error("model must not invent an unapproved call")
                }
                val body = if (result == null) "" else JSONObject().put("jsonrpc", "2.0").put("id", input.getLong("id")).put("result", result).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                    .code(if (result == null) 202 else 200).header("Content-Type", "application/json")
                    .header("Mcp-Session-Id", "fixed-session").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            McpHttpSession(server, client)
        })
        awaitControl("controller ready") { controller.ready }
        store = McpSettingsStore(fixture.application)
        store.save(listOf(McpServer("server", "fixture", "https://fixture.invalid/mcp", "synthetic-token")), McpSettingsOwner(), store.snapshot())
        fixture.database.draft(text)
    }
    override fun tearDown() {
        try { if (::fixture.isInitialized) fixture.close(); if (::root.isInitialized) root.deleteRecursively() }
        finally { super.tearDown() }
    }
    private fun prepare() = checkNotNull(controller.prepareMcpSend(null, owner, text, "server", "erase", "{\"target\":\"one\"}"))
    private fun assertUncommitted() {
        assertEquals(text, fixture.database.draft()); assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, connections.get()); assertEquals(0, requests.get()); assertEquals(0, fixture.opens.get())
    }
    fun testSettingsAndPreviewNeverConnectAndMissingApprovalStaleDraftAndStaleConfigurationAreRejected() {
        val pending = prepare(); assertUncommitted()
        controlMain {
            assertFalse(controller.sendMcp(pending, owner, text, ChatController.TextSendTicket()) { fail("unapproved callback") })
            assertFalse(pending.confirm(pending, owner.copy(draftRevision = 12), text))
        }
        store.save(store.snapshot().servers, McpSettingsOwner(), store.snapshot())
        assertFalse(pending.confirm(pending, owner, text))
        controlMain { controller.rejectMcp(pending) }
        assertUncommitted()
        val next = prepare()
        controlMain { controller.rejectMcp(next) }
        assertUncommitted()
    }
    fun testOriginalDurableTicketPauseResumeAndStopDoNotReconnectMcpOrBroadenItsScope() {
        val pending = prepare()
        val first = fixture.session("late first", held = true, holdSeconds = 30)
        val second = fixture.session("after resume", held = true, holdSeconds = 30)
        val ticket = ChatController.TextSendTicket()
        val done = CountDownLatch(1); val receipt = AtomicReference<ChatController.TextSendResult>()
        controlMain {
            assertTrue(pending.confirm(pending, owner, text))
            assertTrue(controller.sendMcp(pending, owner, text, ticket) { receipt.set(it); done.countDown() })
        }
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(receipt.get() is ChatController.TextSendResult.Accepted)
        assertTrue(first.entered.await(10, TimeUnit.SECONDS)); assertEquals(1, connections.get()); assertEquals(3, requests.get())
        val accepted = receipt.get() as ChatController.TextSendResult.Accepted
        val rows = fixture.database.messages(accepted.conversation.id)
        assertEquals(listOf(MessageBlock.Text("user", text)), rows.single { it.second.role == "user" }.second.blocks)
        val assistant = rows.single { it.second.role == "assistant" }.second
        assertEquals(pending.scope.runId, assistant.runId); assertEquals(pending.assistantMessageId, assistant.id)
        controlMain {
            assertSame(ticket, controller.textSendState()!!.ticket)
            assertEquals(owner, controller.textSendState()!!.owner)
            assertFalse(controller.cancelTextSend(ticket)) // Durable receipt wins; not a new send.
            controller.acknowledgeTextSend(ticket)
            assertTrue(controller.pause(assistant))
        }
        awaitControl("original Runner paused") { controller.runState(assistant)?.phase == ExecutionPhase.PAUSED }
        assertEquals(3, requests.get())
        controlMain { assertTrue(controller.resume(assistant)) }
        assertTrue(second.entered.await(10, TimeUnit.SECONDS)); assertEquals(3, requests.get())
        controlMain { assertTrue(controller.stop(assistant)) }
        awaitControl("accepted run drained") { controller.active == null }
        assertEquals(1, connections.get()); assertEquals(3, requests.get())
        assertEquals(RunStatus.STOPPED, fixture.database.messages(accepted.conversation.id).single { it.second.id == assistant.id }.second.status)
        assertFalse(pending.confirm(pending, owner, text))
    }
    fun testPersistedDraftCasFailureAndRecoveryNeverCreateAReplacementMcpOwnerOrReopenTransport() {
        val pending = prepare()
        controlMain { assertTrue(pending.confirm(pending, owner, text)) }
        fixture.database.draft("edited after preview")
        val done = CountDownLatch(1); val receipt = AtomicReference<ChatController.TextSendResult>()
        controlMain { assertTrue(controller.sendMcp(pending, owner, text, ChatController.TextSendTicket()) { receipt.set(it); done.countDown() }) }
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(receipt.get() is ChatController.TextSendResult.Rejected)
        assertEquals("edited after preview", fixture.database.draft()); assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, requests.get()); assertEquals(0, connections.get())
        controller.close()
        // Real persisted pre-Runner crash boundary: a durable pair without a runtime checkpoint.
        val conversation = fixture.database.create("default")
        val run = RunId(UUID.randomUUID().toString())
        val user = Message(MessageId("mcp-crash-user"), conversation.id, run, "user", listOf(MessageBlock.Text("user", text)), RunStatus.COMPLETED)
        val assistant = Message(MessageId("mcp-crash-assistant"), conversation.id, run, "assistant", emptyList(), RunStatus.RUNNING)
        fixture.database.send(user, assistant, false, "default")
        val reopenedConnections = AtomicInteger(); val reopenedModels = AtomicInteger()
        val reopened = ChatController(fixture.application, fixture.database, fixture.settings, fixture.application.getSharedPreferences("conversation-ui", 0),
            model = { reopenedModels.incrementAndGet(); error("recovery must not reopen a model") },
            mcpTransport = { reopenedConnections.incrementAndGet(); error("recovery must not reconnect MCP") })
        try {
            awaitControl("reopened recovery ready") { reopened.ready }
            assertEquals(RunStatus.INTERRUPTED, fixture.database.messages(conversation.id).single { it.second.id == assistant.id }.second.status)
            assertNull(reopened.active); assertEquals(0, reopenedModels.get()); assertEquals(0, reopenedConnections.get())
        } finally { reopened.close() }
    }
}
