package app.daiyu.android.context

import app.daiyu.android.domain.*
import java.util.UUID

/** One replayed prefix and one final user directive; no shard rewriting or format-repair call. */
internal class HarnessContextSummarizer(
    private val bound: BoundSummaryModel,
    private val cancellation: ContextPreparationCancellation,
    private val tools: List<ToolDefinition> = emptyList(),
    private val systemPrompt: String = "",
) : ContextSummarizer {
    override val displayIdentity get() = bound.displayIdentity
    override fun summarize(input: CompressionInput): String {
        cancellation.check()
        require(input.originals.isNotEmpty()) { "摘要原文为空" }
        val originals = freezeContextMessages(input.originals)
        val deadline = bound.startDeadline()
        val id = UUID.randomUUID().toString()
        val instruction = Message(MessageId("summary-directive-$id"), originals.first().conversationId,
            RunId("summary-request-$id"), Message.USER_ROLE,
            listOf(MessageBlock.Text("compaction-directive", HarnessCompactionPrompt.instruction)), RunStatus.COMPLETED)
        val replay = originals + instruction
        val inputTokens = app.daiyu.android.domain.context.HarnessTokenEstimate.system(systemPrompt) +
            TokenTailCompression.historyTokens(replay, includeLatestScreen = false) + TokenTailCompression.toolTokens(tools)
        require(inputTokens <= bound.inputBudgetTokens) { "摘要模型输入超过其配置预算；保留原文" }
        val session = bound.transport.openReplay(replay, tools, systemPrompt, deadline, cancellation)
        val registration = cancellation.register { session.cancel() }
        try {
            val text = linkedMapOf<String, StringBuilder>()
            session.next(emptyList()) { event ->
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
        } finally { registration.close(); runCatching { session.cancel() } }
    }
}
