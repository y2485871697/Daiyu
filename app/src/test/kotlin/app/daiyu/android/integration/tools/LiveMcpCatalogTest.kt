package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.mcp.*
import app.daiyu.android.skills.SkillRunSession
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LiveMcpCatalogTest {
    private val message = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    private val remote = McpRemoteTool("read", "read", readOnlyHint = true)
    private fun settings(disabled: Boolean = false, absent: Boolean = false) = McpSettings(java.util.UUID.randomUUID().toString(),
        if (absent) emptyList() else listOf(McpServer("server", "fixture", "https://mcp.invalid/rpc", "", catalog = listOf(remote),
            disabledTools = if (disabled) setOf("read") else emptySet())))
    private class Wire : Interceptor {
        val calls = AtomicInteger()
        val active = AtomicReference<Call>()
        var block: (() -> Unit)? = null
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = McpJson.objectValue(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            val result = when (request.getString("method")) {
                "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                "notifications/initialized" -> null
                "tools/list" -> """{"tools":[{"name":"read","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}]}"""
                "tools/call" -> { calls.incrementAndGet(); active.set(chain.call()); block?.invoke(); """{"content":[{"type":"text","text":"private result"}]}""" }
                else -> error("unexpected wire method")
            }
            val text = result?.let { JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id"))
                .put("result", McpJson.objectValue(it)).toString() }.orEmpty()
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (result == null) 202 else 200)
                .message("fixture").header("Content-Type", "application/json")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }
    }
    private inner class Fixture(initial: McpSettings = settings(), factory: ((McpServer) -> McpHttpSession)? = null) {
        val wire = Wire()
        val current = AtomicReference<McpSettings?>(initial)
        var changed: (() -> Unit)? = null
        var audit: () -> Unit = {}
        var unsubscribed = 0
        val live = McpLiveScope(message.conversationId, message.runId, current::get,
            { callback -> changed = callback; { unsubscribed++ } },
            factory ?: { McpHttpSession(it, OkHttpClient.Builder().addInterceptor(wire).build()) })
        val ledger = InMemoryToolLedgerStore()
        val grants = SessionCapabilities("r", setOf(McpLiveScope.CAPABILITY, "audit"))
        val catalog = RunCatalogTools(ToolRegistry().register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition("audit", 1, "audit", "audit", ToolEffect.READ_ONLY, ToolSchema())
            override fun prepare(arguments: ToolArguments) = PreparedTool { audit(); ExecutionReceipt.Confirmed("audit") }
        }), grants, ToolLedger(ledger), message.conversationId, message.runId, liveMcp = live) { _, task ->
            SkillRunSession.create(task, emptySet(), emptyList())
        }
        init {
            var owner: RunExecutionOwner? = null
            val receiver = object : ReceiptBatchToolExecutor {
                override fun bindOwner(value: RunExecutionOwner) { owner = value }
                override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
            }
            ChatRunner(ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
                override fun cancel() {}
            } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { "unused" },
                RunnerOptions(receiptBatchExecutor = receiver)) {}.run(message, emptyList())
            catalog.bindOwner(checkNotNull(owner))
        }
        fun update(value: McpSettings?) { current.set(value); changed?.invoke() }
        fun name() = catalog.definitions.single { it.name.startsWith("mcp_live_") }.name
        fun call(name: String = name(), id: String = "call") = ToolInvocation(ToolCallId(id), name, """{"arguments_json":"{}"}""")
        fun execute(vararg calls: ToolInvocation) = catalog.execute(message, calls.toList()) { false }
        fun close() {
            val done = CountDownLatch(1)
            catalog.closeWithResult { assertEquals(RunCatalogCloseResult.Drained, it); done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
    }
    @Test fun initiallyEmptyRunSeesEnabledToolsWithoutRestartAndKeepsLedgerReplay() {
        val f = Fixture(settings(absent = true))
        try {
            assertEquals(listOf("audit"), f.catalog.definitions.map { it.name })
            f.update(settings())
            val call = f.call()
            assertEquals(0, f.wire.calls.get())
            assertTrue(f.execute(call).single().outcome is DispatchOutcome.Confirmed)
            assertTrue((f.execute(call).single().outcome as DispatchOutcome.Confirmed).replayed)
            assertEquals(1, f.wire.calls.get())
            assertEquals(1, f.ledger.transaction { it.size })
        } finally { f.close() }
    }
    @Test fun disableEnableAndDeleteRecreateNeverReuseOldAliasOrCachedResult() {
        val f = Fixture()
        try {
            val old = f.call()
            assertTrue(f.execute(old).single().outcome is DispatchOutcome.Confirmed)
            f.update(settings(disabled = true))
            assertEquals(listOf("audit"), f.catalog.definitions.map { it.name })
            assertTrue(f.execute(old).single().outcome is DispatchOutcome.NotExecuted)
            f.update(settings())
            val restored = f.call(id = "restored")
            assertNotEquals(old.name, restored.name)
            assertTrue(f.execute(restored).single().outcome is DispatchOutcome.Confirmed)
            f.update(settings(absent = true))
            f.update(settings())
            assertNotEquals(restored.name, f.name())
            assertTrue(f.execute(restored).single().outcome is DispatchOutcome.NotExecuted)
            assertEquals(2, f.wire.calls.get())
            assertTrue(f.execute(ToolInvocation(ToolCallId("audit"), "audit", "{}")).single().outcome is DispatchOutcome.Confirmed)
        } finally { f.close() }
    }
    @Test fun saveBetweenWholeBatchAdmissionAndMcpExecutionBlocksBeforeNetwork() {
        val f = Fixture()
        try {
            val call = f.call()
            f.audit = { f.update(settings(disabled = true)) }
            val result = f.execute(ToolInvocation(ToolCallId("audit"), "audit", "{}"), call)
            assertTrue(result[0].outcome is DispatchOutcome.Confirmed)
            assertTrue(result[1].outcome is DispatchOutcome.NotExecuted)
            assertEquals(0, f.wire.calls.get())
        } finally { f.close() }
    }
    @Test fun currentSnapshotFencesMissedNotificationBeforeLedgerReplay() {
        val f = Fixture()
        try {
            val call = f.call()
            assertTrue(f.execute(call).single().outcome is DispatchOutcome.Confirmed)
            f.current.set(null) // A failed/unconfirmed settings write has no authorized snapshot.
            assertTrue(f.execute(call).single().outcome is DispatchOutcome.NotExecuted)
            assertEquals(1, f.wire.calls.get())
        } finally { f.close() }
    }
    @Test fun inFlightDeleteCancelsRealCallRejectsLatePayloadAndWaitsForDrain() {
        val f = Fixture()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val outcome = AtomicReference<DispatchOutcome>()
        f.wire.block = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val call = f.call()
        val worker = Thread { outcome.set(f.execute(call).single().outcome) }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.update(settings(absent = true))
            assertTrue(f.wire.active.get().isCanceled())
            f.catalog.closeWithResult { assertEquals(RunCatalogCloseResult.Drained, it); done.countDown() }
            assertFalse(done.await(100, TimeUnit.MILLISECONDS))
            release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive)
            assertTrue(outcome.get() is DispatchOutcome.Unknown)
            assertFalse(outcome.get().toString().contains("private result"))
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, f.wire.calls.get()); assertEquals(1, f.unsubscribed)
        } finally { release.countDown(); worker.join(5000); f.close() }
    }
    @Test fun lateFactoryCannotInitializeAfterDeleteAndCloseRetainsItsHandle() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val wire = Wire()
        val f = Fixture(factory = { server ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            McpHttpSession(server, OkHttpClient.Builder().addInterceptor(wire).build())
        })
        val outcome = AtomicReference<DispatchOutcome>(); val done = CountDownLatch(1)
        val call = f.call()
        val worker = Thread { outcome.set(f.execute(call).single().outcome) }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); f.update(settings(absent = true))
            f.catalog.closeWithResult { assertEquals(RunCatalogCloseResult.Drained, it); done.countDown() }
            assertFalse(done.await(100, TimeUnit.MILLISECONDS))
            release.countDown(); worker.join(5000)
            assertTrue(outcome.get() is DispatchOutcome.NotExecuted)
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(0, wire.calls.get())
        } finally { release.countDown(); worker.join(5000); f.close() }
    }
    @Test fun schemaWordsInCredentialsDoNotBreakLiveCatalogConstructionOrUpdates() {
        val f = Fixture()
        try {
            val server = McpServer("server", "fixture", "https://mcp.invalid/rpc", "object", catalog = listOf(remote))
            f.update(McpSettings("replacement", listOf(server)))
            assertTrue(f.execute(f.call()).single().outcome is DispatchOutcome.Confirmed)
            assertEquals(1, f.wire.calls.get())
        } finally { f.close() }
    }
    @Test fun failedInitializationUnsubscribesAndFactoryErrorNeverClaimsPhysicalDrain() {
        var unsubscribed = 0
        assertThrows(IllegalStateException::class.java) {
            McpLiveScope(message.conversationId, message.runId, { error("failed snapshot") }, { { unsubscribed++ } })
        }
        assertEquals(1, unsubscribed)
        val f = Fixture(factory = { throw AssertionError("partial construction") })
        val call = f.call()
        assertThrows(AssertionError::class.java) { f.execute(call) }
        var result: RunCatalogCloseResult? = null
        f.catalog.closeWithResult { result = it }
        assertTrue(result is RunCatalogCloseResult.Unknown)
        assertTrue(f.live.registeredTools().isEmpty())
    }

}
