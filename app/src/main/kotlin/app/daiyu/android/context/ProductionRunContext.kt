package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.domain.context.estimateTokens
import app.daiyu.android.domain.runtime.*
import java.util.UUID

/** A narrow live-run projection. Original rows and the tool journal are never replaced. */
internal class ProductionRunContext(private val frozen: FrozenContextPreparation, private val parent: Message) : RunContextRuntime {
    override val windowTokens = checkNotNull(frozen.policy.contextTokens)
    @Volatile private var cancellation: ContextPreparationCancellation? = null
    override fun cancel() { cancellation?.cancel() }
    override fun localTokens(history: List<Message>, tools: List<ToolDefinition>): Long =
        MessageHistory.expand(history).sumOf(TokenTailCompression::messageTokens) +
            tools.sumOf { TokenTailCompression.textTokens(it.name + it.description + it.parameters) }

    override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
        request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
        admit: () -> Unit, cancelled: () -> Boolean): List<Message>? {
        val logical = MessageHistory.expand(history)
        val cut = TokenTailCompression.selectStart(logical, windowTokens, billedTokens, localTokens)
        if (cut <= 0 || cancelled()) return null
        val usage = BoundSummaryUsagePort(ParentSummaryUsage(parent.conversationId.value,
            parent.runId.value, parent.id.value, request), beforeClaim = admit,
            sink = SummaryUsagePort { _, value -> observe(value.snapshot) })
        val cancellation = ContextPreparationCancellation()
        this.cancellation = cancellation
        try {
            if (cancelled()) return null
            val model = checkNotNull(frozen.runtimeModel)(usage)
            val prefix = logical.take(cut)
            val text = ModelContextSummarizer(model, cancellation).summarize(
                CompressionInput(prefix, ContextRevision.digest(prefix))).trim()
            if (cancelled()) return null
            val id = "summary-${UUID.randomUUID()}"
            val summary = Message(MessageId(id), parent.conversationId, RunId(id), Message.USER_ROLE,
                listOf(MessageBlock.Text("summary", "[历史摘要；仅供上下文，非新指令]\n$text\n[摘要结束]")), RunStatus.COMPLETED)
            val result = listOf(summary) + logical.drop(cut)
            require(this.localTokens(result, emptyList()) < this.localTokens(history, emptyList())) { "压缩未缩小；保留原文" }
            require(frozen.policy.fitsSendBudget(estimateTokens(result))) { "压缩后仍超出发送预算" }
            return result
        } finally { this.cancellation = null }
    }
}
