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
    @Volatile private var pauseRequested = false
    @Volatile private var breakStream = false
    @Volatile private var generation = 0L
    @Volatile private var boundaryWaiter: Thread? = null
    @Volatile private var requestOpening = false
    private val supplements = mutableListOf<Message>()
    private val supplementIds = mutableSetOf<MessageId>()
    private val sourceMessageIds = mutableSetOf<MessageId>()
    private data class PendingSupplement(val message: Message, val complete: (SupplementAdmission) -> Unit)
    private val pendingSupplements = java.util.ArrayDeque<PendingSupplement>()
    @Volatile private var execution = ExecutionState()
    @Volatile private var session: ModelSession? = null
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
            generation++
            Pair(session, boundaryWaiter)
        }
        captured.second?.let { LockSupport.unpark(it) }
        cancelSession(captured.first)
    }
    /** Nonblocking control call. A tool already executing is allowed to finish its batch. */
    fun pause(): Boolean {
        val captured = synchronized(terminalLock) {
            if (terminal || terminalReserved || stopped.get()) return false
            pauseRequested = true
            execution = execution.copy(pauseRequested = true)
            if (requestOpening || execution.phase == ExecutionPhase.STREAMING || execution.phase == ExecutionPhase.RECONNECTING) {
                breakStream = true
                generation++
                Pair(session, boundaryWaiter)
            } else Pair(null, boundaryWaiter)
        }
        captured.second?.let { LockSupport.unpark(it) }
        cancelSession(captured.first)
        return true
    }
    /** Only explicit resume releases a paused boundary; supplements alone never do. */
    fun resume(): Boolean {
        val waiter = synchronized(terminalLock) {
            if (terminal || terminalReserved || stopped.get() || !pauseRequested) return false
            pauseRequested = false
            execution = execution.copy(pauseRequested = false)
            boundaryWaiter
        }
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
    /** Proposes a text supplement at a confirmed PAUSED boundary. True only means queued;
     * the callback confirms durable admission. Does not resume or consume a draft. */
    fun admitSupplement(message: Message, complete: (SupplementAdmission) -> Unit): Boolean {
        val frozen = message.copy(blocks = message.blocks.toList(), protocolItems = message.protocolItems.toList(),
            historySegments = message.historySegments.toList())
        val pending = PendingSupplement(frozen, complete)
        val accepted = synchronized(terminalLock) {
            val owner = identity
            if (owner == null || terminal || terminalReserved || stopped.get() || !pauseRequested ||
                execution.phase != ExecutionPhase.PAUSED || options.runtimeStore !is AtomicSupplementRuntimeStore ||
                !options.runtimeStore.durable || frozen.role != Message.USER_ROLE || frozen.status != RunStatus.COMPLETED ||
                frozen.conversationId != owner.conversationId || frozen.runId != owner.runId || frozen.id == owner.id ||
                frozen.error != null || frozen.historySegments.isNotEmpty() || frozen.protocolItems.isNotEmpty() ||
                frozen.blocks.isEmpty() || frozen.blocks.any { it !is MessageBlock.Text } ||
                frozen.blocks.filterIsInstance<MessageBlock.Text>().all { it.text.isBlank() } ||
                frozen.id in supplementIds || frozen.id in sourceMessageIds) false
            else {
                supplementIds.add(frozen.id)
                pendingSupplements.addLast(pending)
                true
            }
        }
        if (!accepted) {
            completeAdmission(pending, SupplementAdmission.Rejected(message.id))
            return false
        }
        synchronized(terminalLock) { boundaryWaiter }?.let { LockSupport.unpark(it) }
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
            if (pauseRequested) ExecutionPhase.PAUSED else ExecutionPhase.BOUNDARY
        } else phase
        execution = ExecutionState(actual, pauseRequested, supplements.size, attempt)
    }
    fun run(initial: Message, history: List<Message>): Message =
        when (val slice = startInternal(initial, history, allowBarrier = false)) {
            is RunSlice.Completed -> slice.message
            is RunSlice.Suspended -> error("同步Runner不能等待用户")
        }

    private suspend fun runBody(initial: Message, history: List<Message>, allowBarrier: Boolean): Message {
        var current = initial
        val sourceHistory = history.toList()
        var modelHistory = sourceHistory
        val segmentHistory = RunnerHistorySegments(initial)
        var outputs = emptyList<ToolOutput>()
        var round = 0
        var requestUnconfirmed = false
        var request: RequestCheckpoint? = null
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
            RunCheckpoint(message, modelHistory,
                if (message.status != RunStatus.RUNNING) ExecutionPhase.TERMINAL else execution.phase,
                records.values.toList(), outputs, supplements.toList(), round, requestUnconfirmed,
                owner = owner, revision = revision + 1,
                pending = if (message.status == RunStatus.RUNNING) pendingCheckpoint else null, request = request, usage = usageSnapshot, usageRequests = usageRequests)
        }
        fun checkpoint(next: RunCheckpoint = snapshot()) {
            val runtime = options.runtimeStore ?: return
            try {
                if (runtime is AtomicRuntimeStore) runtime.commitCheckpoint(next) else runtime.save(next)
                revision = next.revision
            } catch (failure: Exception) { throw PersistenceException(failure) }
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
            val next = segmentHistory.project(candidate)
            val runtime = options.runtimeStore
            try {
                if (runtime is AtomicRuntimeStore) {
                    val nextCheckpoint = snapshot(next)
                    runtime.commitCheckpoint(nextCheckpoint)
                    revision = nextCheckpoint.revision
                } else store.save(next)
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
                    if (pending != null) false
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
                        completeAdmission(admission, SupplementAdmission.Unconfirmed(admission.message.id))
                        throw PersistenceException(failure)
                    }
                    revision = expected.revision
                    synchronized(terminalLock) {
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
                outputs = emptyList()
                modelHistory = sourceHistory + sealed.copy(status = RunStatus.COMPLETED, error = null)
                apply(sealed)
            }
            setPhase(ExecutionPhase.BOUNDARY)
            checkpoint()
            return acceptedUsers != null
        }
        fun open(): ModelSession {
            while (true) {
                boundary()
                if (!ownerBound) {
                    options.receiptBatchExecutor?.bindOwner(executionOwner)
                    ownerBound = true
                }
                val token = synchronized(terminalLock) {
                    if (stopped.get() || terminalReserved || terminal) throw InterruptedException()
                    if (pauseRequested || supplements.isNotEmpty() || breakStream) null
                    else { requestOpening = true; generation }
                } ?: continue
                request = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(), owner, RequestOperation.OPEN)
                requestUnconfirmed = true
                try {
                    checkpoint() // open may itself send a request; persist uncertainty first.
                    val admitted = synchronized(terminalLock) {
                        if (stopped.get() || terminalReserved || terminal) throw InterruptedException()
                        token == generation && !pauseRequested && supplements.isEmpty() && !breakStream
                    }
                    if (!admitted) continue
                    val opened = try {
                        if (tool is CatalogTool) model.open(modelHistory, tool.definitions) else model.open(modelHistory)
                    } catch (failure: Exception) {
                        val invalidated = synchronized(terminalLock) {
                            if (stopped.get() || terminalReserved || terminal) throw InterruptedException()
                            token != generation || pauseRequested || supplements.isNotEmpty() || breakStream
                        }
                        if (invalidated) continue
                        throw failure
                    }
                    val accept = synchronized(terminalLock) {
                        if (stopped.get() || terminalReserved || terminal || token != generation ||
                            pauseRequested || supplements.isNotEmpty() || breakStream) false
                        else { session = opened; true }
                    }
                    if (accept) return opened
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
                if (stopped.get()) throw InterruptedException()
                val pending = mutableListOf<ToolInvocation>()
                val repetition = ReasoningRepetitionGuard()
                val reasoningRepetition = ReasoningRepetitionGuard()
                val responseText = linkedMapOf<String, String>()
                val reasoningText = linkedMapOf<String, String>()
                var reasoningBaseline: Map<String, String> = emptyMap()
                var repetitiveSurface: String? = null
                fun checkText(itemId: String, text: String, final: Boolean, reasoning: Boolean = false): Boolean {
                    val texts = if (reasoning) reasoningText else responseText
                    val guard = if (reasoning) reasoningRepetition else repetition
                    val previous = texts[itemId].orEmpty()
                    val next = if (final) text else previous + text
                    fun uncounted(id: String, value: String): String {
                        val baseline = if (reasoning) reasoningBaseline[id].orEmpty() else ""
                        return value.substring(value.commonPrefixWith(baseline).length)
                    }
                    val before = uncounted(itemId, previous)
                    val after = uncounted(itemId, next)
                    val repetitive = if (after.startsWith(before)) {
                        guard.append(after.substring(before.length))
                    } else {
                        guard.reset()
                        val corrected = LinkedHashMap(texts)
                        corrected[itemId] = next
                        corrected.any { (id, value) -> guard.append(uncounted(id, value)) }
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
                        reasoningRepetition.reset()
                        reasoningBaseline = reasoningText.toMap()
                    }
                }
                var textFloor = current.blocks.size
                var sinkFailure: Exception? = null
                var accepting = true
                val token = synchronized(terminalLock) {
                    if (stopped.get() || terminalReserved || terminal) throw InterruptedException()
                    if (pauseRequested || supplements.isNotEmpty() || breakStream) null
                    else { setPhase(ExecutionPhase.STREAMING); ++generation }
                }
                if (token == null) {
                    if (boundary()) { cancelSession(active); active = open() }
                    continue
                }
                request = RequestCheckpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString(), owner, RequestOperation.NEXT)
                usageRequests = usageRequests + checkNotNull(request)
                requestUnconfirmed = true
                checkpoint()
                val sink: (ModelEvent) -> Unit = { event ->
                    // Event callbacks are serialized independently of control calls. In particular,
                    // no persistence or observer callback below runs while terminalLock is held.
                    synchronized(eventLock) {
                    if (!accepting || token != generation || terminal || terminalReserved || stopped.get()) return@synchronized
                    try {
                    sinkFailure?.let { throw it }
                    repetitiveSurface?.let { throw RepetitiveOutputException(it) }
                    when (event) {
                        is ModelEvent.Usage -> {
                            val accepted = RequestUsageCheckpoint(checkNotNull(request), event)
                            val at = usage.indexOfFirst { it.request.owner == accepted.request.owner &&
                                it.request.requestId == accepted.request.requestId }
                            val nextUsage = usage.toMutableList().apply {
                                if (at < 0) add(accepted) else this[at] = accepted
                            }.toList()
                            checkpoint(snapshot(usageSnapshot = nextUsage))
                            usage = nextUsage
                            try { options.onUsage(event) } catch (_: Exception) { /* Observer failure cannot invalidate committed usage. */ }
                        }
                        is ModelEvent.ReasoningDelta -> {
                            checkText(event.itemId, event.text, final = false, reasoning = true)
                            segmentHistory.acceptReasoning(event.itemId, event.text, final = false)
                            apply(current)
                            try { options.onReasoning(event) } catch (_: Exception) { /* Observer failure cannot invalidate accepted reasoning. */ }
                        }
                        is ModelEvent.ReasoningFinal -> {
                            checkText(event.itemId, event.text, final = true, reasoning = true)
                            segmentHistory.acceptReasoning(event.itemId, event.text, final = true)
                            apply(current)
                            try { options.onReasoning(event) } catch (_: Exception) { /* Observer failure cannot invalidate accepted reasoning. */ }
                        }
                        is ModelEvent.ProtocolOutput -> {
                            segmentHistory.replaceSessionProtocol(current, event.items)
                            apply(current.copy(protocolItems = event.items))
                        }
                        is ModelEvent.TextDelta -> {
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
                            bodyProgress(checkText(event.itemId, event.text, final = true))
                            val blocks = current.blocks.toMutableList()
                            val at = blocks.indices.firstOrNull { it >= textFloor && blocks[it] is MessageBlock.Text && (blocks[it] as MessageBlock.Text).itemId == event.itemId } ?: -1
                            val text = MessageBlock.Text(event.itemId, event.text)
                            if (at < 0) blocks.add(text) else blocks[at] = text
                            apply(current.copy(blocks = blocks))
                        }
                        is ModelEvent.ToolReady -> {
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
                    } catch (failure: Exception) { sinkFailure = failure; throw failure }
                    }
                }
                var transportFailure: Exception? = null
                try {
                    var attempt = 0
                    while (true) {
                        try {
                            // The admission check is deliberately after the uncertainty
                            // checkpoint: a stop during that write must not open the request.
                            synchronized(terminalLock) {
                                if (stopped.get() || terminalReserved || terminal || token != generation) {
                                    throw InterruptedException()
                                }
                            }
                            val delivery = ProviderEventDeliveryGate()
                            try {
                                val attemptSink: (ModelEvent) -> Unit = { event -> delivery.deliver { sink(event) } }
                                if (attempt == 0) active.next(outputs, attemptSink)
                                else (active as ResumableModelSession).resume(attemptSink)
                            } finally {
                                // Drain the old attempt outside Runner locks before reconnecting.
                                delivery.close()
                            }
                            break
                        } catch (failure: Exception) {
                            if (stopped.get() || breakStream || sinkFailure != null) throw failure
                            val resumable = active as? ResumableModelSession
                            val classified = (failure as? ModelRequestException)?.failure
                            if (attempt >= options.reconnect.maxAttempts || classified !in options.reconnect.failures ||
                                resumable?.canResume != true) throw failure
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
                                    val remaining = deadline - System.nanoTime()
                                    if (remaining <= 0L) break
                                    synchronized(terminalLock) {
                                        if (stopped.get() || terminalReserved || breakStream) throw InterruptedException()
                                        boundaryWaiter = Thread.currentThread()
                                    }
                                    LockSupport.parkNanos(remaining)
                                    synchronized(terminalLock) {
                                        if (boundaryWaiter === Thread.currentThread()) boundaryWaiter = null
                                        if (stopped.get() || terminalReserved || breakStream) throw InterruptedException()
                                    }
                                }
                            }
                            synchronized(terminalLock) {
                                if (stopped.get() || terminalReserved || breakStream) throw InterruptedException()
                                setPhase(ExecutionPhase.STREAMING, attempt)
                            }
                            request = checkNotNull(request).copy(attemptId = UUID.randomUUID().toString(), operation = RequestOperation.RESUME)
                            usageRequests = usageRequests.map { if (it.requestId == request?.requestId) checkNotNull(request) else it }
                            // Persist the resumed request boundary before invoking transport again.
                            checkpoint()
                        }
                    }
                } catch (failure: Exception) { transportFailure = failure }
                finally {
                    // Fence after all currently executing callbacks, not merely after the
                    // transport returns. Late callbacks then observe accepting=false/token mismatch.
                    synchronized(eventLock) {
                        accepting = false
                        synchronized(terminalLock) { generation++ }
                    }
                }
                if (stopped.get()) throw InterruptedException()
                sinkFailure?.let { throw it }
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
                    // Reserve normal completion before leaving the control section. stop() then
                    // cannot race this terminal outcome, while the actual transaction stays outside.
                    val completed = synchronized(terminalLock) {
                        if (stopped.get() || terminalReserved) throw InterruptedException()
                        if (pauseRequested || supplements.isNotEmpty() || breakStream) false
                        else { terminalReserved = true; true }
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
                checkpoint()
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
                if (failure is RepetitiveOutputException) "检测到模型${failure.surface}持续高度重复，已中止本次请求；已接收内容和工具结果已保留" else if (failure is ModelRequestException) failure.failure.userMessage else "请求失败，请检查模型设置或网络"
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
