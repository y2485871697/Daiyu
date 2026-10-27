package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test

class ProductionRunContextCapacityTest {
    @Test fun summaryCannotDiscardToolSchemaOverheadWhenCheckingSendBudget() {
        val parent = Message(MessageId("assistant"), ConversationId("c"), RunId("r"), "assistant",
            emptyList(), RunStatus.RUNNING)
        val history = (0..7).map { index -> parent.copy(id = MessageId("u$index"), role = "user",
            status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text("body", "source evidence ".repeat(120)))) }
        val policy = CompressionPolicy(true, 4096, reserveTokens = 64, tokenTail = true)
        val binding = SummaryModelBinding("provider", "summary")
        val reply = SummaryCheckpoint.marker + "\n" + SummaryCheckpoint.sections.joinToString("\n") { "## $it\n- kept" }
        val runtime = ProductionRunContext(policy, parent, {
            BoundSummaryModel(binding, SummaryModelTransport { _, _ -> object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.TextFinal("summary", reply))
                }
                override fun cancel() {}
            } }, 20000, 15000, { SummaryRequestDeadline(10000) }, requireCheckpointFormat = true)
        }, automatic = false)
        val request = RequestCheckpoint("summary", "attempt", "owner", RequestOperation.NEXT)
        val messageTokens = runtime.localTokens(history, emptyList())
        for (billed in listOf<Long?>(null, messageTokens + policy.sendBudgetTokens!!)) {
            val local = if (billed == null) messageTokens + policy.sendBudgetTokens!! else messageTokens
            val error = assertThrows(IllegalArgumentException::class.java) {
                runtime.compact(history, billed, local, request, {}, {}, { false })
            }
            assertEquals("压缩后仍超出发送预算", error.message)
        }
    }
}
