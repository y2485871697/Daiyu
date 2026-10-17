package app.daiyu.android.integration.tools

import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.*
import app.daiyu.android.browser.bridge.*
import app.daiyu.android.browser.download.*
import app.daiyu.android.browser.host.*
import app.daiyu.android.browser.network.*
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Production approval/token/catalog/ledger/fetch/coordinator; fake HTTP only, real private files. */
class BrowserChildProductionTest {
    private val newTab = """{"action":"new_tab"}"""
    private val fetch = """{"action":"fetch","tab_id":0,"url":"https://fixture.invalid/file"}"""
    private val settings = BrowserSettings(BrowserAccess.FULL)
    private val profile = AssistantProfile("profile", "Test", "")
    private val caps = setOf(RunBrowserTools.READ_CAPABILITY, RunBrowserTools.WRITE_CAPABILITY)
    private val childGrant = BrowserGrant(BrowserOwner("child", BrowserOwnerKind.CHILD), BrowserAccess.FULL)
    private val childOwner = ChildRunOwner("conversation", "parent", "group", 7, "child", "child-message")
    private fun effects(grant: BrowserGrant = childGrant) = setOf(newTab, fetch).map {
        RunBrowserTools.writeEffect(grant, it)
    }.toSet()
    private fun scope(writes: Set<WriteEffect> = effects(), capabilities: Set<String> = caps): ChildRunScope =
        ChildRunScope.capture(childOwner, SessionCapabilities("inherited-session", capabilities),
            TaskAuthorization("inherited-session", "parent", writes), caps, effects())
    private fun prepare(scope: ChildRunScope = scope()) = CatalogChildBrowserSession.prepare(scope, profile,
        settings, childGrant, setOf("new_tab", "list_tabs", "fetch"), listOf(newTab, fetch))

