package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.DaiyuContextEstimate
import java.util.UUID

/** Replay a fitting prefix once; larger histories use bounded, text-only evidence chunks. */
internal class HarnessContextSummarizer(
    private val bound: BoundSummaryModel,
    private val cancellation: ContextPreparationCancellation,
    private val tools: List<ToolDefinition> = emptyList(),
    private val systemPrompt: String = "",
    private val nextModel: (() -> BoundSummaryModel)? = null,
) : ContextSummarizer {
    override val displayIdentity get() = bound.displayIdentity
    override fun summarize(input: CompressionInput): String {
        cancellation.check()
        require(input.originals.isNotEmpty()) { "摘要原文为空" }
        val originals = freezeContextMessages(input.originals)
        val id = UUID.randomUUID().toString()
        val instruction = Message(MessageId("summary-directive-$id"), originals.first().conversationId,
            RunId("summary-request-$id"), Message.USER_ROLE,
            listOf(MessageBlock.Text("compaction-directive", HarnessCompactionPrompt.forCheckpointTarget(input.checkpointTargetTokens))),
            RunStatus.COMPLETED)
        val replay = originals + instruction
        val budget = minOf(bound.inputBudgetTokens, DaiyuContextEstimate.summaryInputLimit(
            bound.contextWindowTokens, DaiyuContextEstimate.summaryOutputLimit(bound.contextWindowTokens)))
        val inputTokens = DaiyuCompaction.request(replay, tools, systemPrompt, includeScreen = false)
        if (inputTokens > budget) return ModelContextSummarizer(bound, cancellation, nextModel,
            planningBudget = budget).summarize(input)
        val deadline = bound.startDeadline()
        var model = bound
        var reasoningIndex = 0
        while (true) {
            cancellation.check(); deadline.check()
            var session: ModelSession? = null
            var registration: AutoCloseable? = null
            try {
                val opened = model.transport.openReplay(replay, tools, systemPrompt, deadline,
                    bound.reasoningLevels[reasoningIndex], cancellation)
                session = opened
                registration = cancellation.register { opened.cancel() }
                val text = linkedMapOf<String, StringBuilder>()
                opened.next(emptyList()) { event ->
                    cancellation.check(); deadline.check()
                    when (event) {
                        is ModelEvent.TextDelta -> text.getOrPut(event.itemId) { StringBuilder() }.append(event.text)
                        is ModelEvent.TextFinal -> text[event.itemId] = StringBuilder(event.text)
                        is ModelEvent.ToolReady -> error("摘要模型请求工具；未执行，原文保留")
                        else -> Unit
                    }
                }
                cancellation.check(); deadline.check()
                return text.values.joinToString("\n").trim().also { require(it.isNotBlank()) { "摘要为空；原文保留" } }
            } catch (failure: Exception) {
                cancellation.check(); deadline.check()
                if (failure !is ModelRequestException || failure.failure != ModelFailure.REQUEST ||
                    failure.diagnostic?.reasoningLevelRejected != true || reasoningIndex != 0 ||
                    bound.reasoningLevels.size < 2 || nextModel == null) throw failure
                reasoningIndex++
            } finally {
                registration?.close()
                session?.let { runCatching { it.cancel() } }
            }
            cancellation.check(); deadline.check()
            model = checkNotNull(nextModel).invoke().also {
                require(it.binding == bound.binding && it.contextWindowTokens == bound.contextWindowTokens &&
                    it.inputBudgetTokens == bound.inputBudgetTokens && it.reasoningLevels == bound.reasoningLevels)
            }
        }
    }
}
