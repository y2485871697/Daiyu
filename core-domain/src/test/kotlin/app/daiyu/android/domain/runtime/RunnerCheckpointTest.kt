package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class RunnerCheckpointTest {
    private val initial = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    private val call = ToolInvocation(ToolCallId("x"), "read", "{}")

    @Test fun atomicStoreIsOnlyWriterAndCheckpointCommitsBeforeTool() {
        val checkpoints = mutableListOf<RunCheckpoint>()
        var executed = 0
        val runtime = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                assertEquals(checkpoints.size.toLong() + 1, checkpoint.revision)
                if (checkpoints.isNotEmpty()) assertEquals(checkpoints.first().owner, checkpoint.owner)
                assertTrue(checkpoint.owner.isNotBlank())
                checkpoints.add(checkpoint)
            }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = checkpoint
        }
        var rounds = 0
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                if (rounds++ == 0) sink(ModelEvent.ToolReady(call))
                else assertEquals("saved", outputs.single().output)
            }
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) { error("must use atomic writer") } }, BatteryTool {
            assertEquals(ToolDisposition.IN_FLIGHT, checkpoints.last().tools.single().disposition)
            executed++; "saved"
        }, RunnerOptions(runtimeStore = runtime)) {}
        assertEquals(RunStatus.COMPLETED, runner.run(initial, emptyList()).status)
        assertEquals(1, executed)
        assertEquals(ExecutionPhase.TERMINAL, checkpoints.last().phase)
        assertEquals(RunStatus.COMPLETED, checkpoints.last().message.status)
    }

    @Test fun processDeathMarksInflightUnknownWithoutOpeningModelOrExecutingTool() {
        val snapshot = RunCheckpoint(initial.copy(blocks = listOf(MessageBlock.Text("t", "partial"))), emptyList(),
            ExecutionPhase.TOOL_EXECUTING, listOf(ToolRecord(call, ToolDisposition.IN_FLIGHT)), requestUnconfirmed = true)
        val saved = mutableListOf<RunCheckpoint>(); var reconciled = 0
        val runtime = object : RuntimeStore { override fun save(checkpoint: RunCheckpoint) { saved.add(checkpoint) } }
        val messages = object : ConversationStore {
            override fun save(message: Message) { reconciled++; assertEquals(RunStatus.INTERRUPTED, message.status) }
        }
        val runner = ChatRunner(ModelPort { error("must not reopen") }, messages, BatteryTool { error("must not execute") }, RunnerOptions(runtimeStore = runtime)) {}
        val result = runner.recover(snapshot)
        assertEquals(RunStatus.INTERRUPTED, result.status)
        assertEquals("partial", (result.blocks.single() as MessageBlock.Text).text)
        assertEquals(ToolDisposition.UNKNOWN, saved.last().tools.single().disposition)
        assertTrue(saved.last().messageReconciled); assertEquals(1, reconciled)
    }

    @Test fun recoveryRetriesMessageProjectionAfterFailedSave() {
        var latest: RunCheckpoint? = null; var attempts = 0
        val runtime = object : RuntimeStore { override fun save(checkpoint: RunCheckpoint) { latest = checkpoint } }
        val messages = object : ConversationStore {
            override fun save(message: Message) { error("reconciliation override required") }
            override fun reconcileRecovery(message: Message) { if (attempts++ == 0) throw PersistenceException(IllegalStateException()) }
        }
        val checkpoint = RunCheckpoint(initial, emptyList(), ExecutionPhase.STREAMING)
        try { ProcessRecovery.recover(checkpoint, messages, runtime); fail("save must fail") } catch (_: PersistenceException) {}
        assertEquals(ExecutionPhase.TERMINAL, latest!!.phase); assertFalse(latest!!.messageReconciled)
        val recovered = ProcessRecovery.recover(latest!!, messages, runtime)
        assertEquals(2, attempts); assertTrue(recovered.messageReconciled)
        ProcessRecovery.recover(recovered, messages, runtime)
        assertEquals(2, attempts)
    }

    @Test fun atomicRecoveryReconcilesActualCompletedRowWithoutDowngrade() {
        val completed = initial.copy(status = RunStatus.COMPLETED)
        val runtime = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) { error("reconcile only") }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint =
                checkpoint.copy(message = completed, phase = ExecutionPhase.TERMINAL, messageReconciled = true)
        }
        val messages = object : ConversationStore { override fun save(message: Message) { error("atomic recovery owns transaction") } }
        val recovered = ProcessRecovery.recover(RunCheckpoint(initial, emptyList(), ExecutionPhase.STREAMING), messages, runtime)
        assertEquals(RunStatus.COMPLETED, recovered.message.status)
    }

    @Test fun nonDurableRecoveryWritesInterruptedIntentBeforeProjectionAndMarksReceipt() {
        val writes = mutableListOf<RunCheckpoint>()
        val projected = mutableListOf<Message>()
        val runtime = object : RuntimeStore {
            override val durable = false
            override fun save(checkpoint: RunCheckpoint) { writes += checkpoint }
        }
        val messages = object : ConversationStore {
            override fun save(message: Message) { error("recovery must use projection hook") }
            override fun reconcileRecovery(message: Message) { projected += message }
        }
        val result = ProcessRecovery.recover(
            RunCheckpoint(initial, emptyList(), ExecutionPhase.STREAMING), messages, runtime)
        assertEquals(RunStatus.INTERRUPTED, result.message.status)
        assertEquals(listOf(false, true), writes.map { it.messageReconciled })
        assertEquals(listOf(RunStatus.INTERRUPTED), projected.map { it.status })
        assertTrue(result.messageReconciled)
    }

    @Test fun atomicRecoveryReceiptIsIdempotentAndPublishFailureDoesNotChangeCommit() {
        val committed = mutableListOf<RunCheckpoint>()
        val runtime = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) { committed += checkpoint }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint {
                val prior = committed.lastOrNull()
                val value = prior ?: checkpoint.copy(message = checkpoint.message.copy(status = RunStatus.INTERRUPTED),
                    phase = ExecutionPhase.TERMINAL, messageReconciled = true)
                if (prior == null) committed += value
                return value
            }
        }
        val messages = object : ConversationStore { override fun save(message: Message) { error("atomic store owns projection") } }
        val checkpoint = RunCheckpoint(initial, emptyList(), ExecutionPhase.TOOL_EXECUTING,
            listOf(ToolRecord(call, ToolDisposition.IN_FLIGHT)), requestUnconfirmed = true)
        val first = ProcessRecovery.recover(checkpoint, messages, runtime) { error("UI callback is not durable state") }
        val second = ProcessRecovery.recover(first, messages, runtime)
        assertEquals(RunStatus.INTERRUPTED, first.message.status)
        assertEquals(first, second)
        assertEquals(1, committed.size)
    }

    @Test fun recoveryNeverReplaysInFlightToolOrUnconfirmedModelRequest() {
        val checkpoint = RunCheckpoint(initial, emptyList(), ExecutionPhase.RECONNECTING,
            listOf(ToolRecord(call, ToolDisposition.IN_FLIGHT)), requestUnconfirmed = true)
        var opened = false
        var executed = false
        val runtime = object : RuntimeStore { override fun save(checkpoint: RunCheckpoint) {
            assertEquals(ToolDisposition.UNKNOWN, checkpoint.tools.single().disposition)
        } }
        val runner = ChatRunner(ModelPort { opened = true; error("recovery must not open model") },
            object : ConversationStore { override fun save(message: Message) {} override fun reconcileRecovery(message: Message) {} },
            BatteryTool { executed = true; "bad" }, RunnerOptions(runtimeStore = runtime)) {}
        val result = runner.recover(checkpoint)
        assertEquals(RunStatus.INTERRUPTED, result.status)
        assertFalse(opened)
        assertFalse(executed)
        assertTrue(result.error!!.contains("未自动重发"))
    }

    @Test fun legacyCompletedReceiptLossNeverWritesFailedOverCompleted() {
        val terminalAttempts = mutableListOf<Message>()
        var persisted: Message? = null
        val messages = object : ConversationStore {
            override fun save(message: Message) {
                if (message.status != RunStatus.RUNNING) terminalAttempts += message
                persisted = message
                if (message.status == RunStatus.COMPLETED) throw PersistenceException(IllegalStateException("receipt lost"))
            }
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.TextFinal("answer", "done"))
            }
            override fun cancel() {}
        } }, messages, BatteryTool { error("no tool expected") }) {}
        val result = runner.run(initial, emptyList())
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("保存失败，已显示内容可能未落盘", result.error)
        assertEquals(listOf(RunStatus.COMPLETED), terminalAttempts.map { it.status })
        assertEquals(RunStatus.COMPLETED, persisted!!.status)
        assertEquals("done", persisted!!.blocks.filterIsInstance<MessageBlock.Text>().single().text)
    }

    /** Contract fake; Android transactions and live-owner takeover are verified by storage owners. */
    private class StatefulAtomicStore(private var loseCompletedReceipt: Boolean = false) : AtomicRuntimeStore {
        val commitAttempts = mutableListOf<RunCheckpoint>()
        val commits = mutableListOf<RunCheckpoint>()
        var latest: RunCheckpoint? = null
            private set
        var reconcileCalls = 0
            private set
        var reconciliationWrites = 0
            private set
        override fun commitCheckpoint(checkpoint: RunCheckpoint) {
            commitAttempts += checkpoint
            val prior = latest
            check(checkpoint.owner.isNotBlank()) { "missing owner" }
            check(checkpoint.revision == (prior?.revision ?: 0L) + 1L) { "stale revision" }
            if (prior != null) {
                check(checkpoint.message.id == prior.message.id)
                check(checkpoint.message.conversationId == prior.message.conversationId)
                check(checkpoint.message.runId == prior.message.runId)
                check(checkpoint.owner == prior.owner) { "foreign owner" }
                check(prior.phase != ExecutionPhase.TERMINAL) { "terminal is immutable" }
            }
            val committed = checkpoint.copy(messageReconciled = true)
            latest = committed; commits += committed
            if (loseCompletedReceipt && committed.phase == ExecutionPhase.TERMINAL &&
                committed.message.status == RunStatus.COMPLETED) {
                loseCompletedReceipt = false
                throw PersistenceException(IllegalStateException("receipt lost"))
            }
        }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint {
            reconcileCalls++
            val prior = latest
            if (prior != null) {
                check(checkpoint.message.id == prior.message.id)
                check(checkpoint.message.conversationId == prior.message.conversationId)
                check(checkpoint.message.runId == prior.message.runId)
                if (prior.phase == ExecutionPhase.TERMINAL) return prior
            }
            check(checkpoint.phase == ExecutionPhase.TERMINAL)
            check(checkpoint.message.status != RunStatus.RUNNING)
            val committed = checkpoint.copy(owner = "recovery-owner", revision = (prior?.revision ?: 0L) + 1L,
                messageReconciled = true)
            latest = committed; reconciliationWrites++
            return committed
        }
    }
    private fun rejectsAtomicCommit(block: () -> Unit) {
        try { block(); fail("commit must be rejected") } catch (_: IllegalStateException) { }
    }
    @Test fun statefulAtomicFakeRejectsForeignOwnerStaleRevisionAndTerminalWrites() {
        val runtime = StatefulAtomicStore()
        val first = RunCheckpoint(initial, emptyList(), ExecutionPhase.STREAMING, owner = "live-owner", revision = 1L)
        runtime.commitCheckpoint(first)
        rejectsAtomicCommit { runtime.commitCheckpoint(first.copy(owner = "other-owner", revision = 2L)) }
        rejectsAtomicCommit { runtime.commitCheckpoint(first.copy(revision = 1L)) }
        assertEquals(first, runtime.latest)
        val terminal = first.copy(message = initial.copy(status = RunStatus.COMPLETED), phase = ExecutionPhase.TERMINAL, revision = 2L)
        runtime.commitCheckpoint(terminal)
        rejectsAtomicCommit { runtime.commitCheckpoint(terminal.copy(message = initial.copy(status = RunStatus.FAILED), revision = 3L)) }
        rejectsAtomicCommit { runtime.commitCheckpoint(terminal.copy(revision = 3L)) }
        assertEquals(terminal, runtime.latest); assertEquals(listOf(first, terminal), runtime.commits)
    }
    @Test fun atomicCompletedReceiptLossReconcilesActualRowWithoutSecondCommit() {
        val runtime = StatefulAtomicStore(loseCompletedReceipt = true)
        val messages = object : ConversationStore {
            override fun save(message: Message) { error("atomic store must be the only writer") }
        }
        val runner = ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { sink(ModelEvent.TextFinal("answer", "done")) }
            override fun cancel() {}
        } }, messages, BatteryTool { error("no tool expected") }, RunnerOptions(runtimeStore = runtime)) {}
        val result = runner.run(initial, emptyList())
        val completed = runtime.latest!!
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(result, completed.message); assertEquals(ExecutionPhase.TERMINAL, completed.phase)
        assertTrue(completed.messageReconciled); assertTrue(completed.owner.isNotBlank())
        assertTrue(runtime.commits.all { it.owner == completed.owner })
        assertEquals(runtime.commits.indices.map { it.toLong() + 1L }, runtime.commits.map { it.revision })
        assertEquals(1, runtime.commitAttempts.count { it.phase == ExecutionPhase.TERMINAL })
        assertEquals(runtime.commits.size, runtime.commitAttempts.size)
        assertEquals(1, runtime.reconcileCalls); assertEquals(0, runtime.reconciliationWrites)
        val attemptsBefore = runtime.commitAttempts.size
        val recovered = ProcessRecovery.recover(runtime.commits.first(), messages, runtime)
        assertEquals(completed, recovered)
        assertEquals(completed, ProcessRecovery.recover(recovered, messages, runtime))
        assertEquals(completed, runtime.latest); assertEquals(attemptsBefore, runtime.commitAttempts.size)
        assertEquals(0, runtime.reconciliationWrites); assertEquals(3, runtime.reconcileCalls)
    }
    @Test fun atomicRecoveryAcquiresOwnerAndDoesNotCommitAgainOnRepeatedRecovery() {
        val runtime = StatefulAtomicStore()
        val running = RunCheckpoint(initial, emptyList(), ExecutionPhase.TOOL_EXECUTING,
            tools = listOf(ToolRecord(call, ToolDisposition.IN_FLIGHT)), requestUnconfirmed = true,
            owner = "live-owner", revision = 1L)
        runtime.commitCheckpoint(running)
        val messages = object : ConversationStore {
            override fun save(message: Message) { error("atomic recovery owns the message projection") }
        }
        val recovered = ProcessRecovery.recover(running, messages, runtime)
        assertEquals(RunStatus.INTERRUPTED, recovered.message.status)
        assertEquals(ExecutionPhase.TERMINAL, recovered.phase)
        assertEquals(ToolDisposition.UNKNOWN, recovered.tools.single().disposition)
        assertEquals("recovery-owner", recovered.owner); assertEquals(2L, recovered.revision)
        assertTrue(recovered.messageReconciled)
        assertEquals(recovered, ProcessRecovery.recover(running, messages, runtime))
        assertEquals(recovered, runtime.latest); assertEquals(1, runtime.commitAttempts.size)
        assertEquals(1, runtime.reconciliationWrites); assertEquals(2, runtime.reconcileCalls)
    }

}
