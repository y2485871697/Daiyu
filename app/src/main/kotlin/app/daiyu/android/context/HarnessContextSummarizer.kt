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
    private val archiveToolResult: ((Message, ContextPreparationCancellation) -> String)? = null,
) : ContextSummarizer {
    override val displayIdentity get() = bound.displayIdentity
    override fun summarize(input: CompressionInput): String {
        cancellation.check()
        require(input.originals.isNotEmpty()) { "摘要原文为空" }
        val deadline = bound.startDeadline()
        val originals = freezeContextMessages(input.originals)
        val evidence = DaiyuSummaryEvidence.prepare(originals, cancellation, archiveToolResult)
        val sourceEvidence = if (bound.requireCheckpointFormat) DaiyuCheckpointEvidence.collect(originals, cancellation) else null
        val evidencePrompt = sourceEvidence?.prompt().orEmpty()
        fun finish(summary: String) = if (sourceEvidence == null) summary else
            SummaryCheckpoint.withArchive(sourceEvidence.finish(summary), input.archiveId)
        val directive = if (bound.requireCheckpointFormat) listOf(SummaryCheckpoint.instruction, evidencePrompt)
            .filter(String::isNotEmpty).joinToString("\n") else HarnessCompactionPrompt.forCheckpointTarget(input.checkpointTargetTokens)
        val id = UUID.randomUUID().toString()
        val instruction = Message(MessageId("summary-directive-$id"), originals.first().conversationId,
            RunId("summary-request-$id"), Message.USER_ROLE,
            listOf(MessageBlock.Text("compaction-directive", directive)),
            RunStatus.COMPLETED)
        val replay = evidence.messages + instruction
        val budget = minOf(bound.inputBudgetTokens, DaiyuContextEstimate.summaryInputLimit(
            bound.contextWindowTokens, DaiyuContextEstimate.summaryOutputLimit(bound.contextWindowTokens)))
        val inputTokens = DaiyuCompaction.request(replay, tools, systemPrompt, includeScreen = false)
        if (inputTokens > budget || !evidence.replayable || !DaiyuSummaryEvidence.canReplay(replay, bound.outputPolicy?.protocol)) return ModelContextSummarizer(bound, cancellation, nextModel,
            planningBudget = budget).summarizePrepared(input.copy(originals = evidence.messages), deadline, sourceEvidence)
        val requests = DaiyuSummaryRequest(bound, cancellation, deadline, nextModel)
        val candidate = requests.complete(replay, inputTokens, tools, systemPrompt)
        if (!bound.requireCheckpointFormat) return candidate
        SummaryCheckpoint.validate(candidate)?.let { return finish(it) }
        val repair = instruction.copy(blocks = listOf(MessageBlock.Text("compaction-repair",
            SummaryCheckpoint.repairPrompt(candidate))))
        val repairTokens = DaiyuCompaction.request(listOf(repair), emptyList(), "", includeScreen = false)
        require(repairTokens <= budget) { "摘要格式修复输入超限；保留原文" }
        return finish(requireNotNull(SummaryCheckpoint.validate(requests.complete(listOf(repair), repairTokens))) {
            "摘要八节格式不完整；保留原文"
        })
    }
}