    private class Native(val grant: BrowserGrant, val registry: BrowserSessionRegistry,
                         val root: Path, val failAfterDownload: Boolean = false) : CatalogBrowserBinding.Host {
        val httpCalls = AtomicInteger()
        var creates = 0
        var closes = 0
        var closeRequests = 0
        var closeFailure = false
        var closeEntered: CountDownLatch? = null
        var returnClose: CountDownLatch? = null
        private var closed = false
        val body = "private-download-测试".toByteArray(Charsets.UTF_8)
        private val http = BrowserHttpDownloader(registry, BrowserDownloadCoordinator(registry, BrowserPathMapper(root)),
            connectionFactory = { uri ->
                httpCalls.incrementAndGet()
                object : HttpURLConnection(uri.toURL()) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun getHeaderField(name: String): String? =
                        if (name.equals("Content-Length", true)) body.size.toString() else null
                    override fun getInputStream() = ByteArrayInputStream(body)
                }
            })
        private val backend = BrowserFetchBackend(registry, http)
        override val session = BrowserToolSession(grant, registry, object : BrowserTabLifecycle {
            override fun create(lease: BrowserTabLease) { creates++ }
            override fun close(lease: BrowserTabLease) { closes++ }
        }, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                val result = backend.execute(command, lease).get(5, TimeUnit.SECONDS)
                if (failAfterDownload) error("lost native reply")
                return result
            }
        })
        override fun closeWithResult(): BrowserCloseResult {
            closeEntered?.countDown()
            returnClose?.let { check(it.await(5, TimeUnit.SECONDS)) }
            closeRequests++
            closed = true
            session.close()
            backend.close()
            return closeStatus()
        }
        override fun closeStatus() = BrowserCloseResult(closed,
            failures = if (closeFailure) setOf("FIXTURE_DESTROY_FAILED") else emptySet()).merge(backend.closeStatus())
    }

    private fun dispatcher(bound: RunBrowserTools.ConfirmedCatalog, scope: ChildRunScope,
                           store: ToolLedgerStore = InMemoryToolLedgerStore()) = ToolDispatcher(
        ToolRegistry().apply { bound.tools.forEach { register(it) } }.snapshot(), scope.session, ToolLedger(store))
    private fun invoke(dispatcher: ToolDispatcher, scope: ChildRunScope, owner: RunExecutionOwner,
                       id: String, raw: String, task: TaskAuthorization = scope.authorization): DispatchOutcome =
        dispatcher.dispatchOwned(task, listOf(ToolInvocation(ToolCallId(id), RunBrowserTools.WRITE_TOOL_NAME, raw)), owner) { false }
            .single().outcome
    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) {}
    }
    private fun delete(root: Path) = Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }; Unit
    }
    private fun awaitClose(run: CatalogChildBrowserSession) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (run.closeResult() == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertSame(RunCatalogCloseResult.Drained, run.closeResult())
    }

    @Test fun independentChildRequiresInheritedEffectsCapabilitiesAndItsExactScopeTask() {
        rejected { prepare(scope(emptySet())) }
        rejected { prepare(scope(capabilities = setOf(RunBrowserTools.READ_CAPABILITY))) }
        rejected { BrowserRunPreparation.prepare(BrowserRunIdentity(ConversationId("conversation"), RunId("child"),
            profile.id, MessageId("child-message")), settings, childGrant, setOf("list_tabs")) }
        val scope = scope()
        val run = prepare(scope)
        assertEquals(childOwner, run.preview.childOwner)
        assertFalse(run.confirm(prepare(scope).preview))
        assertTrue(run.confirm(run.preview))
        assertFalse(run.confirm(run.preview))
        val root = Files.createTempDirectory("child-exact-")
        val native = Native(childGrant, BrowserSessionRegistry(), root)
        try {
            assertTrue(run.open { token -> CatalogBrowserBinding.create(profile, token) { native } })
            val original = owner("child", "child-message")
            val bound = run.bindOwner(original)
            assertSame(scope.authorization, bound.taskAuthorization)
            assertEquals("inherited-session", bound.taskAuthorization.sessionId)
            assertEquals(0, native.creates)
            val dispatch = dispatcher(bound, scope)
            val replacement = TaskAuthorization(scope.authorization.sessionId, "child", scope.authorization.allowedWrites)
            assertTrue(invoke(dispatch, scope, original, "forged-task", newTab, replacement) is DispatchOutcome.NotExecuted)
            assertTrue(invoke(dispatch, scope, owner("child", "child-message"), "forged-owner", newTab) is DispatchOutcome.NotExecuted)
            assertEquals(0, native.creates)
            assertTrue(invoke(dispatch, scope, original, "approved", newTab) is DispatchOutcome.Confirmed)
            assertEquals(1, native.creates)
            // An inherited broad task still cannot change even one confirmed fetch URL parameter.
            val tool = bound.tools.single { it.definition.name == RunBrowserTools.WRITE_TOOL_NAME }
            rejected { tool.prepare(tool.definition.schema.parse(fetch.replace("/file", "/other"))) }
            run.close()
            assertTrue(invoke(dispatch, scope, original, "after-stop", newTab) is DispatchOutcome.NotExecuted)
            assertEquals(1, native.creates)
            assertFalse(run.open { error("must not reopen") })
            awaitClose(run)
        } finally { run.close(); delete(root) }
    }

    @Test fun mainAndChildTabZeroAreIsolatedAndChildCloseCannotCloseMain() {
        val registry = BrowserSessionRegistry()
        val root = Files.createTempDirectory("child-isolation-")
        val main = Native(childGrant.copy(owner = BrowserOwner("parent", BrowserOwnerKind.MAIN)), registry, root)
        val child = Native(childGrant, registry, root)
        try {
            assertTrue(main.session.execute(mapOf("action" to "new_tab")) is BrowserOutcome.Confirmed)
            assertTrue(child.session.execute(mapOf("action" to "new_tab")) is BrowserOutcome.Confirmed)
            val mainLease = checkNotNull(main.session.currentTab)
            val childLease = checkNotNull(child.session.currentTab)
            assertEquals(0, mainLease.tabId); assertEquals(0, childLease.tabId)
            try { child.session.closeTab(mainLease); fail("cross-owner close") } catch (_: IllegalStateException) {}
            try { main.session.closeTab(childLease); fail("cross-owner close") } catch (_: IllegalStateException) {}
            child.closeWithResult()
            assertEquals(1, child.closes)
            assertEquals(0, main.closes)
            registry.assertCurrent(mainLease)
            assertEquals(mainLease, main.session.currentTab)
            assertTrue(main.session.execute(mapOf("action" to "list_tabs")) is BrowserOutcome.Confirmed)
            assertTrue(child.session.execute(mapOf("action" to "list_tabs")) is BrowserOutcome.NotExecuted)
        } finally { child.closeWithResult(); main.closeWithResult(); delete(root) }
    }

    @Test fun approvedFetchReturnsRealPrivateFileAndDigestAndConfirmedLedgerReplayDoesNotGetAgain() {
        downloadCase(unknown = false)
    }
    @Test fun unknownFetchAfterActualPublicationNeverReplaysUnderNewCallId() { downloadCase(unknown = true) }

    private fun downloadCase(unknown: Boolean) {
        val scope = scope()
        val run = prepare(scope)
        assertTrue(run.confirm(run.preview))
        val root = Files.createTempDirectory("child-real-download-")
        val native = Native(childGrant, BrowserSessionRegistry(), root, unknown)
        try {
            assertTrue(run.open { token -> CatalogBrowserBinding.create(profile, token) { native } })
            val owner = owner("child", "child-message")
            val bound = run.bindOwner(owner)
            val store = InMemoryToolLedgerStore()
            val dispatch = dispatcher(bound, scope, store)
            assertTrue(invoke(dispatch, scope, owner, "tab", newTab) is DispatchOutcome.Confirmed)
            val first = invoke(dispatch, scope, owner, "download", fetch)
            assertEquals(1, native.httpCalls.get())
            val files = Files.list(root).use { it.toArray().map { p -> p as Path } }
            assertEquals(1, files.size)
            val path = files.single()
            assertTrue(path.toRealPath().startsWith(root.toRealPath()))
            assertArrayEquals(native.body, Files.readAllBytes(path))
            if (unknown) assertTrue(first is DispatchOutcome.Unknown)
            else {
                val receipt = JSONObject((first as DispatchOutcome.Confirmed).payload)
                assertEquals(path.toRealPath().toString(), receipt.getString("filePath"))
                val summary = JSONObject(receipt.getString("text"))
                assertEquals(path.toRealPath().toString(), summary.getString("path"))
                assertEquals(native.body.size.toLong(), summary.getLong("size"))
                assertEquals(MessageDigest.getInstance("SHA-256").digest(native.body).joinToString("") { "%02x".format(it) },
                    summary.getString("sha256"))
            }
            // Recreate the dispatcher/ledger view, retaining original auth + token + owner.
            val again = invoke(dispatcher(bound, scope, store), scope, owner, "new-provider-call", fetch)
            if (unknown) assertTrue(again is DispatchOutcome.Unknown)
            else assertTrue((again as DispatchOutcome.Confirmed).replayed)
            assertEquals(1, native.httpCalls.get())
            run.close(); awaitClose(run)
        } finally { run.close(); delete(root) }
    }

    @Test fun stopDuringOpenRetainsLateNativeHandleUntilItsActualClose() {
        val run = prepare()
        assertTrue(run.confirm(run.preview))
        val root = Files.createTempDirectory("child-late-open-")
        val native = Native(childGrant, BrowserSessionRegistry(), root)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = java.util.concurrent.FutureTask {
            run.open { token ->
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                CatalogBrowserBinding.create(profile, token) { native }
            }
        }
        val worker = Thread(result)
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            run.close()
            assertNull(run.closeResult())
        } finally { release.countDown(); worker.join(5000) }
        try {
            assertFalse(worker.isAlive)
            assertFalse(result.get(1, TimeUnit.SECONDS))
            awaitClose(run)
            assertEquals(0, native.creates)
        }
        finally { run.close(); delete(root) }
    }

    @Test fun failedChildCloseKeepsUnknownAndPollingNeverRequestsCleanupOrReopens() {
        val run = prepare()
        assertTrue(run.confirm(run.preview))
        val root = Files.createTempDirectory("child-close-unknown-")
        val native = Native(childGrant, BrowserSessionRegistry(), root)
        try {
            assertTrue(run.open { token -> CatalogBrowserBinding.create(profile, token) { native } })
            native.closeFailure = true
            run.close()
            assertTrue(run.closeResult() is RunCatalogCloseResult.Unknown)
            val requests = native.closeRequests
            repeat(3) { assertTrue(run.closeResult() is RunCatalogCloseResult.Unknown) }
            assertEquals(requests, native.closeRequests)
            assertFalse(run.open { error("UNKNOWN is not a new construction grant") })
            native.closeFailure = false
            run.retryCleanup()
            // Physical cleanup may complete later, but unknown is not silently turned into release.
            assertTrue(run.closeResult() is RunCatalogCloseResult.Unknown)
        } finally { run.close(); delete(root) }
    }

    @Test fun originalPartialConstructionCleanupRemainsOwnedAfterFailureAndCannotReopen() {
        val run = prepare()
        assertTrue(run.confirm(run.preview))
        var cleanupCalls = 0
        val failure = app.daiyu.android.browser.openminis.BrowserRunConstructionException(
            IllegalStateException("fixture construction")) {
            cleanupCalls++
            BrowserCloseResult(true, pending = setOf("PARTIAL_NATIVE"))
        }
        assertFalse(run.open { throw failure })
        assertEquals(1, cleanupCalls)
        assertNull(run.closeResult())
        repeat(3) { assertNull(run.closeResult()) }
        assertEquals(1, cleanupCalls)
        assertFalse(run.open { error("failed construction cannot be replayed") })
        run.retryCleanup()
        assertEquals(2, cleanupCalls)
        assertNull(run.closeResult())
    }

    @Test fun actualToolEnabledChildRunnerBindsBeforeSchemaAndDispatchesToolReadyThroughOriginalLedger() {
        val scope = scope()
        val session = prepare(scope)
        assertTrue(session.confirm(session.preview))
        val root = Files.createTempDirectory("actual-child-runner-")
        val native = Native(childGrant, BrowserSessionRegistry(), root)
        val ledgerStore = InMemoryToolLedgerStore()
        val assembly = CatalogChildBrowserAssembly(session, ToolLedger(ledgerStore)) {
            session.open { token -> CatalogBrowserBinding.create(profile, token) { native } }
        }
        val captured = java.util.concurrent.atomic.AtomicReference<RunExecutionOwner>()
        val capturingReceipts = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) {
                assertEquals("child", owner.runId.value)
                captured.set(owner)
                assembly.receipts.bindOwner(owner)
            }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) =
                assembly.receipts.execute(message, invocations, cancelled)
        }
        val retained = object : ChildToolAssembly by assembly {
            override val receipts: ReceiptBatchToolExecutor get() = capturingReceipts
        }
        val finished = CountDownLatch(1)
        val status = java.util.concurrent.atomic.AtomicReference<ChildTaskStatus>()
        val journal = object : ChildTaskStore {
            override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {
                assertEquals(0, native.creates)
            }
            override fun started(task: ChildTaskRecord) { assertNotNull(captured.get()) }
            override fun finished(task: ChildTaskRecord, result: ChildTaskStatus, text: String, detail: String?) {
                status.set(result); finished.countDown()
            }
            override fun list(conversationId: String, limit: Int) = emptyList<ChildTaskRecord>()
        }
        val parent = owner("parent", "parent-message")
        val record = ChildTaskRecord(scope.owner, parent.assistantMessageId.value, parent.ownerId, 0, profile.id, "download once")
        val capacity = java.util.concurrent.Semaphore(1)
        val opens = AtomicInteger()
        val errors = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val model = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = error("schema required")
            override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
                opens.incrementAndGet()
                assertNotNull(captured.get())
                assertTrue(definitions.any { it.name == RunBrowserTools.WRITE_TOOL_NAME })
                assertEquals(scope.owner.childRunId, history.last().runId.value)
                return object : ModelSession {
                    var round = 0
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        try {
                            when (round++) {
                                0 -> sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("runner-tab"), RunBrowserTools.WRITE_TOOL_NAME, newTab)))
                                1 -> {
                                    assertEquals(1, native.creates)
                                    // Negative probes use the ACTUAL child Runner owner and original leaf.
                                    val bound = session.bindOwner(captured.get())
                                    assertSame(scope.authorization, bound.taskAuthorization)
                                    val dispatch = dispatcher(bound, scope, ledgerStore)
                                    listOf(owner("parent", "parent-message"), owner("sibling", "sibling-message"),
                                        owner("old-generation-child", "old-generation-message"), owner("child", "child-message")).forEach { foreign ->
                                        assertTrue(invoke(dispatch, scope, foreign, "foreign-${foreign.ownerId}", fetch) is DispatchOutcome.NotExecuted)
                                    }
                                    assertEquals(0, native.httpCalls.get())
                                    val replaced = TaskAuthorization(scope.authorization.sessionId, "child", scope.authorization.allowedWrites)
                                    assertTrue(invoke(dispatch, scope, captured.get(), "replaced-task", fetch, replaced) is DispatchOutcome.NotExecuted)
                                    sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("runner-fetch"), RunBrowserTools.WRITE_TOOL_NAME, fetch)))
                                }
                                2 -> {
                                    assertEquals(1, native.httpCalls.get())
                                    sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("runner-replay"), RunBrowserTools.WRITE_TOOL_NAME, fetch)))
                                }
                                else -> {
                                    assertEquals(1, native.httpCalls.get())
                                    assertTrue(outputs.single().output.contains("sha256"))
                                    sink(ModelEvent.TextFinal("done", "child finished"))
                                }
                            }
                        } catch (failure: Throwable) { errors.set(failure); throw failure }
                    }
                    override fun cancel() {}
                }
            }
        }
        val run = ToolEnabledChildRun(scope, record, parent, retained, { model }, journal, capacity)
        try {
            assertTrue(run.start())
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            errors.get()?.let { throw it }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!run.drained && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(ChildTaskStatus.COMPLETED, status.get())
            assertTrue(run.drained)
            assertEquals(1, opens.get())
            assertEquals(1, native.creates)
            assertEquals(1, native.httpCalls.get())
            assertEquals(1, capacity.availablePermits())
            assertFalse(run.start())
        } finally { run.close(); delete(root) }
    }

    @Test fun actualChildResourceOwnerPinsCapacityThroughBlockedOrFailedNativeCleanup() {
        for (failure in listOf(false, true)) {
            val scope = scope()
            val session = prepare(scope)
            assertTrue(session.confirm(session.preview))
            val root = Files.createTempDirectory("child-native-close-")
            val closeEntered = CountDownLatch(1)
            val releaseClose = CountDownLatch(1)
            val native = Native(childGrant, BrowserSessionRegistry(), root).apply {
                this.closeEntered = closeEntered
                returnClose = releaseClose
                closeFailure = failure
            }
            val assembly = CatalogChildBrowserAssembly(session, ToolLedger(InMemoryToolLedgerStore())) {
                session.open { token -> CatalogBrowserBinding.create(profile, token) { native } }
            }
            val journal = object : ChildTaskStore {
                override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {}
                override fun started(task: ChildTaskRecord) {}
                override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {}
                override fun list(conversationId: String, limit: Int) = emptyList<ChildTaskRecord>()
            }
            val parent = owner("parent", "parent-message")
            val capacity = java.util.concurrent.Semaphore(1)
            val model = ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { sink(ModelEvent.TextFinal("answer", "done")) }
                override fun cancel() {}
            } }
            val run = ToolEnabledChildRun(scope, ChildTaskRecord(scope.owner, parent.assistantMessageId.value,
                parent.ownerId, 0, profile.id, "approved child"), parent, assembly, { model }, journal, capacity)
            try {
                assertTrue(run.start())
                assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
                assertFalse(run.drained)
                assertEquals(0, capacity.availablePermits())
                releaseClose.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!run.drained && !run.cleanupUnknown && System.nanoTime() < deadline) Thread.sleep(5)
                if (failure) {
                    assertTrue(run.cleanupUnknown)
                    assertFalse(run.drained)
                    assertEquals(0, capacity.availablePermits())
                    native.closeFailure = false
                    session.retryCleanup()
                    assertTrue(session.closeResult() is RunCatalogCloseResult.Unknown)
                    assertEquals(0, capacity.availablePermits())
                } else {
                    assertTrue(run.drained)
                    assertEquals(1, capacity.availablePermits())
                }
                assertFalse(run.start())
            } finally { releaseClose.countDown(); run.close(); delete(root) }
        }
    }

    private fun owner(run: String, message: String): RunExecutionOwner {
        var captured: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { captured = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}
        runner.run(Message(MessageId(message), ConversationId("conversation"), RunId(run),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(captured)
    }
}
