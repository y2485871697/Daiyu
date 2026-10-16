package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.*
import app.daiyu.android.shizuku.*
import app.daiyu.android.skills.SkillRunSession
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM contracts. The backend is a receipt fake; no shell/process/Android I/O. */
class ControllerShizukuPreparationTest {
    private fun preparation(command: String = "printf exact", uid: Int = 2000, timeout: Long = 1234) =
        ControllerShizukuPreparation(null, "用户草稿", uid, command, timeout,
            ConversationId("reserved-conversation"), RunId("reserved-run"))

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") }
        catch (_: IllegalArgumentException) {}
        catch (_: IllegalStateException) {}
    }

    private class Backend(val result: ShizukuCloseResult = ShizukuCloseResult.Drained) : ShizukuBackend {
        override val identity = ShizukuIdentity(2000, "service", "permission")
        var executions = 0
        val requests = mutableListOf<ShizukuCommand>()
        override fun execute(command: ShizukuCommand): ShizukuDispatch {
            executions++; requests += command
            return ShizukuDispatch.Dispatched(ShizukuCommandHandle(command.requestId, identity))
        }
        override fun poll(handle: ShizukuCommandHandle, offset: Int, limit: Int) =
            ShizukuCommandPage(ShizukuOutcome.Completed(0), byteArrayOf(), 0, 0, 0, false, true)
        override fun cancel(handle: ShizukuCommandHandle) = ShizukuOutcome.Unknown("cancel_requested")
        override fun close() {}
        override fun closeWithResult(onResult: (ShizukuCloseResult) -> Unit) { onResult(result) }
    }

    @Test fun uidAndTimeoutParseFailClosedWithoutRootOrShellFallback() {
        assertEquals(0, ControllerShizukuPreparation.parseUid("0"))
        assertEquals(2000, ControllerShizukuPreparation.parseUid("2000"))
        for (input in listOf("", " ", "root", "shell", " 0", "0 ", "02000", "-1", "2001", "+0", "9999999999999"))
            assertNull(input, ControllerShizukuPreparation.parseUid(input))
        for (input in listOf("", "-1", "0", "120001", "1.0", "1e3", "+1", " 1000", "99999999999999999999"))
            assertNull(input, ControllerShizukuPreparation.parseTimeout(input))
        assertEquals(1L, ControllerShizukuPreparation.parseTimeout("1"))
        assertEquals(120000L, ControllerShizukuPreparation.parseTimeout("120000"))
    }

    @Test fun invalidCommandsCannotProduceAPreviewOrAuthorization() {
        rejected { preparation(uid = 1000) }
        rejected { preparation(command = " ") }
        rejected { preparation(command = "x\u0000y") }
        rejected { preparation(command = "x".repeat(16385)) }
        rejected { preparation(timeout = 0) }
        rejected { preparation(timeout = 120001) }
    }

    @Test fun previewHasReservedOwnerAndPreservesExactCommandButDoesNotConnect() {
        val preview = preparation(command = "  printf 'literal'\n")
        assertEquals("reserved-conversation", preview.conversationId.value)
        assertEquals("reserved-run", preview.runId.value)
        assertEquals("  printf 'literal'\n", preview.command)
        assertTrue(preview.messageText.endsWith(preview.command))
        assertTrue(preview.messageText.startsWith(preview.text))
        var connects = 0
        rejected { preview.open { _, _ -> connects++; Backend() } }
        assertFalse(preview.isSubmitted())
        assertEquals(0, connects)
    }

    @Test fun consentIssuesOnlyExactFrozenEffectAndStillDoesNotExecute() {
        val preview = preparation()
        val auth = preview.approve()
        assertEquals(preview.runId.value, auth.sessionId)
        assertEquals(preview.runId.value, auth.taskId)
        assertEquals(setOf(RunShizukuCommands.writeEffect(2000, "printf exact", 1234)), auth.allowedWrites)
        assertFalse(RunShizukuCommands.writeEffect(0, "printf exact", 1234) in auth.allowedWrites)
        assertFalse(RunShizukuCommands.writeEffect(2000, "printf exact ", 1234) in auth.allowedWrites)
        assertFalse(RunShizukuCommands.writeEffect(2000, "printf exact", 1235) in auth.allowedWrites)
        rejected { preview.approve() }
        val backend = Backend()
        preview.open { uid, _ -> assertEquals(2000, uid); backend }
        assertEquals(0, backend.executions)
        rejected { preview.open { _, _ -> fail("must not reconnect"); backend } }
        preview.closeWithResult { assertSame(ShizukuCloseResult.Drained, it) }
    }

    @Test fun rejectionAndCancellationNeverCreateOrReuseAnApproval() {
        val draft = preparation()
        draft.cancel()
        rejected { draft.approve() }
        rejected { draft.open { _, _ -> fail("must not connect"); Backend() } }
        draft.closeWithResult { assertSame(ShizukuCloseResult.Drained, it) }
        val submitted = preparation()
        submitted.approve()
        submitted.cancel()
        rejected { submitted.open { _, _ -> fail("must not connect"); Backend() } }
        rejected { submitted.approve() }
    }

    @Test fun unknownConnectIsNotRetriedWithANewScopeOrRun() {
        val preview = preparation()
        preview.approve()
        var connects = 0
        rejected { preview.open { _, _ -> connects++; error("unconfirmed connection") } }
        rejected { preview.open { _, _ -> connects++; Backend() } }
        rejected { preview.approve() }
        var cleanup: ShizukuCloseResult? = null
        preview.closeWithResult { cleanup = it }
        assertTrue(cleanup is ShizukuCloseResult.Unknown)
        assertEquals(1, connects)
        assertEquals("reserved-run", preview.runId.value)
    }

    @Test fun identicalRunIdentifiersAreNotAnApprovalProof() {
        val approved = preparation()
        approved.approve()
        val impostor = preparation(command = "printf changed")
        assertEquals(approved.runId, impostor.runId)
        assertEquals(approved.conversationId, impostor.conversationId)
        rejected { impostor.open { _, _ -> fail("IDs do not authorize connection"); Backend() } }
        approved.cancel()
        impostor.cancel()
    }

    @Test fun cancellationDuringOpenFencesLateBackendAndWaitsForItsCleanup() {
        val preview = preparation()
        preview.approve()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val backend = Backend(ShizukuCloseResult.Unknown("late backend unconfirmed"))
        val worker = Thread {
            try {
                preview.open { _, _ ->
                    entered.countDown()
                    check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    backend
                }
                failures.set(AssertionError("cancelled backend was published"))
            } catch (_: IllegalStateException) { /* expected fence */ }
            catch (failure: Throwable) { failures.set(failure) }
        }
        worker.start()
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        var cleanup: ShizukuCloseResult? = null
        preview.cancel()
        preview.closeWithResult { cleanup = it }
        assertNull(cleanup)
        release.countDown()
        worker.join(3000)
        assertFalse(worker.isAlive)
        failures.get()?.let { throw AssertionError(it) }
        assertTrue(cleanup is ShizukuCloseResult.Unknown)
        assertEquals(0, backend.executions)
        rejected { preview.open { _, _ -> fail("late cleanup cannot reopen"); backend } }
    }

    @Test fun controllerCleanupNeverTurnsUnknownIntoDrained() {
        val unknown = ControllerShizukuCleanup()
        assertFalse(unknown.drained)
        unknown.observe(RunCatalogCloseResult.Unknown("lost acknowledgement"))
        unknown.observe(RunCatalogCloseResult.Drained)
        assertFalse(unknown.drained)
        assertTrue(unknown.result is RunCatalogCloseResult.Unknown)
        val drained = ControllerShizukuCleanup()
        drained.observe(RunCatalogCloseResult.Drained)
        assertTrue(drained.drained)
    }

    @Test fun preparedApprovalReachesRealCatalogDispatchAndLedgerReplaysSameIdOnly() {
        val preview = preparation()
        val auth = preview.approve()
        val backend = Backend()
        val binding = preview.open { _, _ -> backend }
        val caps = SessionCapabilities(preview.runId.value, setOf(RunShizukuCommands.CAPABILITY))
        val message = Message(MessageId("assistant"), preview.conversationId, preview.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val tools = RunCatalogTools(ToolRegistry(), caps, ToolLedger(InMemoryToolLedgerStore()),
            preview.conversationId, preview.runId, authorization = auth, shizuku = binding) { actualCaps, actualAuth ->
            assertSame(caps, actualCaps)
            assertSame(auth, actualAuth)
            SkillRunSession.create(actualAuth, actualCaps.enabled, emptyList())
        }
        tools.bindOwner(runnerOwner(message))
        assertEquals(listOf(RunShizukuCommands.TOOL_NAME), tools.definitions.map { it.name })
        fun call(script: String, id: String = "same-call") = ToolInvocation(ToolCallId(id),
            RunShizukuCommands.TOOL_NAME, """{"script":"$script","timeout_ms":1234}""")
        val denied = tools.execute(message, listOf(call("printf changed", "changed"))) { false }.single()
        assertEquals(DispatchOutcome.NotExecuted("task_write_not_authorized"), denied.outcome)
        assertEquals(0, backend.executions)
        val receipt = tools.execute(message, listOf(call("printf exact"))) { false }.single().outcome
        assertTrue(receipt is DispatchOutcome.Confirmed)
        val replay = tools.execute(message, listOf(call("printf exact"))) { false }.single().outcome
        assertTrue((replay as DispatchOutcome.Confirmed).replayed)
        assertEquals(1, backend.executions)
        assertEquals(preview.command, backend.requests.single().script)
        assertEquals(preview.timeoutMillis, backend.requests.single().timeoutMillis)
        val cleanup = ControllerShizukuCleanup()
        tools.closeWithResult(cleanup::observe)
        assertTrue(cleanup.drained)
    }

    @Test fun catalogUnknownPinsControllerCleanupAndConsumedPreview() {
        val preview = preparation()
        val auth = preview.approve()
        val backend = Backend(ShizukuCloseResult.Unknown("device still running"))
        val tools = RunCatalogTools(ToolRegistry(), SessionCapabilities(preview.runId.value, emptySet()),
            ToolLedger(InMemoryToolLedgerStore()), preview.conversationId, preview.runId,
            authorization = auth, shizuku = preview.open { _, _ -> backend }) { caps, authorization ->
            SkillRunSession.create(authorization, caps.enabled, emptyList())
        }
        val cleanup = ControllerShizukuCleanup()
        tools.closeWithResult(cleanup::observe)
        assertTrue(cleanup.result is RunCatalogCloseResult.Unknown)
        assertFalse(cleanup.drained)
        rejected { preview.open { _, _ -> fail("UNKNOWN must not reconnect"); backend } }
    }
}
