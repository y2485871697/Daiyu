package app.daiyu.android.context

import app.daiyu.android.diagnostics.RuntimeDiagnostics
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.domain.context.CodexContextPolicy
import app.daiyu.android.domain.context.CodexTokenEstimate
import app.daiyu.android.domain.runtime.*
import java.util.UUID

internal data class RuntimeCompressionConfiguration(
    val automatic: Boolean,
    val resolve: (BoundSummaryUsagePort) -> BoundSummaryModel,
)

/** A narrow live-run projection. Original rows and the tool journal are never replaced. */
internal class ProductionRunContext(
    private val policy: app.daiyu.android.domain.context.CompressionPolicy,
    private val parent: Message,
    private val resolve: ((BoundSummaryUsagePort) -> BoundSummaryModel)?,
    override val automatic: Boolean,
    private val agentLabel: String = "主代理",
    private val taskId: String? = null,
    override val canCompact: Boolean = true,
    override val systemPrompt: String = "",
    private val requestHeader: String = "",
    private val manualRetention: Boolean = false,
    override val initialMeter: ContextMeterCheckpoint? = null,
    private val runtimeSnapshot: (() -> RuntimeCompressionConfiguration)? = null,
    private val progress: (app.daiyu.android.timeline.CompressionActivity) -> Unit = {},
) : RunContextRuntime {
    constructor(frozen: FrozenContextPreparation, parent: Message,
        systemPrompt: String = "", requestHeader: String = "", initialMeter: ContextMeterCheckpoint? = null,
        progress: (app.daiyu.android.timeline.CompressionActivity) -> Unit = {}) :
        this(frozen.policy, parent, frozen.runtimeModel, frozen.policy.enabled && frozen.runtimeModel != null,
            systemPrompt = systemPrompt, requestHeader = requestHeader, initialMeter = initialMeter,
            runtimeSnapshot = frozen.runtimeSnapshot, progress = progress)
    override fun compressionSnapshot(): RunContextRuntime {
        val current = runtimeSnapshot?.invoke() ?: return this
        return ProductionRunContext(policy, parent, current.resolve, current.automatic,
            agentLabel = agentLabel, taskId = taskId, canCompact = canCompact, systemPrompt = systemPrompt,
            requestHeader = requestHeader, manualRetention = manualRetention, initialMeter = initialMeter, progress = progress)
            .also { it.definitions = definitions.toList() }
    }
    override val windowTokens = checkNotNull(policy.contextTokens)
    override val sendBudgetTokens = checkNotNull(policy.sendBudgetTokens).coerceAtLeast(0)
    override val pressureThresholdTokens get() = CodexContextPolicy.autoCompactLimit(windowTokens, policy.triggerPercent)
    override fun automaticCompactionDecision(input: Long, threshold: Long, attempted: Boolean) {
        RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.CONTEXT_PRESSURE,
            if (attempted) RuntimeDiagnostics.Code.BEGIN else RuntimeDiagnostics.Code.THROTTLED,
            size = threshold, count = input)
    }
    private var definitions = emptyList<ToolDefinition>()
    override fun headerIdentity(tools: List<ToolDefinition>): String {
        definitions = tools.toList()
        val value = requestHeader + "\n" + tools.joinToString("\n") { "${it.name}\n${it.description}\n${it.parameters}" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override fun fixedSurfaceTokens(history: List<Message>): Long =
        CodexTokenEstimate.text(systemPrompt) +
            TokenTailCompression.codexHistoryTokens(MessageHistory.expand(history))
    @Volatile private var cancellation: ContextPreparationCancellation? = null
    @Volatile private var lastActivity: app.daiyu.android.timeline.CompressionActivity? = null
    private var boundary: app.daiyu.android.timeline.TimelineAnchor? = null
    override fun beginCompaction(boundary: Message) {
        val logical = MessageHistory.expand(listOf(boundary))
        this.boundary = app.daiyu.android.timeline.TimelineAnchor.at(logical, logical.lastIndex)
    }
    override fun compactionApplied() {
        lastActivity = lastActivity?.copy(succeeded = true)
    }
    override fun finishedCompaction() {
        lastActivity?.takeIf { it.failure == null }?.let { progress(it.copy(running = false)) }
        lastActivity = null
    }
    override fun cancel() { cancellation?.cancel() }
    override fun localTokens(history: List<Message>, tools: List<ToolDefinition>): Long {
        definitions = tools.toList()
        return fixedSurfaceTokens(history) + TokenTailCompression.toolTokens(tools, codex = true)
    }

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
        val failed = lastActivity?.copy(running = false, failure = reason, succeeded = false) ?: app.daiyu.android.timeline.CompressionActivity(UUID.randomUUID().toString(),
            parent.conversationId.value, 0, null, running = false, agentLabel = agentLabel,
            runId = parent.runId.value, taskId = taskId, failure = reason, anchor = boundary)
        lastActivity = failed
        progress(failed)
    }

    override fun compactRequests(history: List<Message>, billedTokens: Long?, localTokens: Long,
        requests: RunSummaryRequests, cancelled: () -> Boolean): List<Message>? {
        return compactRegion(history, billedTokens, localTokens, requests, cancelled, overflow = false)
    }

    override fun compactOverflowRequests(history: List<Message>, billedTokens: Long?, localTokens: Long,
        requests: RunSummaryRequests, cancelled: () -> Boolean): List<Message>? =
        compactRegion(history, billedTokens, localTokens, requests, cancelled, overflow = true)

    private fun compactRegion(history: List<Message>, billedTokens: Long?, localTokens: Long,
        requests: RunSummaryRequests, cancelled: () -> Boolean, overflow: Boolean): List<Message>? {
        val logical = MessageHistory.expand(history)
        val cut = TokenTailCompression.selectStart(logical, windowTokens, billedTokens, localTokens,
            overflow = overflow || manualRetention, reservedCompletionTokens = policy.outputReservationTokens, codex = true)
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
            val systemCount = logical.takeWhile { it.role == "system" }.size
            val prefix = logical.subList(systemCount, cut)
            val activity = app.daiyu.android.timeline.CompressionActivity(UUID.randomUUID().toString(),
                parent.conversationId.value, prefix.size, model.displayIdentity, agentLabel = agentLabel, runId = parent.runId.value, taskId = taskId,
                anchor = boundary ?: app.daiyu.android.timeline.TimelineAnchor.at(logical, logical.lastIndex))
            lastActivity = activity
            progress(activity)
            val prefixTokens = TokenTailCompression.codexHistoryTokens(prefix, includeLatestScreen = false)
            val text = HarnessContextSummarizer(model, cancellation, definitions, systemPrompt,
                checkpointTargetTokens = compactionCheckpointTarget(prefixTokens),
                nextModel = model.rebind?.let { rebind -> { rebind(usage()) } }).summarize(
                CompressionInput(logical.take(cut), ContextRevision.digest(prefix))).trim()
            if (cancelled()) return null
            require(text.isNotEmpty()) { "摘要为空；保留原文" }
            val id = "summary-${activity.id}"
            val summary = Message(MessageId(id), parent.conversationId, RunId(id), Message.USER_ROLE,
                listOf(MessageBlock.Text("summary", SummaryCheckpoint.frame(text))), RunStatus.COMPLETED)
            val result = logical.take(systemCount) + summary + logical.drop(cut)
            val candidateTokens = this.localTokens(result, emptyList())
            val originalTokens = this.localTokens(history, emptyList())
            // A negligible shrink does not create a successful checkpoint or invalidate cloud usage.
            val requiredSaving = maxOf(1L, prefixTokens / 10)
            val reduced = originalTokens - candidateTokens >= requiredSaving
            RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.SUMMARY_REDUCTION,
                if (reduced) RuntimeDiagnostics.Code.SUMMARY_REDUCED else RuntimeDiagnostics.Code.SUMMARY_NOT_REDUCED,
                count = originalTokens, size = candidateTokens)
            require(reduced) { "压缩未缩小；保留原文" }
            return result
        } finally { this.cancellation = null }
    }
}
