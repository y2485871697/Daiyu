package app.daiyu.android.shizuku

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ShizukuRunSessionTest {
    private class Backend(val uid: Int = 2000) : ShizukuBackend {
        override val identity = ShizukuIdentity(uid, "service", "permission")
        val executions = AtomicInteger()
        var unknown = false
        var delayedClose = false
        var closeCallback: ((ShizukuCloseResult) -> Unit)? = null
        val closes = AtomicInteger()
        override fun execute(command: ShizukuCommand): ShizukuDispatch {
            executions.incrementAndGet()
            return if (unknown) ShizukuDispatch.Unknown(command.requestId, "lost_receipt")
            else ShizukuDispatch.Dispatched(ShizukuCommandHandle(command.requestId, identity))
        }
        override fun poll(handle: ShizukuCommandHandle, offset: Int, limit: Int) =
            ShizukuCommandPage(ShizukuOutcome.Completed(0), byteArrayOf(), 0, 0, 0, false, true)
        override fun cancel(handle: ShizukuCommandHandle) = ShizukuOutcome.Unknown("cancel_requested")
        override fun close() { closes.incrementAndGet() }
        override fun closeWithResult(onResult: (ShizukuCloseResult) -> Unit) {
            close()
            if (delayedClose) closeCallback = onResult else onResult(ShizukuCloseResult.Drained)
        }
    }

    private fun owner(runId: String = "run"): RunExecutionOwner {
        var owner: RunExecutionOwner? = null
        val executor = object : ReceiptBatchToolExecutor {
            override fun bindOwner(value: RunExecutionOwner) { owner = value }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = executor)) {}.run(
            Message(MessageId("assistant"), ConversationId("session"), RunId(runId), Message.ASSISTANT_ROLE,
                emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(owner)
    }

    private fun approved(uid: Int = 2000): ShizukuRunSession = ShizukuRunSession("session", "run").apply {
        edit(uid, "printf exact", 1234)
        approve(state)
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("expected fail-closed rejection") }
        catch (_: IllegalStateException) {}
        catch (_: IllegalArgumentException) {}
    }

    @Test fun constructionUnapprovedOpenAndMissingIdentityPerformNoIo() {
        val session = ShizukuRunSession("session", "run")
        var connects = 0
        rejected { session.open { _, _ -> connects++; Backend() } }
        session.edit(null, "printf exact", 1234)
        rejected { session.approve(session.state) }
        assertTrue(session.allowedWrites.isEmpty())
        assertEquals(0, connects)
        session.close()
        assertSame(ShizukuCloseResult.Drained, session.state.cleanup)
    }

    @Test fun grantIsExactAndEveryEditClearsIt() {
        val session = approved()
        assertEquals(setOf(RunShizukuCommands.writeEffect(2000, "printf exact", 1234)), session.allowedWrites)
        session.edit(0, "printf exact", 1234)
        assertTrue(session.allowedWrites.isEmpty())
        session.approve(session.state)
        assertEquals(setOf(RunShizukuCommands.writeEffect(0, "printf exact", 1234)), session.allowedWrites)
        session.edit(0, "printf exact ", 1234)
        assertTrue(session.allowedWrites.isEmpty())
        session.approve(session.state)
        session.edit(0, "printf exact ", 1235)
        assertTrue(session.allowedWrites.isEmpty())
    }

    @Test fun staleDisplayedSnapshotCannotApproveNewCommand() {
        val session = approved()
        val displayed = session.state
        session.edit(2000, "different", 1234)
        rejected { session.approve(displayed) }
        assertTrue(session.allowedWrites.isEmpty())
    }

    @Test fun invalidTimeoutAndScriptDoNotManufactureGrant() {
        for ((script, timeout) in listOf("" to 1L, "x\u0000y" to 1L, "x" to 0L, "x" to 120001L)) {
            val session = ShizukuRunSession("session", "run")
            session.edit(2000, script, timeout)
            rejected { session.approve(session.state) }
            assertTrue(session.allowedWrites.isEmpty())
        }
    }

    @Test fun explicitIdentityPassedToRealFactoryNoFallbackAndNoAutomaticCommand() {
        val session = approved(0)
        val backend = Backend(0)
        var connects = 0
        val commands = session.open { uid, scope ->
            assertEquals(0, uid)
            assertTrue(scope.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            connects++
            backend
        }
        val caps = SessionCapabilities("session", setOf(RunShizukuCommands.CAPABILITY))
        val auth = TaskAuthorization("session", "run", session.allowedWrites)
        commands.bind(owner(), caps, auth)
        commands.tool().prepare(commands.tool().definition.schema.parse("""{"script":"printf exact","timeout_ms":1234}"""))
        assertEquals(0, backend.executions.get())
        rejected { session.open { _, _ -> connects++; Backend() } }
        assertEquals(1, connects)
        session.close()
    }

    @Test fun identityMismatchClosesRatherThanAdoptsOrRetries() {
        val session = approved(0)
        val backend = Backend(2000)
        rejected { session.open { uid, _ -> assertEquals(0, uid); backend } }
        assertEquals(1, backend.closes.get())
        assertTrue(session.allowedWrites.isEmpty())
        rejected { session.open { _, _ -> fail("must not fallback"); backend } }
    }

    @Test fun approvalCannotBindAnotherRunOrSession() {
        val session = approved()
        val backend = Backend()
        val commands = session.open { _, _ -> backend }
        rejected { commands.bind(owner("other"), SessionCapabilities("session", setOf(RunShizukuCommands.CAPABILITY)),
            TaskAuthorization("session", "other", session.allowedWrites)) }
        rejected { commands.bind(owner(), SessionCapabilities("other", setOf(RunShizukuCommands.CAPABILITY)),
            TaskAuthorization("other", "run", session.allowedWrites)) }
        assertEquals(0, backend.executions.get())
        session.close()
    }

    private class DispatchFixture(val session: ShizukuRunSession, val backend: Backend) : AutoCloseable {
        val commands = session.open { _, _ -> backend }
        val caps = SessionCapabilities("session", setOf(RunShizukuCommands.CAPABILITY))
        val auth = TaskAuthorization("session", "run", session.allowedWrites)
        val token = ShizukuRunSessionTest().owner()
        val dispatcher = ToolDispatcher(ToolRegistry().register(commands.tool()).snapshot(), caps,
            ToolLedger(InMemoryToolLedgerStore()))
        init { commands.bind(token, caps, auth) }
        fun dispatch(id: String = "call", script: String = "printf exact", timeout: Long = 1234) = dispatcher.dispatchOwned(
            auth, listOf(ToolInvocation(ToolCallId(id), RunShizukuCommands.TOOL_NAME,
                JSONObject().put("script", script).put("timeout_ms", timeout).toString())), token).single().outcome
        override fun close() = session.close()
    }

    @Test fun defaultOpenFreezesCurrentExactGrantWithoutSeparateApprovalClick() {
        for (script in listOf("printf exact", "printf different")) {
            val session = ShizukuRunSession("session", "run").apply { edit(2000, script, 1234) }
            val backend = Backend()
            assertTrue(session.allowedWrites.isEmpty()) // Editing/mounting never issues authority.
            DispatchFixture(session, backend).use { f ->
                assertEquals(setOf(RunShizukuCommands.writeEffect(2000, script, 1234)), session.allowedWrites)
                assertEquals(0, backend.executions.get()) // Open alone does not execute a command.
                assertTrue(f.dispatch("changed", script + " ") is DispatchOutcome.NotExecuted)
                assertTrue(f.dispatch("timeout", script, 1235) is DispatchOutcome.NotExecuted)
                f.dispatch(script = script)
                assertEquals(1, backend.executions.get())
                f.dispatch("new-call", script)
                assertEquals(1, backend.executions.get())
                rejected { session.open { _, _ -> fail("one-use connection"); Backend() } }
            }
        }
    }

    @Test fun defaultOpenCannotResurrectRevocationOrUnknownOrChooseAnotherIdentity() {
        val revoked = ShizukuRunSession("session", "run").apply { edit(2000, "printf exact", 1234); revoke() }
        rejected { revoked.open { _, _ -> fail("revoked must not connect"); Backend() } }
        assertTrue(revoked.allowedWrites.isEmpty())
        val mismatch = ShizukuRunSession("session", "run").apply { edit(0, "printf exact", 1234) }
        val wrongBackend = Backend(2000)
        rejected { mismatch.open { uid, _ -> assertEquals(0, uid); wrongBackend } }
        assertEquals(1, wrongBackend.closes.get())
        assertTrue(mismatch.allowedWrites.isEmpty())
        val session = ShizukuRunSession("session", "run").apply { edit(2000, "printf exact", 1234) }
        DispatchFixture(session, Backend().apply { unknown = true }).use { f ->
            assertTrue(f.dispatch() is DispatchOutcome.Unknown)
            f.dispatch("new-call")
            assertEquals(1, f.backend.executions.get())
            assertTrue(session.allowedWrites.isEmpty())
            rejected { session.open { _, _ -> fail("unknown must not reconnect"); Backend() } }
        }
    }

    @Test fun exactApprovedCommandExecutesButDifferentScriptOrTimeoutDoesNot() {
        DispatchFixture(approved(), Backend()).use { f ->
            assertTrue(f.dispatch("different", "printf exact ") is DispatchOutcome.NotExecuted)
            assertTrue(f.dispatch("timeout", timeout = 1235) is DispatchOutcome.NotExecuted)
            assertEquals(0, f.backend.executions.get())
            f.dispatch()
            assertEquals(1, f.backend.executions.get())
            assertTrue(f.session.state.receipt is ExecutionReceipt.Confirmed)
        }
    }

    @Test fun sessionGateRejectsOtherCommandEvenIfHostAccidentallyIncludesItsWriteGrant() {
        val session = approved()
        val backend = Backend()
        val commands = session.open { _, _ -> backend }
        val caps = SessionCapabilities("session", setOf(RunShizukuCommands.CAPABILITY))
        val other = RunShizukuCommands.writeEffect(2000, "different", 1234)
        val auth = TaskAuthorization("session", "run", session.allowedWrites + other)
        val token = owner()
        commands.bind(token, caps, auth)
        val dispatcher = ToolDispatcher(ToolRegistry().register(commands.tool()).snapshot(), caps,
            ToolLedger(InMemoryToolLedgerStore()))
        val call = ToolInvocation(ToolCallId("other"), RunShizukuCommands.TOOL_NAME,
            """{"script":"different","timeout_ms":1234}""")
        val result = dispatcher.dispatchOwned(auth, listOf(call), token).single().outcome
        assertEquals(DispatchOutcome.NotExecuted("shizuku_command_not_approved"), result)
        assertEquals(0, backend.executions.get())
        session.close()
    }

    @Test fun revokedGrantIsRejectedEvenWithCopiedImmutableTaskGrants() {
        DispatchFixture(approved(), Backend()).use { f ->
            f.session.revoke()
            assertTrue(f.auth.allowedWrites.isNotEmpty()) // Old snapshot cannot resurrect the live gate.
            assertTrue(f.dispatch() is DispatchOutcome.NotExecuted)
            assertEquals(0, f.backend.executions.get())
            rejected { f.session.approve(f.session.state) }
        }
    }

    @Test fun unknownNeverRetriesUnderNewCallIdAndClosesRun() {
        DispatchFixture(approved(), Backend().apply { unknown = true }).use { f ->
            f.dispatch()
            assertTrue(f.session.state.receipt is ExecutionReceipt.Unknown)
            f.dispatch("new-call")
            assertEquals(1, f.backend.executions.get())
            assertTrue(f.session.allowedWrites.isEmpty())
            rejected { f.session.open { _, _ -> fail("unknown must not reconnect"); f.backend } }
        }
    }

    @Test fun revokeDuringOpenWaitsForLateBackendAndPreservesUnknownCleanup() {
        val session = approved()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val backend = Backend().apply { delayedClose = true }
        val worker = Thread {
            try { session.open { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); backend } }
            catch (caught: Throwable) { failure.set(caught) }
        }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val callback = AtomicReference<ShizukuCloseResult?>()
        session.revoke()
        session.closeWithResult { callback.set(it) }
        assertNull(callback.get())
        release.countDown()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertNotNull(failure.get())
        assertNull(callback.get())
        assertEquals(1, backend.closes.get())
        val unknown = ShizukuCloseResult.Unknown("remote_permission_revoked")
        checkNotNull(backend.closeCallback)(unknown)
        assertEquals(unknown, callback.get())
        assertEquals(unknown, session.state.cleanup)
    }

    @Test fun outerLedgerUnknownAlsoFencesTheSessionAndPreservesItsReason() {
        val session = approved()
        session.markOutcomeUnknown("receipt_persistence_failed")
        assertEquals(ExecutionReceipt.Unknown("receipt_persistence_failed"), session.state.receipt)
        assertTrue(session.allowedWrites.isEmpty())
        rejected { session.open { _, _ -> fail("outer unknown must not reconnect"); Backend() } }
        session.close()
        assertEquals(ExecutionReceipt.Unknown("receipt_persistence_failed"), session.state.receipt)
    }

    @Test fun failedOpenIsNotReusableOrFalselyDrained() {
        val session = approved()
        rejected { session.open { _, _ -> error("service unavailable") } }
        assertTrue(session.state.cleanup is ShizukuCloseResult.Unknown)
        val received = AtomicReference<ShizukuCloseResult?>()
        session.closeWithResult { received.set(it) }
        assertTrue(received.get() is ShizukuCloseResult.Unknown)
        rejected { session.open { _, _ -> fail("must not reconnect"); Backend() } }
    }
}
