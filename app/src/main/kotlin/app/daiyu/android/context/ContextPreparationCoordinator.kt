package app.daiyu.android.context

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.domain.context.CompressionPolicy
import app.daiyu.android.diagnostics.RuntimeDiagnostics
import app.daiyu.android.diagnostics.RuntimeDiagnostics.Stage
import app.daiyu.android.diagnostics.RuntimeDiagnostics.Code
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
    /** 需要绑定持久化用量或指定协议的解析器须重写此重载；默认实现只接受两者均为空的调用。 */
    fun resolve(binding: SummaryModelBinding, usage: BoundSummaryUsagePort?,
        protocol: app.daiyu.android.model.ProviderProtocol? = null): BoundSummaryModel? {
        check(protocol == null) { "summary_resolver_has_no_protocol_preference" }
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
        RuntimeDiagnostics.event(Stage.SUMMARY_REQUEST, Code.CANCEL_REQUESTED)
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
 * 冻结待发送输入和压缩配置，准备发送给 Runner 的完整上下文。
 * 调用方须在 freeze 前取得发送、编辑、删除、分支选择和压缩共用的准备预约，保留到提交或拒绝。
 * 模型 I/O 在工作线程执行，期间不得持有 JVM monitor；关闭、停止或所有者失效时取消预约。
 * database.send 前原子复查令牌、所有者、草稿和 expectedHistoryRevision；随后用 result.messages
 * 启动 Runner，其中已包含待发送用户输入，无须再次追加。
 * ContextService 的档案发布使用独立事务，可能先于发送提交。
 */
class ContextPreparationCoordinator(
    private val service: ContextService,
    private val settingsSnapshot: () -> CompressionSettings,
    private val policies: CompressionPolicyResolver,
    private val models: SummaryModelResolver,
    private val chatModel: ((ProviderSnapshot, Long, BoundSummaryUsagePort?, app.daiyu.android.model.ProviderProtocol?) -> BoundSummaryModel)? = null,
) {
    constructor(service: ContextService, settings: CompressionSettingsStore,
        policies: CompressionPolicyResolver, models: SummaryModelResolver) :
        this(service, settings::snapshot, policies, models)

    fun freeze(conversationId: ConversationId, pendingMessages: List<Message>,
        cancellation: ContextPreparationCancellation = ContextPreparationCancellation(),
        summaryUsage: BoundSummaryUsagePort? = null, contextWindowOverride: Int? = null,
        chatProvider: ProviderSnapshot? = null,
        nextSummaryUsage: (() -> BoundSummaryUsagePort)? = null): FrozenContextPreparation {
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
            binding != null -> models.resolve(binding, summaryUsage, settings.summaryProtocol)
            else -> checkNotNull(chatModel) { "当前聊天摘要解析器不可用" }.invoke(
                checkNotNull(chatProvider) { "需要本次冻结的聊天模型" },
                checkNotNull(settings.contextWindowTokens), summaryUsage, settings.summaryProtocol)
        }
        require(binding == null || bound == null || bound.binding == binding) { "摘要模型绑定已变化；未回退主模型" }
        cancellation.check()
        return FrozenContextPreparation(this, conversationId, settings, policy, pending,
            bound?.let { first -> ModelContextSummarizer(first, cancellation, nextModel = nextSummaryUsage?.let { next ->
                { checkNotNull(first.rebind) { "摘要模型不支持分块用量绑定" }.invoke(next()) }
            }) }, cancellation, bound?.rebind, bound?.displayIdentity)
    }

    fun prepare(frozen: FrozenContextPreparation): PreparedSendContext =
        RuntimeDiagnostics.measure(Stage.CONTEXT_PREPARE) { prepareInternal(frozen) }

    private fun prepareInternal(frozen: FrozenContextPreparation): PreparedSendContext {
        require(frozen.issuer === this) { "准备快照不属于当前协调器" }
        check(frozen.consumed.compareAndSet(false, true)) { "准备快照只能消费一次；不得自动重发" }
        frozen.cancellation.check()
        // The parent reservation must cover both this read and the later send CAS.
        val revision = RuntimeDiagnostics.measure(Stage.CONTEXT_REVISION) {
            ContextRevision.digest(service.displayHistory(frozen.conversationId))
        }
        val history = service.prepareSend(frozen.conversationId, frozen.policy, frozen.summarizer,
            pendingMessages = frozen.pendingMessages, cancelled = { frozen.cancellation.isCancelled })
        // Do not add a fallible post-publication phase: cancellation/commit fencing belongs to
        // the parent. Service has already checked cancellation in its publication transaction.
        return PreparedSendContext(frozen, history, revision)
    }
}

/** 复制消息及历史片段中的列表并设为不可修改，避免后续修改影响已冻结的上下文。 */
internal fun freezeContextMessages(messages: List<Message>): List<Message> = immutable(messages.map { message ->
    message.copy(blocks = immutable(message.blocks), protocolItems = immutable(message.protocolItems),
        historySegments = immutable(message.historySegments.map { segment ->
            segment.copy(blocks = immutable(segment.blocks), protocolItems = immutable(segment.protocolItems))
        }))
})

private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
