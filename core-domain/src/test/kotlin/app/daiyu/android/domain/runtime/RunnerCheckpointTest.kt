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
}
