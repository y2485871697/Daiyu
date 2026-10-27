package app.daiyu.android.domain

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.DispatchOutcome
import app.daiyu.android.domain.tools.jsonQuote
import app.daiyu.android.domain.tools.BarrierOffer
import app.daiyu.android.domain.tools.ExecutionReceipt
import app.daiyu.android.domain.tools.ReceiptBatchStart
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

sealed interface RunSlice {
    data class Completed(val message: Message) : RunSlice
    data class Suspended(val offer: BarrierOffer) : RunSlice
}

/** One owner thread applies events. Cancellation only signals and closes transport. */
class ChatRunner private constructor(private val model: ModelPort, private val store: ConversationStore,
    private val tool: BatteryTool, private val publish: (Message) -> Unit,
    private val options: RunnerOptions) {
    constructor(model: ModelPort, store: ConversationStore, tool: BatteryTool, publish: (Message) -> Unit) :
        this(model, store, tool, publish, RunnerOptions())
    constructor(model: ModelPort, store: ConversationStore, tool: BatteryTool, options: RunnerOptions,
        publish: (Message) -> Unit) : this(model, store, tool, publish, options)
    private val stopped = AtomicBoolean(false)
    /** Control state only. No I/O, callbacks, cancellation, or waits may run under this lock. */
    private val terminalLock = Object()
    private val eventLock = Any()
    @Volatile private var terminal = false
    @Volatile private var terminalReserved = false
    private var started = false
    private class CompactRequest(val create: (RunContextRuntime) -> RunContextRuntime, val completed: (Boolean, String?) -> Unit)
    private var pendingCompact: CompactRequest? = null
    @Volatile private var compactingRuntime: RunContextRuntime? = null

    /** Maintenance only: never cancel the streaming response or an executing tool batch. */
    fun requestCompaction(create: (RunContextRuntime) -> RunContextRuntime, completed: (Boolean, String?) -> Unit = { _, _ -> }): Boolean {
        val waiter = synchronized(terminalLock) {
            if (options.contextRuntime?.canCompact != true || terminal || terminalReserved || stopped.get() || pendingCompact != null) return false
            pendingCompact = CompactRequest(create, completed)
            boundaryWaiter
        }
        waiter?.let { LockSupport.unpark(it) }
        return true
    }

    fun requestCompaction(completed: (Boolean, String?) -> Unit = { _, _ -> }): Boolean =
        requestCompaction({ it }, completed)

    @Volatile private var pauseRequested = false
    @Volatile private var contextLimited = false
    @Volatile private var reasoningInterruptedAtNanos: Long? = null
    @Volatile private var breakStream = false
    @Volatile private var generation = 0L
    @Volatile private var boundaryWaiter: Thread? = null
    @Volatile private var requestOpening = false
    private val supplements = mutableListOf<Message>()
    private val supplementIds = mutableSetOf<MessageId>()
    private val sourceMessageIds = mutableSetOf<MessageId>()
    private data class PendingSupplement(val message: Message, val complete: (SupplementAdmission) -> Unit)
    private val pendingSupplements = java.util.ArrayDeque<PendingSupplement>()
    private var admittingSupplement: Message? = null
    private fun queuedSupplementMessages(): List<Message> = supplements + pendingSupplements.map { it.message } +
        listOfNotNull(admittingSupplement)
    @Volatile private var execution = ExecutionState()
    @Volatile private var session: ModelSession? = null
    @Volatile private var reconnectBudget: TransportReconnectBudget? = null
    @Volatile private var transportInterruption: (() -> Unit)? = null
    private var attemptDelivery: ProviderEventDeliveryGate? = null
    @Volatile private var pauseEpoch = 0L
    private var pauseRevision = 0L
    private fun syncReconnectPause() {
        val snapshot = synchronized(terminalLock) { Triple(reconnectBudget, pauseRequested, pauseRevision) }
        snapshot.first?.setPaused(snapshot.second, snapshot.third)
    }
    private fun retainsTransportOnPause(): Boolean =
        (session is CheckpointedResumableModelSession || session is RequestRetryModelSession) && options.reconnect.budgetFactory != null
    @Volatile private var identity: Message? = null
    private class WaitingSlice(
        val offer: BarrierOffer,
        val generation: Long,
        val continuation: Continuation<ExecutionReceipt>,
        val isCurrent: () -> Boolean
    )
    private var waitingSlice: WaitingSlice? = null
    private var sliceCompletion: Result<Message>? = null

    /** These entry points belong to the parent's serialized worker slot, never a UI callback. */
    fun startSlice(initial: Message, history: List<Message>): RunSlice =
        startInternal(initial, history, allowBarrier = true)

    fun resumeSlice(offer: BarrierOffer, receipt: ExecutionReceipt): RunSlice {
        val waiting = synchronized(terminalLock) {
            check(!terminal && !terminalReserved && !stopped.get()) { "Runner已停止" }
            val held = checkNotNull(waitingSlice) { "没有待恢复的工具" }
            check(held.offer === offer && offer.waitingCommitted &&
                held.generation == generation && held.isCurrent()) { "过期或不属于本轮的offer" }
            waitingSlice = null
            sliceCompletion = null
            held
        }
        waiting.continuation.resumeWith(Result.success(receipt))
        return collectSlice()
    }

    /** Call only after the parent has durably stopped the Question session and its waiter. */
    fun drainStoppedSlice(): RunSlice {
        val waiting = synchronized(terminalLock) {
            check(stopped.get()) { "必须先停止Runner" }
            waitingSlice.also {
                if (it != null) {
                    waitingSlice = null
                    sliceCompletion = null
                }
            }
        }
        if (waiting != null) {
            waiting.continuation.resumeWith(Result.failure(InterruptedException()))
        }
        return collectSlice()
    }

    private fun collectSlice(): RunSlice {
        sliceCompletion?.let { return RunSlice.Completed(it.getOrThrow()) }
        return synchronized(terminalLock) {
            RunSlice.Suspended(checkNotNull(waitingSlice) { "Runner未完成且没有Continuation" }.offer)
        }
    }

    private fun startInternal(initial: Message, history: List<Message>, allowBarrier: Boolean): RunSlice {
        require(initial.status == RunStatus.RUNNING && initial.role == "assistant")
        synchronized(terminalLock) {
            check(!started) { "Runner只能启动一次" }
            started = true
            identity = initial
            sourceMessageIds.addAll((history + initial).flatMap { listOf(it.id) + it.historySegments.map { segment -> segment.messageId } })
        }
        val body: suspend () -> Message = { runBody(initial, history, allowBarrier) }
        body.startCoroutine(object : Continuation<Message> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Message>) { sliceCompletion = result }
        })
        return collectSlice()
    }

    fun state(): ExecutionState = execution
    fun stop() {
        // Reserve the terminal outcome before observing the session. The object captured here is
        // the one that may be cancelled; never read session again after leaving the lock.
        val captured = synchronized(terminalLock) {
            if (terminal || terminalReserved) return
            terminalReserved = true
            stopped.set(true)
            if (reasoningInterruptedAtNanos == null) reasoningInterruptedAtNanos = System.nanoTime()
            generation++
            Pair(session, boundaryWaiter)
        }
        captured.second?.let { LockSupport.unpark(it) }
        reconnectBudget?.finish("stopped")
        cancelSession(captured.first)
        options.contextRuntime?.cancel()
        compactingRuntime?.cancel()
    }
    /** Nonblocking control call. A tool already executing is allowed to finish its batch. */
    fun pause(): Boolean {
        val captured = synchronized(terminalLock) {
            if (terminal || terminalReserved || stopped.get() || contextLimited) return false
            pauseRequested = true
            if (reasoningInterruptedAtNanos == null) reasoningInterruptedAtNanos = System.nanoTime()
            pauseRevision++
            pauseEpoch++
            // Control-only invalidation is synchronous: a quick resume cannot revive this gate.
            attemptDelivery?.expire()
            execution = execution.copy(pauseRequested = true)
            if (requestOpening || execution.phase == ExecutionPhase.STREAMING || execution.phase == ExecutionPhase.RECONNECTING) {
                if (!retainsTransportOnPause()) { breakStream = true; generation++ }
                Triple(session, boundaryWaiter, transportInterruption)
            } else Triple(null, boundaryWaiter, null)
        }
        captured.second?.let { LockSupport.unpark(it) }
        val retained = captured.first
        if (options.reconnect.budgetFactory != null &&
            (retained is CheckpointedResumableModelSession || retained is RequestRetryModelSession)) {
            syncReconnectPause()
            captured.third?.invoke() ?: when (retained) {
                is CheckpointedResumableModelSession -> retained.disconnectTransport()
                is RequestRetryModelSession -> retained.disconnectTransport()
                else -> Unit
            }
        } else cancelSession(retained)
        options.contextRuntime?.cancel()
        compactingRuntime?.cancel()
        return true
    }
    /** Releases the user pause without changing the running owner. */
    fun resume(): Boolean {
        val waiter = synchronized(terminalLock) {
            if (terminal || terminalReserved || stopped.get() || !pauseRequested || contextLimited) return false
            pauseRequested = false
            pauseRevision++
            execution = execution.copy(pauseRequested = false)
            boundaryWaiter
        }
        syncReconnectPause()
        waiter?.let { LockSupport.unpark(it) }
        return true
    }
    /** Caller persists the user message first, on its own writer. Identity checked, not re-saved here. */
    fun supplement(message: Message): Boolean {
        val captured = synchronized(terminalLock) {
            val owner = identity ?: return false
            if (terminal || terminalReserved || stopped.get() || message.role != "user" || message.status != RunStatus.COMPLETED ||
                message.conversationId != owner.conversationId || message.runId != owner.runId ||
                message.id == owner.id || message.error != null || message.historySegments.isNotEmpty() ||
                message.blocks.any { it is MessageBlock.Tool } || message.id in supplementIds) return false
            supplementIds.add(message.id)
            supplements.add(message)
            execution = execution.copy(queuedSupplements = supplements.size)
            if (requestOpening || (!pauseRequested && (execution.phase == ExecutionPhase.STREAMING || execution.phase == ExecutionPhase.RECONNECTING))) {
                breakStream = true
                generation++
                Pair(session, boundaryWaiter)
            } else Pair(null, boundaryWaiter)
        }
        captured.second?.let { LockSupport.unpark(it) }
        cancelSession(captured.first)
        return true
    }
    /** Queues on the live owner; durable admission precedes the next model request.
     * A paused run resumes, while an executing tool batch keeps its receipt boundary. */
    fun admitSupplement(message: Message, complete: (SupplementAdmission) -> Unit): Boolean {
        val frozen = message.copy(blocks = message.blocks.toList(), protocolItems = message.protocolItems.toList(),
            historySegments = message.historySegments.toList())
        val pending = PendingSupplement(frozen, complete)
        val captured = synchronized(terminalLock) {
            val owner = identity
            val rawText = frozen.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { it.text }
            val text = rawText.trim()
            val queued = queuedSupplementMessages()
            if (owner == null || terminal || terminalReserved || stopped.get() ||
                options.runtimeStore !is AtomicSupplementRuntimeStore || !options.runtimeStore.durable ||
                frozen.role != Message.USER_ROLE || frozen.status != RunStatus.COMPLETED ||
                frozen.conversationId != owner.conversationId || frozen.runId != owner.runId || frozen.id == owner.id ||
                frozen.error != null || frozen.historySegments.isNotEmpty() || frozen.protocolItems.isNotEmpty() ||
                frozen.blocks.isEmpty() || frozen.blocks.any { it !is MessageBlock.Text } || text.isBlank() ||
                rawText.length > 64_000 || queued.size >= 16 ||
                queued.sumOf { it.blocks.filterIsInstance<MessageBlock.Text>().sumOf { block -> block.text.length } } + rawText.length > 192_000 ||
                queued.any { it.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") { block -> block.text }.trim() == text } ||
                frozen.id in supplementIds || frozen.id in sourceMessageIds) null
            else {
                supplementIds.add(frozen.id)
                pendingSupplements.addLast(pending)
                breakStream = true
                if (pauseRequested) { pauseRequested = false; pauseRevision++ }
                execution = execution.copy(pauseRequested = false)
                val interrupt = requestOpening || execution.phase == ExecutionPhase.STREAMING ||
                    execution.phase == ExecutionPhase.RECONNECTING
                if (interrupt) {
                    if (reasoningInterruptedAtNanos == null) reasoningInterruptedAtNanos = System.nanoTime()
                    attemptDelivery?.expire()
                    generation++
                }
                Pair(if (interrupt) session else null, boundaryWaiter)
            }
        }
        if (captured == null) {
            completeAdmission(pending, SupplementAdmission.Rejected(message.id))
            return false
        }
        syncReconnectPause()
        captured.second?.let { LockSupport.unpark(it) }
        cancelSession(captured.first)
        options.contextRuntime?.cancel()
        compactingRuntime?.cancel()
        return true
    }
    private fun completeAdmission(pending: PendingSupplement, result: SupplementAdmission) {
        try { pending.complete(result) } catch (_: Exception) { /* Observers cannot undo a committed admission. */ }
    }
    /** Public recovery entry point; never opens a model or executes a tool. */
    fun recover(checkpoint: RunCheckpoint): Message {
        val runtime = requireNotNull(options.runtimeStore) { "恢复需要检查点存储" }
        return ProcessRecovery.recover(checkpoint, store, runtime, publish).message
    }
    private fun setPhase(phase: ExecutionPhase, attempt: Int = 0) = synchronized(terminalLock) {
        val actual = if (phase == ExecutionPhase.BOUNDARY || phase == ExecutionPhase.PAUSED) {
            if (pauseRequested || contextLimited) ExecutionPhase.PAUSED else ExecutionPhase.BOUNDARY
        } else phase
        execution = ExecutionState(actual, pauseRequested || contextLimited, supplements.size, attempt, contextLimited)
    }
    fun run(initial: Message, history: List<Message>): Message =
        when (val slice = startInternal(initial, history, allowBarrier = false)) {
            is RunSlice.Completed -> slice.message
            is RunSlice.Suspended -> error("同步Runner不能等待用户")
        }

    private suspend fun runBody(initial: Message, history: List<Message>, allowBarrier: Boolean): Message {
        var current = initial
        var sourceHistory = history.toList()
        var modelHistory = sourceHistory
        var segmentHistory = RunnerHistorySegments(initial) { reasoningInterruptedAtNanos ?: System.nanoTime() }
        val contextRuntime = options.contextRuntime
        var contextState = contextRuntime?.let { RunContextCheckpoint() }
        var acceptedContextSegments = 0
        var reopenAfterCompaction = false
        var contextInputOverhead = 0L
        fun effectiveHistory(message: Message = current): List<Message> = if (contextRuntime == null) modelHistory else modelHistory +
            MessageHistory.expand(listOf(segmentHistory.project(message).copy(status = RunStatus.COMPLETED, error = null)))
                .drop(acceptedContextSegments)
        var outputs = emptyList<ToolOutput>()
        var round = 0
        var requestUnconfirmed = false
        var request: RequestCheckpoint? = null
        var transportCheckpoint: TransportCheckpoint? = null
        var stagingTransportBatch = false
        var usageRequests = emptyList<RequestCheckpoint>()
        var usage: List<RequestUsageCheckpoint> =
            (options.runtimeStore as? PreparationRuntimeStore)?.preparationUsage(initial).orEmpty()
        val executionOwner = RunExecutionOwner.issue(initial)
        val owner = executionOwner.ownerId
        var ownerBound = false
        var revision = 0L
        var pendingCheckpoint: PendingToolCheckpoint? = null
        val records = linkedMapOf<ToolCallId, ToolRecord>()
        fun snapshot(message: Message = current, usageSnapshot: List<RequestUsageCheckpoint> = usage): RunCheckpoint = synchronized(terminalLock) {
            val now = reasoningInterruptedAtNanos ?: System.nanoTime()
            RunCheckpoint(message.frozenReasoning(now), effectiveHistory(message).map { it.frozenReasoning(now) },
                if (message.status != RunStatus.RUNNING) ExecutionPhase.TERMINAL else execution.phase,
                records.values.toList(), outputs, supplements.toList(), round, requestUnconfirmed,
                owner = owner, revision = revision + 1,
                pending = if (message.status == RunStatus.RUNNING) pendingCheckpoint else null, request = request, usage = usageSnapshot, usageRequests = usageRequests, transport = transportCheckpoint, context = contextState)
        }
        fun checkpoint(next: RunCheckpoint = snapshot()) {
            if (stagingTransportBatch) return
            val runtime = options.runtimeStore ?: return
            try {
                if (runtime is AtomicRuntimeStore) runtime.commitCheckpoint(next) else runtime.save(next)
                revision = next.revision
            } catch (failure: Exception) { throw PersistenceException(failure) }
            if (next.phase == ExecutionPhase.PAUSED) {
                try { publish(next.message) } catch (_: Exception) { /* Observers cannot undo a committed boundary. */ }
            }
        }
        suspend fun awaitBarrier(offer: BarrierOffer): ExecutionReceipt {
            val runtime = checkNotNull(options.runtimeStore as? AtomicRuntimeStore) {
                "用户等待需要原子检查点存储"
            }
            check(runtime.durable) { "用户等待需要持久检查点存储" }
            pendingCheckpoint = offer.pending
            setPhase(ExecutionPhase.WAITING_USER)
            val expected = snapshot()
            try {
                runtime.commitCheckpoint(expected)
            } catch (failure: Exception) {
                val committed = try { runtime.readCommittedCheckpoint(expected) } catch (_: Exception) { null }
                if (committed != expected) throw PersistenceException(failure)
            }
            revision = expected.revision
            return suspendCoroutine { continuation ->
                synchronized(terminalLock) {
                    if (stopped.get() || terminalReserved || terminal) throw InterruptedException()
                    check(waitingSlice == null)
                    offer.markWaitingCommitted()
                    waitingSlice = WaitingSlice(offer, generation, continuation) {
                        revision == expected.revision && pendingCheckpoint == expected.pending &&
                            owner == expected.owner && offer.context.owner === executionOwner &&
                            execution.phase == ExecutionPhase.WAITING_USER
                    }
                }
            }
        }
        fun apply(candidate: Message) {
            if (candidate.status != RunStatus.RUNNING) segmentHistory.finishReasoning()
            val next = segmentHistory.project(candidate)
            if (stagingTransportBatch) { current = next; return }
            val runtime = options.runtimeStore
            try {
                if (runtime is AtomicRuntimeStore) {
                    val nextCheckpoint = snapshot(next)
                    runtime.commitCheckpoint(nextCheckpoint)
                    revision = nextCheckpoint.revision
                } else store.save(next.frozenReasoning(reasoningInterruptedAtNanos ?: System.nanoTime()))
            } catch (failure: Exception) { throw PersistenceException(failure) }
            current = next
            if (next.status != RunStatus.RUNNING) {
                // The terminal transaction succeeded. UI/snapshot errors cannot downgrade it.
                terminal = true; setPhase(ExecutionPhase.TERMINAL)
                if (runtime !is AtomicRuntimeStore) try { checkpoint() } catch (_: Exception) { }
            } else if (runtime !is AtomicRuntimeStore) checkpoint()
            try { publish(next) } catch (_: Exception) { /* UI notification is not a domain failure. */ }
        }
        val calls = linkedMapOf<ToolCallId, ToolInvocation>()
        val results = mutableMapOf<ToolCallId, String>()
        val unknownOperations = mutableSetOf<Pair<String, String>>()
        fun awaitPause() {
            while (true) {
                var pending: PendingSupplement? = null
                val needsCheckpoint = synchronized(terminalLock) {
                    if (stopped.get() || terminalReserved) { boundaryWaiter = null; return }
                    pending = pendingSupplements.pollFirst()
                    admittingSupplement = pending?.message
                    if (pending != null) { setPhase(ExecutionPhase.BOUNDARY); false }
                    else {
                        if (!pauseRequested) { boundaryWaiter = null; return }
                        boundaryWaiter = Thread.currentThread()
                        val changed = execution.phase != ExecutionPhase.PAUSED
                        setPhase(ExecutionPhase.PAUSED)
                        changed
                    }
                }
                val admission = pending
                if (admission != null) {
                    val expected = snapshot().copy(supplements = synchronized(terminalLock) { supplements.toList() } + admission.message)
                    try {
                        (options.runtimeStore as AtomicSupplementRuntimeStore).commitSupplement(expected, admission.message)
                    } catch (failure: Exception) {
                        synchronized(terminalLock) { admittingSupplement = null }
                        completeAdmission(admission, SupplementAdmission.Unconfirmed(admission.message.id))
                        throw PersistenceException(failure)
                    }
                    revision = expected.revision
                    synchronized(terminalLock) {
                        admittingSupplement = null
                        supplements.add(admission.message)
                        execution = execution.copy(queuedSupplements = supplements.size)
                    }
                    completeAdmission(admission, SupplementAdmission.Accepted(admission.message.id))
                    continue
                }
                if (needsCheckpoint) checkpoint()
                LockSupport.park()
            }
        }
        fun boundary(): Boolean {
            segmentHistory.finishReasoning()
            current = segmentHistory.project(current)
            setPhase(if (pauseRequested) ExecutionPhase.PAUSED else ExecutionPhase.BOUNDARY)
            checkpoint()
            awaitPause()
            if (stopped.get() || terminalReserved) throw InterruptedException()
            val acceptedUsers = synchronized(terminalLock) {
                if (stopped.get() || terminalReserved) throw InterruptedException()
                if (!breakStream && supplements.isEmpty()) null
                else supplements.toList().also {
                    supplements.clear(); breakStream = false
                    execution = execution.copy(queuedSupplements = 0)
                }
            }
            if (acceptedUsers != null) {
                val sealed = segmentHistory.sealSession(current, acceptedUsers)
                segmentHistory = RunnerHistorySegments(sealed) { reasoningInterruptedAtNanos ?: System.nanoTime() }
                outputs = emptyList()
                modelHistory = if (contextRuntime == null) sourceHistory + sealed.copy(status = RunStatus.COMPLETED, error = null)
                    else effectiveHistory()
                if (contextRuntime != null) acceptedContextSegments = sealed.historySegments.size
                apply(sealed)
            }
            setPhase(ExecutionPhase.BOUNDARY)
            checkpoint()
            return acceptedUsers != null
        }
        fun compactAtBoundary(): Boolean {
            val base = contextRuntime ?: return false
            if (!base.canCompact) return false
            val pressure = contextState?.pressure
            val selected = synchronized(terminalLock) {
                if (stopped.get() || terminalReserved || pauseRequested || breakStream || supplements.isNotEmpty() || pendingSupplements.isNotEmpty()) return false
                if (pendingCompact == null && (!base.automatic || pressure == null)) return false
                (pendingCompact to pauseEpoch).also { pendingCompact = null }
            }
            val manual = selected.first
            val compactEpoch = selected.second
            val cancelled = { compactEpoch != pauseEpoch || stopped.get() || terminalReserved || pauseRequested || breakStream || supplements.isNotEmpty() || pendingSupplements.isNotEmpty() }
            var applied = false
            var error: String? = null
            var runtime: RunContextRuntime? = null
            var admissionAttempted = false
            var persistenceFailure: PersistenceException? = null
            val compactionBoundary = segmentHistory.project(current)
            val admittedRequests = hashSetOf<String>()
            fun persistSummary(value: RunCheckpoint? = null) {
                try { if (value == null) checkpoint() else checkpoint(value) }
                catch (failure: PersistenceException) { persistenceFailure = failure; throw failure }
            }
            try {
                val selectedRuntime = manual?.create?.invoke(base) ?: base
                runtime = selectedRuntime
                compactingRuntime = selectedRuntime
                if (cancelled()) return false
                selectedRuntime.beginCompaction(compactionBoundary)
                val before = effectiveHistory()
                val historyLocal = selectedRuntime.localTokens(before, (tool as? CatalogTool)?.definitions.orEmpty())
                val anchor = contextState?.calibration ?: contextState?.input
                val local = anchor?.let { (it.localTokens + historyLocal - it.historyTokens).coerceAtLeast(0) } ?: historyLocal
                val billed = anchor?.billedTokens?.let { (it + local - anchor.localTokens).coerceAtLeast(0) }
                if (cancelled()) return false
                val candidate = selectedRuntime.compactRequests(before, billed, local, object : RunSummaryRequests {
                    override fun create() = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(), owner, RequestOperation.NEXT)
                    override fun admit(request: RequestCheckpoint) {
                        persistenceFailure?.let { throw it }
                        check(!cancelled() && admittedRequests.add(request.requestId))
                        admissionAttempted = true
                        contextState = checkNotNull(contextState).copy(pressure = null,
                            summaries = checkNotNull(contextState).summaries + request)
                        persistSummary()
                    }
                    override fun observe(request: RequestCheckpoint, event: ModelEvent.Usage) {
                        persistenceFailure?.let { throw it }
                        check(request.requestId in admittedRequests)
                        val accepted = RequestUsageCheckpoint(request, event, UsageRequestSource.SUMMARY)
                        val next = usage.filterNot { it.request.owner == owner && it.request.requestId == request.requestId } + accepted
                        persistSummary(snapshot(usageSnapshot = next)); usage = next
                    }
                }, cancelled)
                persistenceFailure?.let { throw it }
                if (candidate == null || cancelled()) return false
                require(candidate.all { it.conversationId == initial.conversationId })
                val sealed = segmentHistory.sealSession(current, emptyList())
                segmentHistory = RunnerHistorySegments(sealed) { reasoningInterruptedAtNanos ?: System.nanoTime() }
                current = sealed
                acceptedContextSegments = sealed.historySegments.size
                sourceHistory = candidate.toList(); modelHistory = sourceHistory
                outputs = emptyList()
                transportCheckpoint = null
                contextState = checkNotNull(contextState).copy(input = null, calibration = null, pressure = null,
                    compactions = checkNotNull(contextState).compactions + 1)
                val expected = snapshot()
                val store = options.runtimeStore
                try { checkpoint(expected) } catch (failure: PersistenceException) {
                    val committed = try { (store as? AtomicRuntimeStore)?.readCommittedCheckpoint(expected) } catch (_: Exception) { null }
                    if (committed != expected) { persistenceFailure = failure; throw failure }
                    revision = expected.revision
                }
                reopenAfterCompaction = true
                applied = true
                return true
            } catch (failure: Exception) {
                persistenceFailure?.let { throw it }
                if (failure is PersistenceException) throw failure
                if (cancelled()) return false
                error = (failure as? ModelRequestException)?.userMessage ?: failure.message ?: "压缩失败，原始上下文保留"
                val failedRuntime = runtime ?: base
                try {
                    if (runtime == null) failedRuntime.beginCompaction(compactionBoundary)
                    failedRuntime.failed(error)
                } catch (_: Exception) { }
                // Keep the failure divider at this boundary; the next session replays history, not tools.
                val sealed = segmentHistory.sealSession(current, emptyList())
                segmentHistory = RunnerHistorySegments(sealed) { reasoningInterruptedAtNanos ?: System.nanoTime() }
                current = sealed
                modelHistory = effectiveHistory()
                acceptedContextSegments = sealed.historySegments.size
                outputs = emptyList()
                transportCheckpoint = null
                reopenAfterCompaction = true
                contextState = checkNotNull(contextState).copy(pressure = null)
                checkpoint()
                return false
            } finally {
                try { (runtime ?: base).finishedCompaction() } catch (_: Exception) { }
                compactingRuntime = null
                try { manual?.completed?.invoke(applied, error) } catch (_: Exception) { }
                if (!admissionAttempted && persistenceFailure == null && pressure != null && !cancelled() && contextState?.pressure == pressure) {
                    contextState = checkNotNull(contextState).copy(pressure = null)
                    checkpoint()
                }
            }
        }
        fun contextSize(): Long {
            val runtime = contextRuntime ?: return 0
            val local = runtime.localTokens(effectiveHistory(), (tool as? CatalogTool)?.definitions.orEmpty())
            val anchor = contextState?.calibration ?: contextState?.input
            return if (anchor != null) ((anchor.billedTokens ?: anchor.localTokens) + local - anchor.historyTokens).coerceAtLeast(0)
                else (local + contextInputOverhead).coerceAtLeast(0)
        }
        fun awaitContextCapacity() {
            val runtime = contextRuntime ?: return
            while (true) {
                if (stopped.get() || terminalReserved) throw InterruptedException()
                val controls = synchronized(terminalLock) {
                    pauseRequested || breakStream || supplements.isNotEmpty() || pendingSupplements.isNotEmpty()
                }
                if (controls && boundary()) reopenAfterCompaction = true
                // A manual summary is maintenance on the same writer, not a new model/tool turn.
                if (synchronized(terminalLock) { pendingCompact != null }) compactAtBoundary()
                val estimate = contextSize()
                if (estimate <= runtime.sendBudgetTokens) {
                    if (contextLimited) {
                        contextState = checkNotNull(contextState).copy(capacity = null)
                        synchronized(terminalLock) { contextLimited = false; boundaryWaiter = null }
                        setPhase(ExecutionPhase.BOUNDARY)
                        checkpoint()
                    }
                    return
                }
                if (!contextLimited) {
                    val claimed = synchronized(terminalLock) {
                        if (stopped.get() || terminalReserved) throw InterruptedException()
                        if (pauseRequested || breakStream || supplements.isNotEmpty() || pendingSupplements.isNotEmpty()) false
                        else { contextLimited = true; setPhase(ExecutionPhase.PAUSED); true }
                    }
                    if (!claimed) continue
                    // Freeze the stop position so resumed text starts below the divider.
                    val sealed = segmentHistory.sealSession(current, emptyList())
                    segmentHistory = RunnerHistorySegments(sealed) { reasoningInterruptedAtNanos ?: System.nanoTime() }
                    current = sealed
                    val segment = (MessageHistory.expand(listOf(sealed)).count {
                        it.id == initial.id && it.runId == initial.runId
                    } - 1).coerceAtLeast(0)
                    contextState = checkNotNull(contextState).copy(capacity = ContextCapacityCheckpoint(
                        UUID.randomUUID().toString(), System.currentTimeMillis(), estimate,
                        runtime.windowTokens, runtime.sendBudgetTokens, segment))
                    checkpoint()
                }
                val park = synchronized(terminalLock) {
                    if (stopped.get() || terminalReserved || pauseRequested || breakStream || pendingCompact != null ||
                        supplements.isNotEmpty() || pendingSupplements.isNotEmpty()) false
                    else { boundaryWaiter = Thread.currentThread(); true }
                }
                if (park) LockSupport.park()
            }
        }
        fun open(): ModelSession {
            while (true) {
                boundary()
                if (!ownerBound) {
                    options.receiptBatchExecutor?.bindOwner(executionOwner)
                    ownerBound = true
                }
                compactAtBoundary()
                awaitContextCapacity()
                val token = synchronized(terminalLock) {
                    if (stopped.get() || terminalReserved || terminal) throw InterruptedException()
                    if (pauseRequested || supplements.isNotEmpty() || pendingSupplements.isNotEmpty() || breakStream) null
                    else { requestOpening = true; generation to pauseEpoch }
                } ?: continue
                // A supplement starts a new provider session, never a resume of the old cursor.
                transportCheckpoint = null
                request = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(), owner, RequestOperation.OPEN)
                requestUnconfirmed = true
                try {
                    checkpoint() // open may itself send a request; persist uncertainty first.
                    val admitted = synchronized(terminalLock) {
                        if (stopped.get() || terminalReserved || terminal) throw InterruptedException()
                        token.first == generation && token.second == pauseEpoch && !pauseRequested && supplements.isEmpty() && pendingSupplements.isEmpty() && !breakStream
                    }
                    if (!admitted) continue
                    val opened = try {
                        if (tool is CatalogTool) model.open(modelHistory, tool.definitions) else model.open(modelHistory)
                    } catch (failure: Exception) {
                        val invalidated = synchronized(terminalLock) {
                            if (stopped.get() || terminalReserved || terminal) throw InterruptedException()
                            token.first != generation || token.second != pauseEpoch || pauseRequested || supplements.isNotEmpty() || pendingSupplements.isNotEmpty() || breakStream
                        }
                        if (invalidated) continue
                        throw failure
                    }
                    val accept = synchronized(terminalLock) {
                        if (stopped.get() || terminalReserved || terminal || token.first != generation || token.second != pauseEpoch ||
                            pauseRequested || supplements.isNotEmpty() || pendingSupplements.isNotEmpty() || breakStream) false
                        else { session = opened; true }
                    }
                    if (accept) { reopenAfterCompaction = false; return opened }
                    cancelSession(opened)
                } finally {
                    synchronized(terminalLock) { requestOpening = false }
                }
            }
        }
        try {
            var active = open()
            if (stopped.get()) { cancelSession(active); throw InterruptedException() }
            while (round < options.maxRounds) {
                if (boundary()) { cancelSession(active); active = open() }
                if (round > 0 && compactAtBoundary()) {
                    cancelSession(active); active = open()
                }
                awaitContextCapacity()
                if (reopenAfterCompaction) { cancelSession(active); active = open() }
                if (stopped.get()) throw InterruptedException()
                val pending = mutableListOf<ToolInvocation>()
                var repetition = ReasoningRepetitionGuard()
                var reasoningRepetition = ReasoningRepetitionGuard()
                // Keep the exact append/reset order; rebuilding from final text would change
                // the detector's stride, correction and body-progress semantics.
                val guardEdits = mutableListOf<Pair<Boolean, String?>>()
                fun appendGuard(reasoning: Boolean, text: String): Boolean {
                    guardEdits.add(reasoning to text)
                    return (if (reasoning) reasoningRepetition else repetition).append(text)
                }
                fun resetGuard(reasoning: Boolean) {
                    guardEdits.add(reasoning to null)
                    (if (reasoning) reasoningRepetition else repetition).reset()
                }
                fun restoreGuards(size: Int) {
                    guardEdits.subList(size, guardEdits.size).clear()
                    repetition = ReasoningRepetitionGuard()
                    reasoningRepetition = ReasoningRepetitionGuard()
                    guardEdits.forEach { (reasoning, text) ->
                        val guard = if (reasoning) reasoningRepetition else repetition
                        if (text == null) guard.reset() else guard.append(text)
                    }
                }
                val responseText = linkedMapOf<String, String>()
                val reasoningText = linkedMapOf<String, String>()
                var reasoningBaseline: Map<String, String> = emptyMap()
                var repetitiveSurface: String? = null
                fun checkText(itemId: String, text: String, final: Boolean, reasoning: Boolean = false): Boolean {
                    val texts = if (reasoning) reasoningText else responseText
                    val previous = texts[itemId].orEmpty()
                    val next = if (final) text else previous + text
                    fun uncounted(id: String, value: String): String {
                        val baseline = if (reasoning) reasoningBaseline[id].orEmpty() else ""
                        return value.substring(value.commonPrefixWith(baseline).length)
                    }
                    val before = uncounted(itemId, previous)
                    val after = uncounted(itemId, next)
                    val repetitive = if (after.startsWith(before)) {
                        appendGuard(reasoning, after.substring(before.length))
                    } else {
                        resetGuard(reasoning)
                        val corrected = LinkedHashMap(texts)
                        corrected[itemId] = next
                        corrected.any { (id, value) -> appendGuard(reasoning, uncounted(id, value)) }
                    }
                    if (repetitive) {
                        val surface = if (reasoning) "思考摘要" else "正文"
                        repetitiveSurface = surface
                        cancelSession(active)
                        throw RepetitiveOutputException(surface)
                    }
                    texts[itemId] = next
                    return next.substring(next.commonPrefixWith(previous).length).isNotBlank()
                }
                fun bodyProgress(changed: Boolean) {
                    if (changed) {
                        resetGuard(reasoning = true)
                        reasoningBaseline = reasoningText.toMap()
                    }
                }
                var textFloor = current.blocks.size
                var sinkFailure: Exception? = null
                var accepting = true
                var attemptFence = 0L
                if (reopenAfterCompaction) {
                    cancelSession(active); active = open(); reopenAfterCompaction = false
                }
                val token = synchronized(terminalLock) {
                    if (stopped.get() || terminalReserved || terminal) throw InterruptedException()
                    if (pauseRequested || supplements.isNotEmpty() || pendingSupplements.isNotEmpty() || breakStream) null
                    else { reasoningInterruptedAtNanos = null; attemptFence = pauseEpoch; setPhase(ExecutionPhase.STREAMING); ++generation }
                }
                if (token == null) {
                    if (boundary()) { cancelSession(active); active = open() }
                    continue
                }
                transportCheckpoint = null
                request = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(), owner, RequestOperation.NEXT)
                usageRequests = usageRequests + checkNotNull(request)
                contextState = contextState?.copy(input = null)
                requestUnconfirmed = true
                checkpoint()
                val sink: (ModelEvent) -> Unit = { event ->
                    // Event callbacks are serialized independently of control calls. In particular,
                    // no persistence or observer callback below runs while terminalLock is held.
                    synchronized(eventLock) {
                    if (!accepting || token != generation || terminal || terminalReserved || stopped.get()) return@synchronized
                    if (retainsTransportOnPause() && (pauseRequested || attemptFence != pauseEpoch))
                        throw ModelDeliveryRejectedException()
                    try {
                    reconnectBudget?.check()
                    sinkFailure?.let { throw it }
                    repetitiveSurface?.let { throw RepetitiveOutputException(it) }
                    when (event) {
                        is ModelEvent.InputEstimate -> {
                            contextState = contextState?.copy(input = RequestInputCheckpoint(checkNotNull(request), event.tokens,
                                historyTokens = checkNotNull(contextRuntime).localTokens(effectiveHistory(),
                                    (tool as? CatalogTool)?.definitions.orEmpty())))
                            contextState?.input?.let { contextInputOverhead = it.localTokens - it.historyTokens }
                            checkpoint()
                        }
                        is ModelEvent.Usage -> {
                            val accepted = RequestUsageCheckpoint(checkNotNull(request), event)
                            val at = usage.indexOfFirst { it.request.owner == accepted.request.owner &&
                                it.request.requestId == accepted.request.requestId }
                            val nextUsage = usage.toMutableList().apply {
                                if (at < 0) add(accepted) else this[at] = accepted
                            }.toList()
                            val local = contextState?.input
                            val window = contextRuntime?.windowTokens
                            val billed = event.inputTokens
                            if (window != null) {
                                val plausible = billed != null && billed > 0 && billed <= window * 130 / 100 &&
                                    (event.cachedTokens == null || event.cachedTokens <= window)
                                val state = checkNotNull(contextState)
                                if (plausible && local != null && local.request == request)
                                    contextInputOverhead = checkNotNull(billed) - local.historyTokens
                                contextState = state.copy(
                                    calibration = if (plausible && local?.request == request) local?.copy(billedTokens = billed)
                                        else state.calibration?.takeUnless { it.request == request },
                                    pressure = if (contextRuntime.automatic && plausible &&
                                        checkNotNull(billed) >= window * 80 / 100) request
                                        else state.pressure?.takeUnless { it == request })
                            }
                            checkpoint(snapshot(usageSnapshot = nextUsage))
                            usage = nextUsage
                            try { if (!stagingTransportBatch) options.onUsage(event) } catch (_: Exception) { /* Observer failure cannot invalidate committed usage. */ }
                        }
                        is ModelEvent.ReasoningDelta -> {
                            checkText(event.itemId, event.text, final = false, reasoning = true)
                            segmentHistory.acceptReasoning(event.itemId, event.text, final = false, blockOffset = current.blocks.size)
                            apply(current)
                            try { if (!stagingTransportBatch) options.onReasoning(event) } catch (_: Exception) { /* Observer failure cannot invalidate accepted reasoning. */ }
                        }
                        is ModelEvent.ReasoningFinal -> {
                            checkText(event.itemId, event.text, final = true, reasoning = true)
                            segmentHistory.acceptReasoning(event.itemId, event.text, final = true, blockOffset = current.blocks.size)
                            apply(current)
                            try { if (!stagingTransportBatch) options.onReasoning(event) } catch (_: Exception) { /* Observer failure cannot invalidate accepted reasoning. */ }
                        }
                        is ModelEvent.ProtocolOutput -> {
                            segmentHistory.replaceSessionProtocol(current, event.items)
                            apply(current.copy(protocolItems = event.items))
                        }
                        is ModelEvent.TextDelta -> {
                            segmentHistory.finishReasoning()
                            bodyProgress(checkText(event.itemId, event.text, final = false))
                            val blocks = current.blocks.toMutableList()
                            val at = blocks.indices.firstOrNull { it >= textFloor && blocks[it] is MessageBlock.Text && (blocks[it] as MessageBlock.Text).itemId == event.itemId } ?: -1
                            if (at < 0) blocks.add(MessageBlock.Text(event.itemId, event.text))
                            else {
                                val text = blocks[at] as MessageBlock.Text
                                blocks[at] = text.copy(text = text.text + event.text)
                            }
                            apply(current.copy(blocks = blocks))
                        }
                        is ModelEvent.TextFinal -> {
                            segmentHistory.finishReasoning()
                            bodyProgress(checkText(event.itemId, event.text, final = true))
                            val blocks = current.blocks.toMutableList()
                            val at = blocks.indices.firstOrNull { it >= textFloor && blocks[it] is MessageBlock.Text && (blocks[it] as MessageBlock.Text).itemId == event.itemId } ?: -1
                            val text = MessageBlock.Text(event.itemId, event.text)
                            if (at < 0) blocks.add(text) else blocks[at] = text
                            apply(current.copy(blocks = blocks))
                        }
                        is ModelEvent.ToolReady -> {
                            segmentHistory.finishReasoning()
                            val call = event.invocation
                            val prior = calls[call.id]
                            require(prior == null || prior == call) { "重复调用标识的内容不一致" }
                            check(prior == null || pending.any { it.id == call.id }) { "模型重复请求已登记调用；未重复执行" }
                            if (prior == null) {
                                calls[call.id] = call
                                pending.add(call)
                                records[call.id] = ToolRecord(call, ToolDisposition.READY)
                                apply(current.copy(blocks = current.blocks + MessageBlock.Tool(call)))
                                textFloor = current.blocks.size
                            }
                        }
                    }
                    } catch (failure: Exception) { if (failure !is TransportReconnectDeadlineException) sinkFailure = failure; throw failure }
                    }
                }
                val acknowledgedSink: (ModelEventBatch) -> ModelDeliveryAck = { batch ->
                    synchronized(eventLock) {
                        fun currentAttempt(): Boolean = accepting && token == generation && attemptFence == pauseEpoch && !terminal &&
                            !terminalReserved && !stopped.get() && !pauseRequested && !breakStream
                        if (!currentAttempt()) ModelDeliveryAck.REJECTED
                        else {
                            val beforeCurrent = current
                            val beforeHistory = segmentHistory
                            val beforeUsage = usage
                            val beforeContext = contextState
                            val beforeTransport = transportCheckpoint
                            val beforeCalls = calls.toMap()
                            val beforePending = pending.toList()
                            val beforeRecords = records.toMap()
                            val beforeResponseText = responseText.toMap()
                            val beforeReasoningText = reasoningText.toMap()
                            val beforeBaseline = reasoningBaseline
                            val beforeSurface = repetitiveSurface
                            val beforeFloor = textFloor
                            val beforeGuardSize = guardEdits.size
                            var committed = false
                            try {
                                check(options.runtimeStore is AtomicRuntimeStore && options.runtimeStore.durable) {
                                    "Background resume requires an atomic durable checkpoint store"
                                }
                                reconnectBudget?.check()
                                segmentHistory = segmentHistory.fork()
                                stagingTransportBatch = true
                                try { batch.events.forEach(sink) } finally { stagingTransportBatch = false }
                                if (!currentAttempt()) throw ModelDeliveryRejectedException()
                                reconnectBudget?.check()
                                transportCheckpoint = batch.checkpoint
                                checkpoint()
                                committed = true
                                // The successful atomic write is the ACK linearization point.
                                // Pause/stop/deadline may fence later deliveries, not undo this ACK.
                                try { publish(current) } catch (_: Exception) {}
                                batch.events.forEach { event ->
                                    try {
                                        if (event is ModelEvent.Usage) options.onUsage(event)
                                        if (event is ModelEvent.ReasoningDelta || event is ModelEvent.ReasoningFinal) options.onReasoning(event)
                                    } catch (_: Exception) {}
                                }
                                ModelDeliveryAck.ACCEPTED
                            } catch (failure: Exception) {
                                if (!committed) {
                                    current = beforeCurrent
                                    segmentHistory = beforeHistory
                                    usage = beforeUsage
                                    contextState = beforeContext
                                    transportCheckpoint = beforeTransport
                                    calls.clear(); calls.putAll(beforeCalls)
                                    pending.clear(); pending.addAll(beforePending)
                                    records.clear(); records.putAll(beforeRecords)
                                    responseText.clear(); responseText.putAll(beforeResponseText)
                                    reasoningText.clear(); reasoningText.putAll(beforeReasoningText)
                                    reasoningBaseline = beforeBaseline
                                    repetitiveSurface = beforeSurface
                                    textFloor = beforeFloor
                                    restoreGuards(beforeGuardSize)
                                }
                                // An uncertain write or sink failure seals this run. Never retry
                                // or issue a later checkpoint from a possibly dirty candidate.
                                if (failure !is ModelDeliveryRejectedException) sinkFailure = failure
                                throw failure
                            }
                        }
                    }
                }
                // A repost replaces only this model turn. Prior tool receipts/outputs stay intact.
                val requestBaseCurrent = current
                val requestBaseHistory = segmentHistory.fork()
                val requestBaseContext = contextState
                val requestBaseOverhead = contextInputOverhead
                val requestBaseRecords = records.toMap()
                val requestBaseCalls = calls.toMap()
                val requestBaseFloor = textFloor
                var replayRequest = false
                var transportFailure: Exception? = null
                try {
                    var attempt = 0
                    while (true) {
                        var invoked = false
                        try {
                            // The admission check is deliberately after the uncertainty
                            // checkpoint: a stop during that write must not open the request.
                            synchronized(terminalLock) {
                                if (stopped.get() || terminalReserved || terminal || token != generation) {
                                    throw InterruptedException()
                                }
                                if (pauseRequested || attemptFence != pauseEpoch) throw ModelDeliveryRejectedException()
                            }
                            val delivery = ProviderEventDeliveryGate()
                            val providerInFlight = AtomicBoolean(false)
                            val checkpointed = active as? CheckpointedResumableModelSession
                            val interrupt: () -> Unit = {
                                delivery.expire()
                                when (val original = active) {
                                    is CheckpointedResumableModelSession -> original.disconnectTransport()
                                    is RequestRetryModelSession -> original.disconnectTransport()
                                }
                            }
                            synchronized(terminalLock) {
                                if (stopped.get() || terminalReserved || terminal || token != generation) throw InterruptedException()
                                if (pauseRequested || attemptFence != pauseEpoch) throw ModelDeliveryRejectedException()
                                attemptDelivery = delivery
                                transportInterruption = interrupt
                            }
                            var binding: AutoCloseable? = null
                            try {
                                binding = reconnectBudget?.attach(interrupt)
                                reconnectBudget?.check()
                                val attemptSink: (ModelEvent) -> Unit = { event ->
                                    val ack = delivery.deliverAcknowledged { sink(event); ModelDeliveryAck.ACCEPTED }
                                    if (ack == ModelDeliveryAck.REJECTED && providerInFlight.get() &&
                                        (active is RequestRetryModelSession || active is CheckpointedResumableModelSession))
                                        throw ModelDeliveryRejectedException()
                                }
                                // attach/check may call outside code. Re-admit only while the
                                // registered gate and the pre-checkpoint pause fence still match.
                                synchronized(terminalLock) {
                                    if (stopped.get() || terminalReserved || terminal || token != generation) throw InterruptedException()
                                    if (pauseRequested || attemptFence != pauseEpoch || delivery.isClosed) throw ModelDeliveryRejectedException()
                                }
                                if (replayRequest) {
                                    synchronized(eventLock) {
                                        segmentHistory = requestBaseHistory.fork()
                                        current = requestBaseCurrent
                                        contextState = requestBaseContext
                                        contextInputOverhead = requestBaseOverhead
                                        transportCheckpoint = null
                                        records.clear(); records.putAll(requestBaseRecords)
                                        calls.clear(); calls.putAll(requestBaseCalls)
                                        pending.clear()
                                        responseText.clear(); reasoningText.clear()
                                        reasoningBaseline = emptyMap()
                                        repetitiveSurface = null
                                        restoreGuards(0)
                                        textFloor = requestBaseFloor
                                        apply(current)
                                    }
                                    replayRequest = false
                                }
                                reconnectBudget?.check()
                                synchronized(terminalLock) {
                                    if (stopped.get() || terminalReserved || terminal || token != generation) throw InterruptedException()
                                    if (pauseRequested || attemptFence != pauseEpoch || delivery.isClosed) throw ModelDeliveryRejectedException()
                                }
                                invoked = true
                                providerInFlight.set(true)
                                if (checkpointed != null) {
                                    check(options.runtimeStore is AtomicRuntimeStore && options.runtimeStore.durable) {
                                        "Background resume requires an atomic durable checkpoint store"
                                    }
                                    val batchSink: (ModelEventBatch) -> ModelDeliveryAck = { batch ->
                                        delivery.deliverAcknowledged { acknowledgedSink(batch) }
                                    }
                                    if (attempt == 0) checkpointed.nextAcknowledged(outputs, attemptSink, batchSink)
                                    else checkpointed.resumeAcknowledged(batchSink)
                                } else if (attempt == 0) active.next(outputs, attemptSink)
                                else (active as ResumableModelSession).resume(attemptSink)
                                reconnectBudget?.check()
                            } finally {
                                providerInFlight.set(false)
                                synchronized(terminalLock) {
                                    if (attemptDelivery === delivery) attemptDelivery = null
                                    if (transportInterruption === interrupt) transportInterruption = null
                                }
                                // Drain even when releasing an external budget binding fails.
                                try { binding?.close() } finally { delivery.close() }
                            }
                            reconnectBudget?.finish("succeeded")
                            break
                        } catch (failure: Exception) {
                            if (stopped.get() || breakStream || sinkFailure != null) throw failure
                            // No HTTP request has started: a control fence is not a network error.
                            if (!invoked && failure is ModelDeliveryRejectedException && retainsTransportOnPause()) {
                                awaitPause()
                                synchronized(terminalLock) {
                                    if (stopped.get() || terminalReserved || breakStream) throw InterruptedException()
                                    attemptFence = pauseEpoch
                                    setPhase(ExecutionPhase.STREAMING, attempt)
                                }
                                checkpoint()
                                continue
                            }
                            val resumable = active as? ResumableModelSession
                            val classified = (failure as? ModelRequestException)?.failure
                                ?: if (failure is ModelDeliveryRejectedException && (active is CheckpointedResumableModelSession || active is RequestRetryModelSession))
                                    ModelFailure.NETWORK else null
                            val factory = options.reconnect.budgetFactory.takeIf { active is CheckpointedResumableModelSession || active is RequestRetryModelSession }
                            val canResume = try { resumable?.canResume == true } catch (_: Exception) { false }
                            if ((factory == null && attempt >= options.reconnect.maxAttempts) ||
                                classified !in options.reconnect.failures || !canResume) throw failure
                            replayRequest = (active as? RequestRetryModelSession)?.retriesCurrentRequest == true
                            segmentHistory.finishReasoning()
                            apply(current)
                            if (factory != null) {
                                if (reconnectBudget == null) reconnectBudget = factory.create(round, checkNotNull(classified))
                                val budget = checkNotNull(reconnectBudget)
                                budget.updateReason(checkNotNull(classified))
                                syncReconnectPause()
                                budget.check()
                                if (pauseRequested) awaitPause()
                                budget.check()
                            }
                            synchronized(terminalLock) {
                                if (stopped.get() || terminalReserved || breakStream) throw InterruptedException()
                                setPhase(ExecutionPhase.RECONNECTING, ++attempt)
                            }
                            checkpoint()
                            if (options.reconnect.delayMillis > 0) {
                                // A deadline park is outside the control lock and is interruptible
                                // by stop/resume's unpark; unlike sleep it does not add stop latency.
                                val deadline = System.nanoTime() + options.reconnect.delayMillis * 1_000_000L
                                while (true) {
                                    reconnectBudget?.check()
                                    if (reconnectBudget != null && pauseRequested) awaitPause()
                                    val remaining = deadline - System.nanoTime()
                                    if (remaining <= 0L) break
                                    synchronized(terminalLock) {
                                        if (stopped.get() || terminalReserved || breakStream) throw InterruptedException()
                                        boundaryWaiter = Thread.currentThread()
                                    }
                                    LockSupport.parkNanos(minOf(remaining, 100_000_000L))
                                    synchronized(terminalLock) {
                                        if (boundaryWaiter === Thread.currentThread()) boundaryWaiter = null
                                        if (stopped.get() || terminalReserved || breakStream) throw InterruptedException()
                                    }
                                }
                            }
                            reconnectBudget?.check()
                            if (reconnectBudget != null && pauseRequested) awaitPause()
                            synchronized(terminalLock) {
                                if (stopped.get() || terminalReserved || breakStream) throw InterruptedException()
                                reasoningInterruptedAtNanos = null
                                setPhase(ExecutionPhase.STREAMING, attempt)
                                attemptFence = pauseEpoch
                            }
                            request = if (replayRequest) RequestCheckpoint(UUID.randomUUID().toString(),
                                UUID.randomUUID().toString(), owner, RequestOperation.NEXT)
                            else checkNotNull(request).copy(attemptId = UUID.randomUUID().toString(), operation = RequestOperation.RESUME)
                            usageRequests = if (replayRequest) usageRequests + checkNotNull(request)
                                else usageRequests.map { if (it.requestId == request?.requestId) checkNotNull(request) else it }
                            contextState = contextState?.let { state ->
                                val resumed = checkNotNull(request)
                                fun anchor(value: RequestInputCheckpoint?): RequestInputCheckpoint? = value?.let {
                                    if (it.request.owner == resumed.owner && it.request.requestId == resumed.requestId)
                                        it.copy(request = resumed) else it
                                }
                                state.copy(input = anchor(state.input), calibration = anchor(state.calibration),
                                    pressure = state.pressure?.let {
                                        if (it.owner == resumed.owner && it.requestId == resumed.requestId) resumed else it
                                    })
                            }
                            // Persist the resumed request boundary before invoking transport again.
                            checkpoint()
                        }
                    }
                } catch (failure: Exception) { transportFailure = failure }
                finally {
                    try { reconnectBudget?.finish(if (stopped.get()) "stopped" else if (transportFailure != null) "failed" else "succeeded") }
                    catch (failure: Exception) { if (transportFailure == null) transportFailure = failure }
                    finally { reconnectBudget = null; transportInterruption = null }
                    // Fence after all currently executing callbacks, not merely after the
                    // transport returns. Late callbacks then observe accepting=false/token mismatch.
                    synchronized(eventLock) {
                        accepting = false
                        segmentHistory.finishReasoning()
                        current = segmentHistory.project(current)
                        synchronized(terminalLock) { generation++ }
                    }
                }
                sinkFailure?.let { throw it }
                if (stopped.get()) throw InterruptedException()
                repetitiveSurface?.let { throw RepetitiveOutputException(it) }
                if (breakStream) {
                    // A cancelled stream never confirms pending tools. Only accepted text is retained.
                    cancelSession(active)
                    boundary()
                    active = open()
                    continue
                }
                transportFailure?.let { throw it }
                requestUnconfirmed = false
                round++
                synchronized(terminalLock) { setPhase(ExecutionPhase.BOUNDARY) }
                checkpoint()
                require(pending.none { it.name == "ask_user" } || pending.size == 1) { "ask_user必须独占工具批次" }
                options.validateBatch(pending.toList())
                if (pending.any { it.name == "ask_user" }) {
                    check(allowBarrier) { "ask_user需要startSlice" }
                    check(options.receiptBatchExecutor != null && options.runtimeStore is AtomicRuntimeStore &&
                        options.runtimeStore.durable) { "ask_user需要持久原子检查点和receipt执行器" }
                }
                if (pending.isEmpty()) {
                    if (boundary()) { cancelSession(active); active = open(); continue }
                    var completed = false
                    while (true) {
                        compactAtBoundary()
                        val decision = synchronized(terminalLock) {
                            if (stopped.get() || terminalReserved) throw InterruptedException()
                            when {
                                pauseRequested || supplements.isNotEmpty() || pendingSupplements.isNotEmpty() || breakStream -> 0
                                pendingCompact != null -> 1
                                else -> { terminalReserved = true; 2 }
                            }
                        }
                        if (decision == 1) continue
                        completed = decision == 2
                        break
                    }
                    if (completed) {
                        apply(current.copy(status = RunStatus.COMPLETED))
                        return current
                    }
                    continue
                }
                // Pause/supplement requests during this batch wait until ALL calls finish.
                synchronized(terminalLock) { setPhase(ExecutionPhase.TOOL_EXECUTING) }
                checkpoint()
                val batch = options.batchExecutor
                val receiptBatch = options.receiptBatchExecutor
                if (receiptBatch != null) {
                    pending.forEach { records[it.id] = ToolRecord(it, ToolDisposition.IN_FLIGHT) }
                    checkpoint()
                    if (stopped.get() || terminalReserved) throw InterruptedException()
                    val receipts = try {
                        val start = if (allowBarrier) {
                            receiptBatch.start(current, pending.toList(), stopped::get)
                        } else {
                            ReceiptBatchStart.Completed(receiptBatch.execute(current, pending.toList(), stopped::get))
                        }
                        val completed = when (start) {
                            is ReceiptBatchStart.Completed -> start.receipts
                            is ReceiptBatchStart.Suspended -> {
                                check(allowBarrier)
                                val offer = start.offer
                                val call = pending.single()
                                check(call.name == "ask_user" && offer.context.owner === executionOwner &&
                                    executionOwner.owns(current) && offer.context.invocation === call &&
                                    offer.pending.callId == call.id && !offer.waitingCommitted &&
                                    records[call.id]?.disposition == ToolDisposition.IN_FLIGHT) { "barrier身份不匹配" }
                                val receipt = awaitBarrier(offer)
                                if (stopped.get() || terminalReserved) throw InterruptedException()
                                val finished = offer.finish(receipt)
                                pendingCheckpoint = null
                                setPhase(ExecutionPhase.TOOL_EXECUTING)
                                listOf(finished)
                            }
                        }
                        completed.also {
                            check(it.map { receipt -> receipt.callId } == pending.map { call -> call.id }) {
                                "批次结果身份或顺序错误"
                            }
                        }
                    } catch (failure: Exception) {
                        if (failure is PersistenceException || failure is InterruptedException) throw failure
                        pendingCheckpoint = null
                        pending.forEach {
                            records[it.id] = ToolRecord(it, ToolDisposition.UNKNOWN)
                            unknownOperations.add(it.name to it.arguments)
                        }
                        checkpoint()
                        throw UnknownToolOutcomeException()
                    }
                    val known = linkedMapOf<ToolCallId, String>()
                    var uncertain = false
                    pending.zip(receipts).forEach { (call, receipt) ->
                        when (val outcome = receipt.outcome) {
                            is DispatchOutcome.Confirmed -> {
                                records[call.id] = ToolRecord(call,
                                    if (outcome.isError) ToolDisposition.FAILED else ToolDisposition.SUCCEEDED,
                                    outcome.payload)
                                known[call.id] = outcome.payload
                            }
                            is DispatchOutcome.NotExecuted -> {
                                val result = "{\"ok\":false,\"executed\":false,\"error\":" + jsonQuote(outcome.code) + "}"
                                records[call.id] = ToolRecord(call, ToolDisposition.NOT_EXECUTED, result)
                                known[call.id] = result
                            }
                            is DispatchOutcome.Unknown -> {
                                records[call.id] = ToolRecord(call, ToolDisposition.UNKNOWN)
                                unknownOperations.add(call.name to call.arguments)
                                uncertain = true
                            }
                        }
                    }
                    results.putAll(known)
                    outputs = known.map { ToolOutput(it.key, it.value) }
                    apply(current.copy(blocks = current.blocks.map {
                        if (it is MessageBlock.Tool && it.invocation.id in known)
                            it.copy(result = known.getValue(it.invocation.id)) else it
                    }))
                    if (uncertain) throw UnknownToolOutcomeException()
                } else if (batch != null) {
                    pending.forEach { records[it.id] = ToolRecord(it, ToolDisposition.IN_FLIGHT) }
                    checkpoint()
                    if (stopped.get() || terminalReserved) throw InterruptedException()
                    val batchOutputs = try { batch.execute(current, pending.toList()) } catch (_: Exception) {
                        pending.forEach {
                            records[it.id] = ToolRecord(it, ToolDisposition.UNKNOWN)
                            unknownOperations.add(it.name to it.arguments)
                        }
                        checkpoint()
                        throw UnknownToolOutcomeException()
                    }
                    check(batchOutputs.map { it.callId } == pending.map { it.id }) { "批次结果身份或顺序错误" }
                    outputs = batchOutputs
                    val byId = batchOutputs.associate { it.callId to it.output }
                    pending.forEach {
                        val result = byId.getValue(it.id)
                        records[it.id] = ToolRecord(it, ToolDisposition.SUCCEEDED, result)
                        results[it.id] = result
                    }
                    apply(current.copy(blocks = current.blocks.map {
                        if (it is MessageBlock.Tool && it.invocation.id in byId) it.copy(result = byId[it.invocation.id]) else it
                    }))
                } else outputs = pending.map { call ->
                    if (stopped.get()) throw InterruptedException()
                    val key = call.name to call.arguments
                    check(key !in unknownOperations) { "未知操作禁止等价重放" }
                    val sideEffect = (tool as? CatalogTool)?.definitions?.firstOrNull { it.name == call.name }?.sideEffect == true
                    if (sideEffect) check(options.toolJournal?.durable == true) { "副作用工具需要持久执行日志" }
                    val result = results.getOrPut(call.id) {
                        val claim = options.toolJournal?.claim(current, call) ?: ToolClaim.Execute
                        when (claim) {
                            is ToolClaim.Cached -> claim.result
                            ToolClaim.Unknown -> {
                                unknownOperations.add(key)
                                records[call.id] = ToolRecord(call, ToolDisposition.UNKNOWN)
                                checkpoint()
                                throw UnknownToolOutcomeException()
                            }
                            ToolClaim.Execute -> {
                                records[call.id] = ToolRecord(call, ToolDisposition.IN_FLIGHT)
                                checkpoint() // Must commit before crossing the tool boundary.
                                try {
                                    if (stopped.get()) throw InterruptedException()
                                    val value = tool.execute(call)
                                    options.toolJournal?.complete(current, call, value)
                                    value
                                } catch (failure: Exception) {
                                    unknownOperations.add(key)
                                    records[call.id] = ToolRecord(call, ToolDisposition.UNKNOWN)
                                    checkpoint()
                                    throw if (failure is InterruptedException) failure else UnknownToolOutcomeException()
                                }
                            }
                        }
                    }
                    records[call.id] = ToolRecord(call, ToolDisposition.SUCCEEDED, result)
                    // Persist the result before handing it back to a model.
                    apply(current.copy(blocks = current.blocks.map {
                        if (it is MessageBlock.Tool && it.invocation.id == call.id) it.copy(result = result) else it
                    }))
                    ToolOutput(call.id, result)
                }
                if (contextRuntime != null && compactAtBoundary()) {
                    cancelSession(active); active = open(); reopenAfterCompaction = false
                } else checkpoint()
            }
            throw IllegalStateException("工具续轮达到12轮上限，已停止追加请求")
        } catch (failure: Exception) {
            if (allowBarrier && failure is PersistenceException) {
                synchronized(terminalLock) {
                    terminalReserved = true
                    terminal = true
                    setPhase(ExecutionPhase.TERMINAL)
                }
                throw failure
            }
            pendingCheckpoint = null
            records.entries.forEach { entry ->
                if (entry.value.disposition == ToolDisposition.IN_FLIGHT) {
                    entry.setValue(entry.value.copy(disposition = ToolDisposition.UNKNOWN))
                }
            }
            val terminalInfo = synchronized(terminalLock) {
                // A reservation is only an in-memory ordering decision. Its durable outcome must
                // be read back after a failed terminal commit; it is never proof of COMPLETED.
                val reserved = terminalReserved
                terminalReserved = true
                terminal = true
                setPhase(ExecutionPhase.TERMINAL)
                val outcome = if (stopped.get()) RunStatus.STOPPED
                else if (reserved) RunStatus.COMPLETED
                else RunStatus.FAILED
                Pair(reserved, outcome)
            }
            val wasReserved = terminalInfo.first
            val status = terminalInfo.second
            val baseError = if (failure is PersistenceException) "保存失败，已显示内容可能未落盘" else
                if (failure is UnknownToolOutcomeException) "工具结果未知；已停止，禁止重放，请仅使用只读方式核实" else
                if (failure is TransportReconnectDeadlineException) failure.userMessage else
                if (failure is RepetitiveOutputException) "检测到模型${failure.surface}持续高度重复，已中止本次请求；已接收内容和工具结果已保留" else if (failure is ModelRequestException) failure.userMessage else "请求失败，请检查模型设置或网络"
            val next = current.copy(status = status, error = if (status == RunStatus.STOPPED || status == RunStatus.COMPLETED) null else baseError)
            val runtime = options.runtimeStore
            val terminalCommitWasAttempted = wasReserved && status == RunStatus.COMPLETED
            // A terminal write may have succeeded before throwing. Never replace it with
            // a second unconditional terminal write, including on legacy stores.
            if (!terminalCommitWasAttempted && !(runtime is AtomicRuntimeStore && failure is PersistenceException)) {
                try {
                    apply(next)
                    return current
                } catch (saveFailure: Exception) {
                    if (allowBarrier && saveFailure is PersistenceException) throw saveFailure
                    /* A failed terminal receipt is confirmed only by reading. */
                }
            }
            if (runtime is AtomicRuntimeStore) {
                // Read only: a live owner's uncertain receipt never authorizes process recovery.
                // Do not issue a second unconditional terminal commit.
                try {
                    val committed = runtime.readCommittedCheckpoint(snapshot(next))
                    if (committed != null) {
                        val actual = committed.message
                        check(actual.id == current.id && actual.conversationId == current.conversationId &&
                            actual.runId == current.runId && committed.owner == owner &&
                            committed.revision in 1..(revision + 1))
                        check((committed.phase == ExecutionPhase.TERMINAL) == (actual.status != RunStatus.RUNNING))
                        current = actual
                        if (committed.phase == ExecutionPhase.TERMINAL) {
                            try { publish(current) } catch (_: Exception) { }
                            return current
                        }
                    }
                } catch (_: Exception) { /* No write or recovery follows an uncertain receipt. */ }
            }
            val persistenceError = if (runtime !is AtomicRuntimeStore) "保存失败，已显示内容可能未落盘"
            else "保存失败，无法确认终态是否已落盘；未重发请求或工具"
            // In particular, a reserved COMPLETED state is not returned as success unless the
            // atomic reconciliation read actually confirms it.
            current = current.copy(status = if (status == RunStatus.COMPLETED) RunStatus.FAILED else status,
                error = persistenceError)
            try { publish(current) } catch (_: Exception) { }
            return current
        } finally {
            val toCancel = synchronized(terminalLock) {
                val captured = session
                session = null
                captured
            }
            cancelSession(toCancel)
            val rejected = synchronized(terminalLock) {
                pendingSupplements.toList().also { pendingSupplements.clear() }
            }
            rejected.forEach { completeAdmission(it, SupplementAdmission.Rejected(it.message.id)) }
            val abandoned = synchronized(terminalLock) { pendingCompact.also { pendingCompact = null } }
            try { abandoned?.completed?.invoke(false, "运行已结束；未执行压缩，原始上下文保留") } catch (_: Exception) { }
        }
    }
}
class PersistenceException(cause: Throwable) : RuntimeException("Conversation persistence failed", cause)

private fun cancelSession(session: ModelSession?) {
    if (session == null) return
    // Cancellation is transport cleanup, not control flow. A broken provider is allowed to
    // block here without delaying stop/pause/supplement or the runner's terminal projection.
    try {
        Thread {
            try { session.cancel() } catch (_: Exception) { }
        }.apply {
            isDaemon = true
            name = "chat-runner-cancel"
        }.start()
    } catch (_: Exception) { }
}

private class RepetitiveOutputException(val surface: String) : RuntimeException("Repetitive model output")
private class UnknownToolOutcomeException : RuntimeException("Unknown tool outcome")
