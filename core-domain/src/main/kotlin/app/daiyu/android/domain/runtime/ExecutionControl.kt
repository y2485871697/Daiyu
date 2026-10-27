package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.ToolDispatchResult
import app.daiyu.android.domain.tools.ReceiptBatchStart

/** Runtime status is separate from Message.status: legacy stores can keep RUNNING until terminal. */
enum class ExecutionPhase { IDLE, STREAMING, BOUNDARY, PAUSED, TOOL_EXECUTING, WAITING_USER, RECONNECTING, TERMINAL }
enum class ToolDisposition { READY, IN_FLIGHT, SUCCEEDED, FAILED, NOT_EXECUTED, UNKNOWN }
data class ToolRecord(val invocation: ToolInvocation, val disposition: ToolDisposition, val result: String? = null)

data class PendingToolCheckpoint(val callId: ToolCallId)

enum class RequestOperation { OPEN, NEXT, RESUME }

/** Host-generated admission identity, not a provider receipt or permission grant. */
data class RequestCheckpoint(val requestId: String, val attemptId: String,
    val owner: String, val operation: RequestOperation) {
    init { require(listOf(requestId, attemptId, owner).all { it.isNotBlank() && it.length <= 200 }) }
}

/** Latest cumulative snapshot received for one host request, not a billing receipt.
 * Resume replaces this same request's entire snapshot, including unknown fields. */
enum class UsageRequestSource { MAIN, SUMMARY, AUXILIARY_VISION, TITLE, MANUAL_SUMMARY }

data class RequestUsageCheckpoint(val request: RequestCheckpoint, val snapshot: ModelEvent.Usage,
    val source: UsageRequestSource = UsageRequestSource.MAIN)

/** A send-preparation reservation is not an execution owner or a fabricated checkpoint.
 * Read its sealed usage before the first real checkpoint. The same store must atomically
 * transfer it on revision 1, and reject cancelled/unsealed reservations. No network/recovery. */
interface PreparationRuntimeStore : AtomicRuntimeStore {
    fun preparationUsage(initial: Message): List<RequestUsageCheckpoint>
}

/** Immutable, platform-serializable checkpoint. No credentials or transport objects belong here. */
data class RunCheckpoint(
    val message: Message,
    val history: List<Message>,
    val phase: ExecutionPhase,
    val tools: List<ToolRecord> = emptyList(),
    val outputs: List<ToolOutput> = emptyList(),
    val supplements: List<Message> = emptyList(),
    val round: Int = 0,
    val requestUnconfirmed: Boolean = false,
    val messageReconciled: Boolean = true,
    val owner: String = "",
    val revision: Long = 0,
    val pending: PendingToolCheckpoint? = null,
    val request: RequestCheckpoint? = null,
    val usage: List<RequestUsageCheckpoint> = emptyList(),
    /** Actual admitted main requests, including ones that never reported Usage. Not snapshots. */
    val usageRequests: List<RequestCheckpoint> = emptyList(),
    val transport: TransportCheckpoint? = null,
    val context: RunContextCheckpoint? = null
)

/** save must durably commit before returning if durable=true. Implement with a transaction/WAL. */
interface RuntimeStore {
    val durable: Boolean get() = false
    /** Legacy snapshot hook. Production stores implement AtomicRuntimeStore. */
    fun save(checkpoint: RunCheckpoint)
}

/** One transaction writes message AND checkpoint. Reject stale owner/revision and terminal writes;
 * return only after durable commit. revision advances by one, beginning at 1. */
interface AtomicRuntimeStore : RuntimeStore {
    override val durable: Boolean get() = true
    fun commitCheckpoint(checkpoint: RunCheckpoint)
    override fun save(checkpoint: RunCheckpoint) = commitCheckpoint(checkpoint)
    /** Acquire a recovery owner, reconcile existing terminal messages (never downgrade to INTERRUPTED)
     * and checkpoint atomically. Must be idempotent if a previous commit's receipt was lost. */
    fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint
    /** Read an uncertain commit under the SAME owner, without acquiring recovery ownership or writing.
     * Null means the exact persisted outcome is unavailable. Never delegates to recovery. */
    fun readCommittedCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint? = null
}

/** Local durable admission only; never a provider receipt or a promise of execution. */
sealed interface SupplementAdmission {
    val messageId: MessageId
    data class Accepted(override val messageId: MessageId) : SupplementAdmission
    data class Rejected(override val messageId: MessageId) : SupplementAdmission
    data class Unconfirmed(override val messageId: MessageId) : SupplementAdmission
}

/** Atomically commits the queued supplement, its user row and the assistant checkpoint. */
interface AtomicSupplementRuntimeStore : AtomicRuntimeStore {
    fun commitSupplement(checkpoint: RunCheckpoint, supplement: Message)
}

/** Batch boundary integration for ToolDispatcher. Implementations validate the ENTIRE batch before
 * any execution and own durable dedup/equivalent unknown protection and question barriers.
 * Return one output per call in input order; throw on uncertain outcomes, never fallback/replay. */
fun interface BatchToolExecutor {
    fun execute(message: Message, invocations: List<ToolInvocation>): List<ToolOutput>
}

