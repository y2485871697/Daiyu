package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class RunnerPreparationUsageTest {
    private val initial = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        "assistant", emptyList(), RunStatus.RUNNING)
    private val summary = RequestUsageCheckpoint(RequestCheckpoint("opaque-request", "admitted-attempt", "preparation-owner",
        RequestOperation.NEXT), ModelEvent.Usage(null, 0, 0), UsageRequestSource.SUMMARY)

    @Test fun seedPrecedesOwnerBindingAndEveryMainCheckpointCarriesUnchangedSummary() {
        val checkpoints = mutableListOf<RunCheckpoint>()
        var seedReads = 0; var opens = 0
        val runtime = object : PreparationRuntimeStore {
            override fun preparationUsage(initial: Message): List<RequestUsageCheckpoint> {
                assertTrue(checkpoints.isEmpty()); seedReads++; return listOf(summary)
            }
            override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                assertEquals(listOf(summary), checkpoint.usage.filter { it.source == UsageRequestSource.SUMMARY })
                assertFalse(checkpoint.owner == summary.request.owner)
                assertEquals(checkpoints.size + 1L, checkpoint.revision)
                checkpoints.add(checkpoint)
            }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("no recovery")
        }
        val model = ModelPort {
            opens++; assertTrue(checkpoints.isNotEmpty())
            assertEquals(summary, checkpoints.last().usage.single())
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.Usage(100, 20, 5)); sink(ModelEvent.TextFinal("text", "done"))
                }
                override fun cancel() {}
            }
        }
        val result = ChatRunner(model, object : ConversationStore { override fun save(message: Message) = error("atomic only") },
            BatteryTool { error("no tools") }, RunnerOptions(runtimeStore = runtime)) {}.run(initial, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status); assertEquals(1, opens); assertEquals(1, seedReads)
        assertEquals(2, checkpoints.last().usage.size)
        assertEquals(ModelEvent.Usage(100, 20, 5), checkpoints.last().usage.single { it.source == UsageRequestSource.MAIN }.snapshot)
    }

    @Test fun revokedSeedCannotOpenMainTransportOrBeRecoveredAsANewSummaryPost() {
        var opens = 0
        val runtime = object : PreparationRuntimeStore {
            override fun preparationUsage(initial: Message): List<RequestUsageCheckpoint> = error("revoked")
            override fun commitCheckpoint(checkpoint: RunCheckpoint) = error("seed must fail first")
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("no implicit recovery")
        }
        val runner = ChatRunner(ModelPort { opens++; error("must not connect") },
            object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("no tools") },
            RunnerOptions(runtimeStore = runtime)) {}
        try { runner.run(initial, emptyList()); fail("revoked seed accepted") } catch (_: IllegalStateException) {}
        assertEquals(0, opens)
    }
}
