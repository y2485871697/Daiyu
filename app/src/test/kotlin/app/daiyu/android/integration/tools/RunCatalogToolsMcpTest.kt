package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real catalog, original dispatcher/ledger, Runner-issued opaque owner, real okhttp Call; only wire is fake. */
class RunCatalogToolsMcpTest {
    private val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private class Wire : Interceptor {
        val methods = mutableListOf<String>()
        val calls = AtomicInteger()
        var callBody: ((Interceptor.Chain) -> String)? = null
        var listing = """{"tools":[{"name":"erase","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}]}"""
        override fun intercept(chain: Interceptor.Chain): Response {
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            val input = McpJson.objectValue(buffer.readUtf8()); val method = input.getString("method")
            synchronized(methods) { methods.add(method) }
            val text = when (method) {
                "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                "notifications/initialized" -> null
                "tools/list" -> listing
                "tools/call" -> { calls.incrementAndGet(); callBody?.invoke(chain) ?: """{"content":[{"type":"text","text":"done"}]}""" }
                else -> error("unexpected method")
            }
            val response = if (text == null) "" else JSONObject().put("jsonrpc", "2.0").put("id", input.getLong("id"))
                .put("result", McpJson.objectValue(text)).toString()
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (text == null) 202 else 200).message("fixture")
                .header("Mcp-Session-Id", "fixed-session").header("Content-Type", "application/json")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }
    }
    private inner class Fixture(val wire: Wire = Wire(), factory: ((McpServer) -> McpHttpSession)? = null) {
        val current = AtomicBoolean(true)
        val server = McpServer("server", "Fixture", "https://mcp.invalid/rpc", "secret-token", enabled = true, catalog = listOf(McpRemoteTool("erase", "erase", readOnlyHint = true)), allowedTools = setOf("erase"))
        val scope = McpRunScope(McpSettings("revision", listOf(server)), listOf(McpSelection("server", "erase", "{\"target\":\"one\"}")),
            message.conversationId, message.runId, current::get, factory ?: { McpHttpSession(it, OkHttpClient.Builder().addInterceptor(wire).build()) })
        val capabilities = SessionCapabilities("run", setOf(McpRunScope.CAPABILITY, "audit"))
        val ledger = InMemoryToolLedgerStore()
        val contexts = mutableListOf<ToolExecutionContext>()
        val registry = ToolRegistry().register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition("audit", 1, "audit", "audit", ToolEffect.READ_ONLY, ToolSchema())
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual { contexts.add(it); ExecutionReceipt.Confirmed("audit") }
        })
        val owner = runnerOwner(message)
        val catalog = RunCatalogTools(registry, capabilities, ToolLedger(ledger), message.conversationId, message.runId,
            authorization = scope.authorization, mcp = scope) { grants, task ->
                assertSame(capabilities, grants); assertSame(scope.authorization, task)
                SkillRunSession.create(task, emptySet(), emptyList())
            }
        init { catalog.bindOwner(owner) }
        fun call(id: String = "call", json: String = "{\"target\":\"one\"}") = ToolInvocation(ToolCallId(id), "mcp_0_call",
            JSONObject().put("arguments_json", json).toString())
        fun dispatch(vararg calls: ToolInvocation) = catalog.execute(message, calls.toList()) { false }
        fun close() {
            val done = CountDownLatch(1)
            catalog.closeWithResult { assertEquals(RunCatalogCloseResult.Drained, it); done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
    }
    @Test fun metadataDriftRejectsPreviouslyAllowedNameBeforeCallAndDrains() {
        for (listing in listOf(
            """{"tools":[{"name":"erase","description":"changed","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}]}""",
            """{"tools":[{"name":"erase","inputSchema":{"type":"object","required":["target"]},"annotations":{"readOnlyHint":true}}]}""",
            """{"tools":[{"name":"erase","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":false}}]}""")) {
            val wire = Wire().apply { this.listing = listing }
            val f = Fixture(wire)
            try {
                assertThrows(IllegalStateException::class.java) { f.catalog.definitions }
                assertEquals(0, wire.calls.get())
                assertFalse(f.scope.isCurrent())
            } finally { f.close() }
        }
    }
    @Test fun bothPersistentPolicyGatesRejectBeforeTransportAndInvalidationClosesScope() {
        val tool = McpRemoteTool("erase", "erase", readOnlyHint = true)
        for (server in listOf(
            McpServer("server", "fixture", "https://mcp.invalid/rpc", ""),
            McpServer("server", "fixture", "https://mcp.invalid/rpc", "", catalog = listOf(tool), allowedTools = setOf("erase")),
            McpServer("server", "fixture", "https://mcp.invalid/rpc", "", enabled = true, catalog = listOf(tool)))) {
            assertThrows(IllegalArgumentException::class.java) {
                McpRunScope(McpSettings("policy", listOf(server)), listOf(McpSelection("server", "erase", "{}")),
                    message.conversationId, message.runId, { true }, { error("disabled transport") })
            }
        }
        var invalidate: (() -> Unit)? = null
        var unsubscribed = 0
        val server = McpServer("server", "fixture", "https://mcp.invalid/rpc", "", true, listOf(tool), setOf("erase"))
        val scope = McpRunScope(McpSettings("policy", listOf(server)), listOf(McpSelection("server", "erase", "{}")),
            message.conversationId, message.runId, { true }, { error("preview must not connect") },
            observeConfiguration = { invalidate = it; { unsubscribed++ } })
        assertTrue(scope.isCurrent()); checkNotNull(invalidate).invoke()
        assertFalse(scope.isCurrent()); assertEquals(1, unsubscribed)
        var drained = false; scope.observeDrained { drained = true }; assertTrue(drained)
        scope.close(); assertEquals(1, unsubscribed)
    }
    @Test fun dynamicToolsUseSameOpaqueOwnerUniqueAuthorizationAndOriginalLedger() {
        val f = Fixture()
        try {
            assertTrue(f.wire.methods.isEmpty())
            val definitions = f.catalog.definitions
            assertEquals(setOf("audit", "mcp_0_call"), definitions.map { it.name }.toSet())
            assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), f.wire.methods)
            // Even a readOnlyHint=true tool requires the exact host WRITE grant.
            assertEquals(1, f.scope.authorization.allowedWrites.size)
            val audit = ToolInvocation(ToolCallId("audit"), "audit", "{}")
            assertTrue(f.dispatch(f.call(), audit).all { it.outcome is DispatchOutcome.Confirmed })
            assertSame(f.owner, f.contexts.single().owner)
            assertSame(f.scope.authorization, f.contexts.single().authorization)
            assertEquals(1, f.wire.calls.get())
            assertTrue((f.dispatch(f.call()).single().outcome as DispatchOutcome.Confirmed).replayed)
            assertEquals(1, f.wire.calls.get())
            assertEquals(2, f.ledger.transaction { it.size })
            f.current.set(false)
            val denied = f.dispatch(f.call()).single().outcome
            assertEquals(DispatchOutcome.NotExecuted("mcp_owner_or_configuration_stale"), denied)
            assertFalse(denied.toString().contains("done"))
            assertThrows(IllegalStateException::class.java) { f.catalog.bindOwner(runnerOwner(message)) }
        } finally { f.close() }
    }
    @Test fun exactArgumentsAndMissingOrCopiedAuthorizationFailClosedBeforeReservation() {
        val f = Fixture()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                RunCatalogTools(ToolRegistry(), f.capabilities, ToolLedger(f.ledger), message.conversationId, message.runId,
                    authorization = TaskAuthorization("run", "run", f.scope.requiredWrites), mcp = f.scope) { _, _ -> error("capture") }
            }
            f.catalog.definitions
            val result = f.dispatch(f.call(json = "{\"target\":\"two\"}"), ToolInvocation(ToolCallId("audit"), "audit", "{}"))
            assertTrue(result.all { it.outcome == DispatchOutcome.NotExecuted("preparation_failed") })
            assertTrue(f.ledger.transaction { it.isEmpty() }); assertTrue(f.contexts.isEmpty()); assertEquals(0, f.wire.calls.get())
        } finally { f.close() }
    }
    @Test fun durableReplayRechecksContextAfterReservationAndDoesNotDeliverPrivilegedCachedPayload() {
        val current = AtomicBoolean(true)
        val base = InMemoryToolLedgerStore()
        var revokeAtReservation = false
        val store = object : ToolLedgerStore {
            override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T = base.transaction { entries ->
                val result = block(entries)
                if (revokeAtReservation) current.set(false)
                result
            }
        }
        val realOwner = runnerOwner(message)
        val registry = ToolRegistry().register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition("private_read", 1, "private", "private", ToolEffect.READ_ONLY, ToolSchema())
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual(admit = {
                if (current.get() && it.owner === realOwner) null else ExecutionReceipt.NotExecuted("owner_revoked")
            }, execute = { ExecutionReceipt.Confirmed("privileged cached result") })
        })
        val dispatcher = ToolDispatcher(registry.snapshot(), SessionCapabilities("run", setOf("private")), ToolLedger(store))
        val task = TaskAuthorization("run", "run")
        val call = listOf(ToolInvocation(ToolCallId("call"), "private_read", "{}"))
        assertTrue(dispatcher.dispatchOwned(task, call, realOwner).single().outcome is DispatchOutcome.Confirmed)
        revokeAtReservation = true
        assertEquals(DispatchOutcome.NotExecuted("owner_revoked"), dispatcher.dispatchOwned(task, call, realOwner).single().outcome)
    }
    @Test fun centralUnknownFencesScopeAndNeverRetriesOrReturnsLatePayload() {
        val wire = Wire().apply { callBody = { """{"content":[{"type":"image","data":"unsupported"}]}""" } }
        val f = Fixture(wire)
        try {
            f.catalog.definitions
            assertTrue(f.dispatch(f.call()).single().outcome is DispatchOutcome.Unknown)
            assertTrue(f.dispatch(f.call()).single().outcome is DispatchOutcome.NotExecuted)
            assertEquals(1, wire.calls.get()); assertEquals(1, f.ledger.transaction { it.size })
        } finally { f.close() }
    }
    @Test fun realCallCancellationRetainsCatalogUntilExecuteAndBodyHaveActuallyReturned() {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1); val release = CountDownLatch(1)
        val wire = Wire().apply { callBody = { chain ->
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            check(chain.call().isCanceled()); cancelled.countDown()
            check(release.await(5, TimeUnit.SECONDS)); throw java.io.IOException("cancelled fixture")
        } }
        val f = Fixture(wire); f.catalog.definitions
        val result = AtomicReference<List<ToolDispatchResult>>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread { try { result.set(f.dispatch(f.call())) } catch (t: Throwable) { failure.set(t) } }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val drained = CountDownLatch(1)
        f.catalog.closeWithResult { assertEquals(RunCatalogCloseResult.Drained, it); drained.countDown() }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertFalse(drained.await(100, TimeUnit.MILLISECONDS))
        release.countDown(); worker.join(5000)
        failure.get()?.let { throw it }
        assertFalse(worker.isAlive); assertTrue(drained.await(5, TimeUnit.SECONDS))
        assertTrue(result.get().single().outcome is DispatchOutcome.Unknown); assertEquals(1, wire.calls.get())
    }
    @Test fun throwingTransportFactoryWithoutAHandleRetainsCleanupUnknownAndCannotReopen() {
        val factories = AtomicInteger()
        val f = Fixture(factory = { factories.incrementAndGet(); error("synthetic partial construction") })
        assertThrows(IllegalStateException::class.java) { f.catalog.definitions }
        assertFalse(f.scope.isCurrent())
        val results = mutableListOf<RunCatalogCloseResult>()
        f.catalog.closeWithResult { results.add(it) }
        assertEquals(listOf(RunCatalogCloseResult.Unknown("catalog_close_unconfirmed")), results)
        f.scope.close()
        f.catalog.closeWithResult { results.add(it) }
        assertEquals(2, results.size); assertSame(results[0], results[1])
        val drained = AtomicBoolean(false)
        assertThrows(IllegalStateException::class.java) { f.scope.observeDrained { drained.set(true) } }
        assertFalse(drained.get())
        assertThrows(IllegalStateException::class.java) { f.scope.registeredTools() }
        assertThrows(IllegalStateException::class.java) { f.catalog.definitions }
        assertThrows(IllegalStateException::class.java) { f.catalog.bindOwner(runnerOwner(message)) }
        assertEquals(1, factories.get()); assertTrue(f.wire.methods.isEmpty())
    }
    @Test fun throwingLateTransportFactoryCannotTurnCloseIntoEmptyMapDrained() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val factories = AtomicInteger()
        val f = Fixture(factory = {
            factories.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            error("synthetic late construction failure")
        })
        val failed = AtomicBoolean(false)
        val setup = Thread { try { f.catalog.definitions } catch (_: IllegalStateException) { failed.set(true) } }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val done = CountDownLatch(1); val result = AtomicReference<RunCatalogCloseResult>()
            f.catalog.closeWithResult { result.set(it); done.countDown() }
            assertFalse(done.await(100, TimeUnit.MILLISECONDS))
            release.countDown(); setup.join(5000)
            assertFalse(setup.isAlive); assertTrue(failed.get()); assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(RunCatalogCloseResult.Unknown("catalog_close_unconfirmed"), result.get())
            f.catalog.closeWithResult { assertSame(result.get(), it) }
            assertThrows(IllegalStateException::class.java) { f.catalog.definitions }
            assertEquals(1, factories.get()); assertTrue(f.wire.methods.isEmpty())
        } finally { release.countDown(); setup.join(5000) }
    }
    @Test fun concurrentScopeDiscoveryCannotReplaceAnInFlightTransport() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val factories = AtomicInteger()
        val f = Fixture { server ->
            factories.incrementAndGet(); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            McpHttpSession(server, OkHttpClient.Builder().addInterceptor(Wire()).build())
        }
        val failure = AtomicReference<Throwable>()
        val setup = Thread { try { f.catalog.definitions } catch (error: Throwable) { failure.set(error) } }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { f.scope.registeredTools() }
            assertEquals(1, factories.get())
            release.countDown(); setup.join(5000)
            assertFalse(setup.isAlive); assertNull(failure.get())
        } finally { release.countDown(); setup.join(5000); f.close() }
    }
    @Test fun missingCentralFeedbackRevokesTheMcpScopeBeforeAnotherBatch() {
        val f = Fixture()
        try {
            assertThrows(IllegalStateException::class.java) { f.dispatch(f.call()) }
            assertFalse(f.scope.isCurrent())
            assertTrue(f.wire.methods.isEmpty())
            assertThrows(IllegalStateException::class.java) { f.catalog.definitions }
        } finally { f.close() }
    }
    @Test fun closeDuringLateTransportConstructionRetainsHandleAndNeverInitializesIt() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val wire = Wire()
        val f = Fixture(wire) { server ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            McpHttpSession(server, OkHttpClient.Builder().addInterceptor(wire).build())
        }
        val failed = AtomicBoolean(false)
        val setup = Thread { try { f.catalog.definitions } catch (_: IllegalStateException) { failed.set(true) } }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val drained = CountDownLatch(1)
        f.catalog.closeWithResult { assertEquals(RunCatalogCloseResult.Drained, it); drained.countDown() }
        assertFalse(drained.await(100, TimeUnit.MILLISECONDS))
        release.countDown(); setup.join(5000)
        assertFalse(setup.isAlive); assertTrue(failed.get()); assertTrue(drained.await(5, TimeUnit.SECONDS))
        assertTrue(wire.methods.isEmpty())
    }
}