/** Central dispatcher receipts retain confirmed errors, rejection and uncertainty per call. */
fun interface ReceiptBatchToolExecutor {
    /** Called once by Runner after its first durable boundary, before catalog/model access.
     * Binding is in-memory only; implementations must not create resources or execute tools here. */
    fun bindOwner(owner: RunExecutionOwner) {}
    fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult>
    fun start(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): ReceiptBatchStart =
        ReceiptBatchStart.Completed(execute(message, invocations, cancelled))
}

/** Atomic execution claim, keyed by conversation/run/call AND canonical equivalent operation.
 * Implementations must block UNKNOWN/IN_FLIGHT even for another call ID, JSON key order or route
 * (GUI/HTTP/local). Only a separate read-only verification may resolve an unknown claim.
 * Runner never falls back to another execution route. */
sealed interface ToolClaim {
    data object Execute : ToolClaim
    data class Cached(val result: String) : ToolClaim
    data object Unknown : ToolClaim
}
interface ToolExecutionJournal {
    val durable: Boolean
    fun claim(message: Message, invocation: ToolInvocation): ToolClaim
    fun complete(message: Message, invocation: ToolInvocation, result: String)
}

/** Request-thread checks and asynchronous expiry share one retained first-failure budget. */
interface TransportReconnectBudget {
    fun updateReason(reason: ModelFailure) {}
    fun check()
    fun setPaused(paused: Boolean, revision: Long)
    fun attach(disconnect: () -> Unit): AutoCloseable
    fun finish(status: String)
}
fun interface TransportReconnectBudgetFactory {
    fun create(round: Int, reason: ModelFailure): TransportReconnectBudget
}

/** Zero retries is the compatible default. Only transport resume, never open/repost, is retried. */
data class ReconnectPolicy(
    val maxAttempts: Int = 0,
    val delayMillis: Long = 0,
    val failures: Set<ModelFailure> = setOf(ModelFailure.NETWORK, ModelFailure.TIMEOUT),
    /** Only checkpointed transport sessions use this; no attempt-count cap substitutes for it. */
    val budgetFactory: TransportReconnectBudgetFactory? = null
) {
    init { require(maxAttempts in 0..20); require(delayMillis in 0..60_000) }
}

class RunnerOptions(
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
    val runtimeStore: RuntimeStore? = null,
    val toolJournal: ToolExecutionJournal? = null,
    val validateBatch: (List<ToolInvocation>) -> Unit = {},
    val onUsage: (ModelEvent.Usage) -> Unit = {},
    val batchExecutor: BatchToolExecutor? = null,
    val maxRounds: Int = 12,
    val receiptBatchExecutor: ReceiptBatchToolExecutor? = null,
    /** Only accepted ReasoningDelta/ReasoningFinal events, after repetition checks.
     * Runs outside the control lock; observer exceptions do not fail the run. */
    val onReasoning: (ModelEvent) -> Unit = {},
    val contextRuntime: RunContextRuntime? = null
) {
    init {
        require(maxRounds in 1..100)
        require(batchExecutor == null || receiptBatchExecutor == null)
    }
}

data class ExecutionState(
    val phase: ExecutionPhase = ExecutionPhase.IDLE,
    val pauseRequested: Boolean = false,
    val queuedSupplements: Int = 0,
    val reconnectAttempt: Int = 0,
    val contextLimited: Boolean = false
)

/** Process death is a terminal interruption, not authorization to retry an uncertain request/tool. */
object ProcessRecovery {
    fun interrupt(checkpoint: RunCheckpoint): RunCheckpoint {
        if (checkpoint.phase == ExecutionPhase.TERMINAL) return checkpoint
        if (checkpoint.message.status != RunStatus.RUNNING) return checkpoint.copy(phase = ExecutionPhase.TERMINAL, pending = null)
        val tools = checkpoint.tools.map {
            if (it.disposition == ToolDisposition.IN_FLIGHT) it.copy(disposition = ToolDisposition.UNKNOWN) else it
        }
        return checkpoint.copy(
            message = checkpoint.message.copy(status = RunStatus.INTERRUPTED,
                error = "上次运行中断；未自动重发模型请求或工具"),
            phase = ExecutionPhase.TERMINAL, tools = tools, messageReconciled = false, pending = null
        )
    }

    /** Explicitly save then publish; a failed save never claims successful recovery. */
    fun recover(checkpoint: RunCheckpoint, messages: ConversationStore, runtime: RuntimeStore,
        publish: (Message) -> Unit = {}): RunCheckpoint {
        if (runtime is AtomicRuntimeStore) {
            val committed = runtime.reconcileCheckpoint(interrupt(checkpoint))
            try { publish(committed.message) } catch (_: Exception) { }
            return committed
        }
        val recovered = interrupt(checkpoint)
        if (recovered.messageReconciled && recovered === checkpoint) return recovered
        // Persist intent before projection. A crash here leaves a retryable reconciliation record.
        runtime.save(recovered)
        messages.reconcileRecovery(recovered.message)
        val committed = recovered.copy(messageReconciled = true)
        runtime.save(committed)
        try { publish(committed.message) } catch (_: Exception) { }
        return committed
    }
}
