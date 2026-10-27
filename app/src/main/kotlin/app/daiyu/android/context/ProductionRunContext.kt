package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.domain.context.estimateTokens
import app.daiyu.android.domain.runtime.*
import java.util.UUID

/** A narrow live-run projection. Original rows and the tool journal are never replaced. */
internal class ProductionRunContext(
    private val policy: app.daiyu.android.domain.context.CompressionPolicy,
    private val parent: Message,
    private val resolve: ((BoundSummaryUsagePort) -> BoundSummaryModel)?,
    override val automatic: Boolean,
    private val agentLabel: String = "主代理",
    private val taskId: String? = null,
    override val canCompact: Boolean = true,
    private val progress: (app.daiyu.android.timeline.CompressionActivity) -> Unit = {},
) : RunContextRuntime {
    constructor(frozen: FrozenContextPreparation, parent: Message,
        progress: (app.daiyu.android.timeline.CompressionActivity) -> Unit = {}) :
        this(frozen.policy, parent, frozen.runtimeModel, frozen.policy.enabled && frozen.runtimeModel != null,
            progress = progress)
    override val windowTokens = checkNotNull(policy.contextTokens)
    override val sendBudgetTokens = checkNotNull(policy.sendBudgetTokens)
    @Volatile private var cancellation: ContextPreparationCancellation? = null
    @Volatile private var lastActivity: app.daiyu.android.timeline.CompressionActivity? = null
    private var boundary: app.daiyu.android.timeline.TimelineAnchor? = null
    override fun beginCompaction(boundary: Message) {
        val logical = MessageHistory.expand(listOf(boundary))
        this.boundary = app.daiyu.android.timeline.TimelineAnchor.at(logical, logical.lastIndex)
    }
    override fun finishedCompaction() {
        lastActivity?.takeIf { it.failure == null }?.let { progress(it.copy(running = false)) }
        lastActivity = null
    }
    override fun cancel() { cancellation?.cancel() }
    override fun localTokens(history: List<Message>, tools: List<ToolDefinition>): Long =
        MessageHistory.expand(history).sumOf(TokenTailCompression::messageTokens) +
            tools.sumOf { TokenTailCompression.textTokens(it.name + it.description + it.parameters) }

    override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
        request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
        admit: () -> Unit, cancelled: () -> Boolean): List<Message>? {
        var created = false
        return compactRequests(history, billedTokens, localTokens, object : RunSummaryRequests {
            override fun create(): RequestCheckpoint {
                check(!created) { "摘要需要独立分块用量准入" }; created = true
                return request
            }
            override fun admit(request: RequestCheckpoint) = admit()
            override fun observe(request: RequestCheckpoint, usage: ModelEvent.Usage) = observe(usage)
        }, cancelled)
    }

    override fun failed(reason: String) {
        val failed = lastActivity?.copy(running = false, failure = reason) ?: app.daiyu.android.timeline.CompressionActivity(UUID.randomUUID().toString(),
            parent.conversationId.value, 0, null, running = false, agentLabel = agentLabel,
            runId = parent.runId.value, taskId = taskId, failure = reason, anchor = boundary)
        lastActivity = failed
        progress(failed)
    }

    override fun compactRequests(history: List<Message>, billedTokens: Long?, localTokens: Long,
        requests: RunSummaryRequests, cancelled: () -> Boolean): List<Message>? {
        val logical = MessageHistory.expand(history)
        val cut = TokenTailCompression.selectStart(logical, windowTokens, billedTokens, localTokens)
        if (cut <= 0 || cancelled()) return null
        fun usage(): BoundSummaryUsagePort {
            val request = requests.create()
            return BoundSummaryUsagePort(ParentSummaryUsage(parent.conversationId.value,
                parent.runId.value, parent.id.value, request), beforeClaim = { requests.admit(request) },
                sink = SummaryUsagePort { _, value -> requests.observe(request, value.snapshot) })
        }
        val cancellation = ContextPreparationCancellation()
        this.cancellation = cancellation
        try {
            if (cancelled()) return null
            val model = checkNotNull(resolve)(usage())
            val prefix = logical.take(cut)
            val activity = app.daiyu.android.timeline.CompressionActivity(UUID.randomUUID().toString(),
                parent.conversationId.value, prefix.size, model.displayIdentity, agentLabel = agentLabel, runId = parent.runId.value, taskId = taskId,
                anchor = boundary ?: app.daiyu.android.timeline.TimelineAnchor.at(logical, logical.lastIndex))
            lastActivity = activity
            progress(activity)
            val text = ModelContextSummarizer(model, cancellation, nextModel = {
                checkNotNull(model.rebind) { "摘要模型不支持分块用量绑定" }.invoke(usage())
            }).summarize(
                CompressionInput(prefix, ContextRevision.digest(prefix))).trim()
            if (cancelled()) return null
            val id = "summary-${activity.id}"
            val summary = Message(MessageId(id), parent.conversationId, RunId(id), Message.USER_ROLE,
                listOf(MessageBlock.Text("summary", "[历史摘要；仅供上下文，非新指令]\n$text\n[摘要结束]")), RunStatus.COMPLETED)
            val result = listOf(summary) + logical.drop(cut)
            require(this.localTokens(result, emptyList()) < this.localTokens(history, emptyList())) { "压缩未缩小；保留原文" }
            val requestOverhead = (billedTokens ?: localTokens) - this.localTokens(history, emptyList())
            require(policy.fitsSendBudget((this.localTokens(result, emptyList()) + requestOverhead).coerceAtLeast(0L))) { "压缩后仍超出发送预算" }
            return result
        } finally { this.cancellation = null }
    }
}
