package app.daiyu.android.gui

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.*
import app.daiyu.android.skills.SkillRunSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual catalog/binding/central ledger/GUI session and main-loop release paths. No system
 * accessibility service, external app, network, screenshot or action is enabled by these tests. */
@Suppress("DEPRECATION")
class GuiCatalogProductionTest : InstrumentationTestCase() {
    private val message = Message(MessageId("gui-assistant"), ConversationId("gui-conversation"),
        RunId("gui-run"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { value.set(block()) } catch (t: Throwable) { error.set(t) } }
        error.get()?.let { throw it }
        return value.get()
    }
    private fun owner(): RunExecutionOwner {
        var result: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { result = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) =
                error("no tools expected")
        }
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}.run(message, emptyList())
        return checkNotNull(result)
    }
    private fun session(): GuiRunSession = main {
        // No real screen grant. Freeze the empty snapshot through the production UI-only method.
        GuiRunSession(GuiLeaseGate.Lease(GuiOwner("gui-run", "gui-run"))).also { it.freezeAuthorizationFromUser() }
    }
    private fun tools(session: GuiRunSession?, token: RunExecutionOwner, task: TaskAuthorization,
        store: ToolLedgerStore = InMemoryToolLedgerStore(), registry: ToolRegistry = ToolRegistry(),
        observe: ((GuiRunSession, (RunCatalogCloseResult) -> Unit) -> Unit)? = null): RunCatalogTools {
        val caps = SessionCapabilities("gui-run", setOf(GuiScreenToolFactory.CAPABILITY, "gui.audit"))
        val captured: (SessionCapabilities, TaskAuthorization) -> SkillRunSession = { actual, auth ->
            assertSame(caps, actual); assertSame(task, auth)
            SkillRunSession.create(auth, emptySet(), emptyList())
        }
        return (if (observe == null) RunCatalogTools(registry, caps, ToolLedger(store), message.conversationId,
            message.runId, authorization = task, gui = session, capture = captured)
        else RunCatalogTools(registry, caps, ToolLedger(store), message.conversationId, message.runId,
            authorization = task, gui = session, observeGuiClose = observe, capture = captured)).also { it.bindOwner(token) }
    }

    fun testSameOwnerAndUnionAuthorizationBindAndOnlyExplicitSessionAdvertisesGui() {
        val token = owner()
        val task = TaskAuthorization("gui-run", "gui-run", setOf(WriteEffect("other", "write", "target", "exact")))
        val gui = session()
        val catalog = tools(gui, token, task)
        try {
            assertNull(gui.admission(token, task))
            assertEquals("GUI_RUN_OWNER_MISMATCH", gui.admission(token,
                TaskAuthorization(task.sessionId, task.taskId, task.allowedWrites))?.code)
            assertEquals(setOf("screen", "screen_action"), catalog.definitions.map { it.name }.toSet())
            catalog.bindOwner(token) // idempotent, same objects
        } finally { catalog.close() }
        val ordinary = tools(null, token, task)
        try { assertTrue(ordinary.definitions.none { it.name == "screen" || it.name == "screen_action" }) }
        finally { ordinary.close() }
    }

    fun testEveryFinalCentralUnknownFencesGuiIncludingUnrelatedToolPersistenceFailure() {
        for (start in listOf(false, true)) {
            val gui = session()
            val token = owner()
            val task = TaskAuthorization("gui-run", "gui-run")
            val memory = InMemoryToolLedgerStore()
            val transactions = AtomicInteger()
            val store = object : ToolLedgerStore {
                override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                    if (transactions.incrementAndGet() == 2) error("receipt write failed")
                    return memory.transaction(block)
                }
            }
            val registry = ToolRegistry().register(object : RegisteredTool {
                override val definition = app.daiyu.android.domain.tools.ToolDefinition("audit", 1,
                    "unrelated central receipt", "gui.audit", ToolEffect.READ_ONLY, ToolSchema(emptyList()))
                override fun prepare(arguments: ToolArguments) = PreparedTool.contextual {
                    assertSame(token, it.owner); assertSame(task, it.authorization)
                    ExecutionReceipt.Confirmed("adapter succeeded")
                }
            })
            val catalog = tools(gui, token, task, store, registry)
            try {
                catalog.definitions
                val calls = listOf(ToolInvocation(ToolCallId("original"), "audit", "{}"))
                val receipts = if (start) (catalog.start(message, calls) { false } as ReceiptBatchStart.Completed).receipts
                    else catalog.execute(message, calls) { false }
                assertEquals(DispatchOutcome.Unknown("receipt_persistence_failed"), receipts.single().outcome)
                assertEquals("GUI_PREVIOUS_OPERATION_UNCONFIRMED", gui.admission(token, task)?.code)
                assertEquals("GUI_PREVIOUS_OPERATION_UNCONFIRMED", (gui.observe { false } as GuiResult.Rejected).code)
                assertEquals("GUI_PREVIOUS_OPERATION_UNCONFIRMED", (gui.screenshot("approved") { false } as GuiResult.Rejected).code)
                assertEquals("GUI_PREVIOUS_OPERATION_UNCONFIRMED", (gui.action(GuiAction("approved", GuiOperation.HOME)) { false } as GuiResult.Rejected).code)
            } finally { catalog.close() }
        }
    }

    fun testGuiScopeRejectsSuspendingBarrierBeforeAnyLedgerOrModelWork() {
        val gui = session()
        val registry = ToolRegistry().register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition("ask_user", 1,
                "barrier", "gui.audit", ToolEffect.USER_BARRIER, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = PreparedTool.userBarrier()
        })
        val catalog = tools(gui, owner(), TaskAuthorization("gui-run", "gui-run"), registry = registry)
        try { catalog.definitions; fail("GUI scope must not suspend beyond its outcome observer") }
        catch (expected: IllegalArgumentException) { assertEquals("gui_user_barrier_not_supported", expected.message) }
        finally { catalog.close() }
        assertFalse(gui.preparationAvailable())
    }

    fun testWorkerCloseSealsAdmissionBeforeMainReleasesLease() {
        val gui = main { GuiRunSession(GuiLeaseGate.Lease(GuiOwner("worker-close", "worker-close"))) }
        try {
            main {
                assertTrue(gui.preparationAvailable())
                val returned = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                Thread {
                    try { gui.close() } catch (t: Throwable) { failure.set(t) }
                    finally { returned.countDown() }
                }.start()
                assertTrue(returned.await(5, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
                assertFalse(gui.preparationAvailable())
                assertTrue(gui.lease.valid)
                assertFalse(gui.releaseConfirmed)
            }
            main {
                assertFalse(gui.lease.valid)
                assertTrue(gui.releaseConfirmed)
            }
        } finally { main { gui.close() } }
    }

    fun testMainCloseStaysSynchronousInsideImmediateCoroutine() {
        main {
            runBlocking(Dispatchers.Main.immediate) {
                val gui = GuiRunSession(GuiLeaseGate.Lease(GuiOwner("main-close", "main-close")))
                try {
                    assertTrue(gui.preparationAvailable())
                    gui.close()
                    assertFalse(gui.preparationAvailable())
                    assertFalse(gui.lease.valid)
                    assertTrue(gui.releaseConfirmed)
                } finally { gui.close() }
            }
        }
    }

    fun testCatalogCloseWaitsForActualMainReleaseAndDoesNotDropGuiObservation() {
        val gui = session()
        val token = owner()
        val observed = AtomicReference<((RunCatalogCloseResult) -> Unit)?>()
        val catalog = tools(gui, token, TaskAuthorization("gui-run", "gui-run"), observe = { actual, done ->
            assertSame(gui, actual); observed.set(done)
        })
        val result = AtomicReference<RunCatalogCloseResult?>()
        catalog.closeWithResult { result.set(it) }
        assertNotNull(observed.get())
        assertNull(result.get()) // seven other resources being absent must not hide GUI
        main { assertTrue(gui.releaseConfirmed) }
        observed.get()!!.invoke(RunCatalogCloseResult.Drained)
        assertEquals(RunCatalogCloseResult.Drained, result.get())
    }

    fun testProductionCatalogObserverReportsDrainedOnlyAfterLateCaptureReleasesRealSession() {
        val gui = session()
        val request = main {
            val capturesField = GuiRuntime::class.java.getDeclaredField("captures").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val captures = capturesField.get(GuiRuntime) as MutableMap<GuiLeaseGate.Lease, Int>
            val finished = GuiRuntime::class.java.getDeclaredMethod("captureFinished", GuiLeaseGate.Lease::class.java)
                .apply { isAccessible = true }
            captures[gui.lease] = 1
            GuiCaptureRequest<String> { finished.invoke(GuiRuntime, gui.lease) }.also {
                it.submit { throw IllegalStateException("accepted before throw") }
            }
        }
        val catalog = tools(gui, owner(), TaskAuthorization("gui-run", "gui-run"))
        val result = AtomicReference<RunCatalogCloseResult?>()
        val done = java.util.concurrent.CountDownLatch(1)
        try {
            catalog.closeWithResult { result.set(it); done.countDown() }
            main { assertFalse(gui.releaseConfirmed); assertNull(result.get()) }
            main { request.resourcesDisposed() }
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(gui.releaseConfirmed)
            assertEquals(RunCatalogCloseResult.Drained, result.get())
        } finally { main { request.resourcesDisposed() }; catalog.close() }
    }

    fun testRealRuntimeClosePinsCaptureAfterSubmissionThrowUntilLateCallbackDisposed() {
        val released = AtomicInteger()
        main {
            val lease = GuiLeaseGate.Lease(GuiOwner("late-capture", "late-run"))
            val capturesField = GuiRuntime::class.java.getDeclaredField("captures").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val captures = capturesField.get(GuiRuntime) as MutableMap<GuiLeaseGate.Lease, Int>
            val finished = GuiRuntime::class.java.getDeclaredMethod("captureFinished", GuiLeaseGate.Lease::class.java)
                .apply { isAccessible = true }
            captures[lease] = 1
            val request = GuiCaptureRequest<String> { finished.invoke(GuiRuntime, lease) }
            try {
                request.submit { throw IllegalStateException("platform accepted then threw") }
                GuiRuntime.close(lease) { released.incrementAndGet() }
                assertFalse(lease.valid)
                assertEquals(0, released.get())
                request.future.complete(GuiResult.Value("late callback"))
                assertEquals(0, released.get()) // platform buffer has not been disposed
                request.resourcesDisposed()
                assertEquals(1, released.get())
                request.resourcesDisposed()
                assertEquals(1, released.get())
                assertFalse(captures.containsKey(lease))
            } finally { request.resourcesDisposed() }
        }
    }
}
