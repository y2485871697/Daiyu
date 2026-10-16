package app.daiyu.android.domain.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import org.junit.Assert.*
import org.junit.Test

class ToolContextAdmissionTest {
    private fun owner() = RunExecutionOwner.issue(Message(MessageId("assistant"), ConversationId("conversation"),
        RunId("task"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))

    private class Store : ToolLedgerStore {
        private val durable = InMemoryToolLedgerStore()
        var transactions = 0
        override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
            transactions++
            return durable.transaction(block)
        }
        fun rows(): List<LedgerEntry> = durable.transaction { it.toList() }
    }

    private inner class Fixture(write: Boolean = true) {
        val token = owner()
        val effect = if (write) WriteEffect("test", "replace", "target", "body") else null
        val task = TaskAuthorization("session", "task", setOfNotNull(effect))
        val store = Store()
        var executions = 0
        var plainExecutions = 0
        var failAdmission = false
        var receipt: ExecutionReceipt = ExecutionReceipt.Confirmed("private payload", isError = true)
        val tool = object : RegisteredTool {
            override val definition = ToolDefinition("guarded", 1, "Guarded test tool", "test",
                if (write) ToolEffect.WRITE else ToolEffect.READ_ONLY, ToolSchema())
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual(effect, admit = { context ->
                if (failAdmission) error("private admission exception")
                when {
                    context.owner !== token -> ExecutionReceipt.NotExecuted("owner_mismatch")
                    context.authorization !== task -> ExecutionReceipt.NotExecuted("authorization_mismatch")
                    else -> null
                }
            }) { context ->
                assertSame(token, context.owner)
                assertSame(task, context.authorization)
                executions++
                receipt
            }
        }
        private val plain = object : RegisteredTool {
            override val definition = ToolDefinition("plain", 1, "Default test tool", "test", ToolEffect.READ_ONLY, ToolSchema())
            override fun prepare(arguments: ToolArguments) = PreparedTool {
                plainExecutions++
                ExecutionReceipt.Confirmed("plain")
            }
        }
        val dispatcher = ToolDispatcher(ToolRegistry().register(tool).register(plain).snapshot(),
            SessionCapabilities("session", setOf("test")), ToolLedger(store))
        fun call(id: String = "call", name: String = "guarded") = ToolInvocation(ToolCallId(id), name, "{}")
        fun dispatch(id: String = "call", authorization: TaskAuthorization = task, runOwner: RunExecutionOwner = token) =
            dispatcher.dispatchOwned(authorization, listOf(call(id)), runOwner).single().outcome
    }

    @Test fun admissionRejectsCopiedIdentitiesBeforeConfirmedCallOrEffectReplayWithoutTouchingLedger() {
        for (write in listOf(false, true)) {
            val f = Fixture(write)
            val first = f.dispatch() as DispatchOutcome.Confirmed
            assertFalse(first.replayed)
            assertEquals("private payload", first.payload)
            assertTrue(first.isError)
            val rows = f.store.rows()
            val transactions = f.store.transactions
            val otherOwner = owner()
            assertNotSame(f.token, otherOwner)
            assertEquals(f.token.conversationId, otherOwner.conversationId)
            assertEquals(f.token.runId, otherOwner.runId)
            assertEquals(f.token.assistantMessageId, otherOwner.assistantMessageId)
            val otherTask = TaskAuthorization(f.task.sessionId, f.task.taskId, f.task.allowedWrites)
            val ids = if (write) listOf("call", "equivalent-effect") else listOf("call")
            for (id in ids) {
                assertEquals(DispatchOutcome.NotExecuted("owner_mismatch"), f.dispatch(id, runOwner = otherOwner))
                assertEquals(DispatchOutcome.NotExecuted("authorization_mismatch"), f.dispatch(id, authorization = otherTask))
                assertEquals(transactions, f.store.transactions)
                assertEquals(rows, f.store.rows())
                assertEquals(1, f.executions)
            }
            for (id in ids) {
                val replay = f.dispatch(id) as DispatchOutcome.Confirmed
                assertTrue(replay.replayed)
                assertEquals(first.payload, replay.payload)
                assertEquals(first.isError, replay.isError)
            }
            assertEquals(1, f.executions)
        }
    }

    @Test fun wholeBatchAdmissionCompletesBeforeAnyReservationOrExecution() {
        val f = Fixture()
        val result = f.dispatcher.dispatchOwned(TaskAuthorization("session", "task", f.task.allowedWrites),
            listOf(f.call("first", "plain"), f.call()), f.token)
        assertEquals(2, result.size)
        assertTrue(result.all { it.outcome == DispatchOutcome.NotExecuted("authorization_mismatch") })
        assertEquals(0, f.store.transactions)
        assertTrue(f.store.rows().isEmpty())
        assertEquals(0, f.executions)
        assertEquals(0, f.plainExecutions)
    }

    @Test fun admissionExceptionFailsClosedBeforeReservationAndBeforeReplayWithoutLeakingException() {
        val f = Fixture()
        f.failAdmission = true
        assertEquals(DispatchOutcome.NotExecuted("context_admission_failed"), f.dispatch())
        assertEquals(0, f.store.transactions)
        assertEquals(0, f.executions)
        f.failAdmission = false
        assertTrue(f.dispatch() is DispatchOutcome.Confirmed)
        val transactions = f.store.transactions
        val rows = f.store.rows()
        f.failAdmission = true
        assertEquals(DispatchOutcome.NotExecuted("context_admission_failed"), f.dispatch())
        assertEquals(transactions, f.store.transactions)
        assertEquals(rows, f.store.rows())
        assertEquals(1, f.executions)
    }

    @Test fun admittedUnknownCallAndEquivalentEffectStillNeverRetry() {
        val f = Fixture()
        f.receipt = ExecutionReceipt.Unknown("remote_unconfirmed")
        assertEquals(DispatchOutcome.Unknown("remote_unconfirmed"), f.dispatch())
        val transactions = f.store.transactions
        assertEquals(DispatchOutcome.NotExecuted("owner_mismatch"), f.dispatch(runOwner = owner()))
        assertEquals(transactions, f.store.transactions)
        for (id in listOf("call", "equivalent-effect")) {
            assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"), f.dispatch(id))
        }
        assertEquals(1, f.executions)
        assertEquals(LedgerState.UNKNOWN, f.store.rows().single().state)
    }

    @Test fun legacyContextualFactoryKeepsDefaultAdmissionAndReplayBehavior() {
        var executions = 0
        val effect = WriteEffect("test", "replace", "target", "body")
        val tool = object : RegisteredTool {
            override val definition = ToolDefinition("legacy", 1, "Legacy contextual tool", "test", ToolEffect.WRITE, ToolSchema())
            // Keep the original positional factory signature as well as default admission.
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual(effect, {
                executions++
                ExecutionReceipt.Confirmed("legacy")
            })
        }
        val store = Store()
        val dispatcher = ToolDispatcher(ToolRegistry().register(tool).snapshot(),
            SessionCapabilities("session", setOf("test")), ToolLedger(store))
        fun dispatch(id: String) = dispatcher.dispatchOwned(TaskAuthorization("session", "task", setOf(effect)),
            listOf(ToolInvocation(ToolCallId(id), "legacy", "{}")), owner()).single().outcome
        assertEquals(DispatchOutcome.Confirmed("legacy", false), dispatch("first"))
        assertEquals(DispatchOutcome.Confirmed("legacy", false, replayed = true), dispatch("first"))
        assertEquals(DispatchOutcome.Confirmed("legacy", false, replayed = true), dispatch("equivalent-effect"))
        assertEquals(1, executions)
    }
}
