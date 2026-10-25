package app.daiyu.android.context

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.domain.context.ContextRevision
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Host adapter keeps legacy reference settings separate from production token-tail policy. */
fun interface CompressionPolicyResolver {
    fun resolve(settings: CompressionSettings): CompressionPolicy
}

/** Resolve exactly this saved identity to a frozen, configured, tool-free-capable transport.
 * Null means unavailable, never "use the main chat model". Resolution must not open network I/O. */
fun interface SummaryModelResolver {
    fun resolve(binding: SummaryModelBinding): BoundSummaryModel?
    /** Production must implement this overload; legacy fake resolvers may ignore an unused port. */
    fun resolve(binding: SummaryModelBinding, usage: BoundSummaryUsagePort?): BoundSummaryModel? {
        check(usage == null) { "summary_resolver_has_no_durable_usage_binding" }
        return resolve(binding)
    }
}

/** Parent-owned one-way cancellation, including active summary I/O (not merely a polling flag). */
class ContextPreparationCancellation {
    private val cancelled = AtomicBoolean(false)
    private val callbacks = linkedSetOf<() -> Unit>()
    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        val pending = synchronized(callbacks) {
            if (!cancelled.compareAndSet(false, true)) return
            callbacks.toList().also { callbacks.clear() }
        }
        pending.forEach { runCatching(it) }
    }

    internal fun register(callback: () -> Unit): AutoCloseable {
        val immediate = synchronized(callbacks) {
            if (cancelled.get()) true else { callbacks.add(callback); false }
        }
        if (immediate) runCatching(callback)
        return AutoCloseable { synchronized(callbacks) { callbacks.remove(callback) } }
    }

    internal fun check() {
        if (isCancelled || Thread.currentThread().isInterrupted)
            throw CancellationException("上下文准备已取消")
    }
}

/** Single-use snapshot. The parent retains its preparation reservation until send acceptance. */
class FrozenContextPreparation internal constructor(
    internal val issuer: Any,
    val conversationId: ConversationId,
    val settings: CompressionSettings,
    val policy: CompressionPolicy,
    pending: List<Message>,
    internal val summarizer: ContextSummarizer?,
    val cancellation: ContextPreparationCancellation,
    internal val runtimeModel: ((BoundSummaryUsagePort) -> BoundSummaryModel)? = null,
    internal val summaryIdentity: app.daiyu.android.timeline.SummaryModelIdentity? = null,
) {
    val pendingMessages: List<Message> = freezeContextMessages(pending)
    internal val consumed = AtomicBoolean(false)
}

/** messages is the COMPLETE runner input: the frozen pending input has already been appended. */
class PreparedSendContext internal constructor(
    val frozen: FrozenContextPreparation,
    history: PreparedContext,
    /** Compare against authoritative raw history under the host's send transaction/owner lock. */
    val expectedHistoryRevision: String,
) {
    val history: PreparedContext = history.copy(messages = freezeContextMessages(history.messages))
    val messages: List<Message> = freezeContextMessages(this.history.messages + frozen.pendingMessages)
    val archiveId: String? get() = history.archiveId
}

/**
 * Production preparation seam, NOT a second queue or a send transaction.
 *
 * Caller must reserve the same parent preparation/owner lock used by sends, edits, deletion,
 * branch selection and compression BEFORE freeze; perform model I/O off the UI thread without
 * holding a JVM monitor. Keep that reservation until commit/rejection and cancel it on close,
 * stop or owner invalidation. Recheck token, owner, draft and expectedHistoryRevision atomically
 * before the existing database.send; only then start the runner with result.messages (no +user).
 * ContextService publishes archives in its own transaction: no claim of atomicity with send.
 */
class ContextPreparationCoordinator(
    private val service: ContextService,
    private val settingsSnapshot: () -> CompressionSettings,
    private val policies: CompressionPolicyResolver,
    private val models: SummaryModelResolver,
    private val chatModel: ((ProviderSnapshot, Long, BoundSummaryUsagePort?) -> BoundSummaryModel)? = null,
) {
    constructor(service: ContextService, settings: CompressionSettingsStore,
        policies: CompressionPolicyResolver, models: SummaryModelResolver) :
        this(service, settings::snapshot, policies, models)

    fun freeze(conversationId: ConversationId, pendingMessages: List<Message>,
        cancellation: ContextPreparationCancellation = ContextPreparationCancellation(),
        summaryUsage: BoundSummaryUsagePort? = null, contextWindowOverride: Int? = null,
        chatProvider: ProviderSnapshot? = null): FrozenContextPreparation {
        cancellation.check()
        val pending = freezeContextMessages(pendingMessages)
        require(pending.all { it.conversationId == conversationId }) { "待发送消息不属于当前会话" }
        require(pending.map { it.id }.distinct().size == pending.size) { "待发送消息重复" }
        require(contextWindowOverride == null || contextWindowOverride > 0) { "上下文窗口必须为正整数" }
        val savedSettings = settingsSnapshot()
        val settings = if (contextWindowOverride == null) savedSettings else
            savedSettings.copy(contextWindowTokens = contextWindowOverride.toLong())
        val policy = policies.resolve(settings)
        // A resolver cannot enable unknown capacity or substitute the main model's window.
        require(policy.enabled == settings.automaticWithKnownWindow)
        require(policy.contextTokens == settings.contextWindowTokens && policy.reserveTokens == settings.reserveTokens)
        val binding = settings.summaryModel
        val bound = when {
            !policy.enabled -> null
            binding != null -> models.resolve(binding, summaryUsage)
            else -> checkNotNull(chatModel) { "当前聊天摘要解析器不可用" }.invoke(
                checkNotNull(chatProvider) { "需要本次冻结的聊天模型" },
                checkNotNull(settings.contextWindowTokens), summaryUsage)
        }
        require(binding == null || bound == null || bound.binding == binding) { "摘要模型绑定已变化；未回退主模型" }
        cancellation.check()
        return FrozenContextPreparation(this, conversationId, settings, policy, pending,
            bound?.let { ModelContextSummarizer(it, cancellation) }, cancellation, bound?.rebind, bound?.displayIdentity)
    }

    fun prepare(frozen: FrozenContextPreparation): PreparedSendContext {
        require(frozen.issuer === this) { "准备快照不属于当前协调器" }
        check(frozen.consumed.compareAndSet(false, true)) { "准备快照只能消费一次；不得自动重发" }
        frozen.cancellation.check()
        // The parent reservation must cover both this read and the later send CAS.
        val revision = ContextRevision.digest(service.displayHistory(frozen.conversationId))
        val history = service.prepareSend(frozen.conversationId, frozen.policy, frozen.summarizer,
            pendingMessages = frozen.pendingMessages, cancelled = { frozen.cancellation.isCancelled })
        // Do not add a fallible post-publication phase: cancellation/commit fencing belongs to
        // the parent. Service has already checked cancellation in its publication transaction.
        return PreparedSendContext(frozen, history, revision)
    }
}

/** Defensive collection copies only; does not expand, estimate, prune or choose archive cuts. */
internal fun freezeContextMessages(messages: List<Message>): List<Message> = immutable(messages.map { message ->
    message.copy(blocks = immutable(message.blocks), protocolItems = immutable(message.protocolItems),
        historySegments = immutable(message.historySegments.map { segment ->
            segment.copy(blocks = immutable(segment.blocks), protocolItems = immutable(segment.protocolItems))
        }))
})

private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
