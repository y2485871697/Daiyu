package app.daiyu.android.domain

import java.util.concurrent.atomic.AtomicBoolean
import app.daiyu.android.domain.runtime.*

/** One owner thread applies events. Cancellation only signals and closes transport. */
class ChatRunner private constructor(private val model: ModelPort, private val store: ConversationStore,
    private val tool: BatteryTool, private val publish: (Message) -> Unit,
    private val options: RunnerOptions) {
    constructor(model: ModelPort, store: ConversationStore, tool: BatteryTool, publish: (Message) -> Unit) :
        this(model, store, tool, publish, RunnerOptions())
    constructor(model: ModelPort, store: ConversationStore, tool: BatteryTool, options: RunnerOptions,
        publish: (Message) -> Unit) : this(model, store, tool, publish, options)
    private val stopped = AtomicBoolean(false)
    private val terminalLock = Object()
    private var terminal = false
    private var started = false
    private var pauseRequested = false
    @Volatile private var breakStream = false
    private var generation = 0L
    private val supplements = mutableListOf<Message>()
    private val supplementIds = mutableSetOf<MessageId>()
    @Volatile private var execution = ExecutionState()
    @Volatile private var session: ModelSession? = null
    @Volatile private var identity: Message? = null
    fun state(): ExecutionState = execution
    fun stop() {
        synchronized(terminalLock) {
            if (terminal) return
            stopped.set(true); generation++; terminalLock.notifyAll()
        }
        cancelSession(session)
    }
    /** Nonblocking control call. A tool already executing is allowed to finish its batch. */
    fun pause(): Boolean {
        val close = synchronized(terminalLock) {
            if (terminal || stopped.get()) return false
            pauseRequested = true
            execution = execution.copy(pauseRequested = true)
            if (execution.phase == ExecutionPhase.STREAMING || execution.phase == ExecutionPhase.RECONNECTING) {
                breakStream = true; generation++; true
            } else false
        }
        if (close) cancelSession(session)
        return true
    }
    /** Only explicit resume releases a paused boundary; supplements alone never do. */
    fun resume(): Boolean = synchronized(terminalLock) {
        if (terminal || stopped.get() || !pauseRequested) return false
        pauseRequested = false
        execution = execution.copy(pauseRequested = false)
        terminalLock.notifyAll(); true
    }
    /** Caller persists the user message first, on its own writer. Identity checked, not re-saved here. */
    fun supplement(message: Message): Boolean {
        val close = synchronized(terminalLock) {
            val owner = identity ?: return false
            if (terminal || stopped.get() || message.role != "user" || message.status != RunStatus.COMPLETED ||
                message.conversationId != owner.conversationId || message.runId != owner.runId ||
                message.id in supplementIds) return false
            supplementIds.add(message.id)
            supplements.add(message)
            execution = execution.copy(queuedSupplements = supplements.size)
            if (!pauseRequested && (execution.phase == ExecutionPhase.STREAMING || execution.phase == ExecutionPhase.RECONNECTING)) {
                breakStream = true; generation++; true
            } else false
        }
        if (close) cancelSession(session)
        return true
    }
    /** Public recovery entry point; never opens a model or executes a tool. */
    fun recover(checkpoint: RunCheckpoint): Message {
        val runtime = requireNotNull(options.runtimeStore) { "恢复需要检查点存储" }
        return ProcessRecovery.recover(checkpoint, store, runtime, publish).message
    }
    private fun setPhase(phase: ExecutionPhase, attempt: Int = 0) {
        execution = ExecutionState(phase, pauseRequested, supplements.size, attempt)
    }
    fun run(initial: Message, history: List<Message>): Message {
        require(initial.status == RunStatus.RUNNING && initial.role == "assistant")
        synchronized(terminalLock) { check(!started) { "Runner只能启动一次" }; started = true; identity = initial }
        var current = initial
        var modelHistory = history.toList()
        var outputs = emptyList<ToolOutput>()
        var round = 0
        var requestUnconfirmed = false
        var historyBlockStart = 0
        val owner = java.util.UUID.randomUUID().toString()
        var revision = 0L
        val records = linkedMapOf<ToolCallId, ToolRecord>()
        fun snapshot(message: Message = current): RunCheckpoint = synchronized(terminalLock) {
            RunCheckpoint(message, modelHistory,
                if (message.status != RunStatus.RUNNING) ExecutionPhase.TERMINAL else execution.phase,
                records.values.toList(), outputs, supplements.toList(), round, requestUnconfirmed,
                owner = owner, revision = revision + 1)
        }
        fun checkpoint() {
            val runtime = options.runtimeStore ?: return
            try {
                val next = snapshot()
                if (runtime is AtomicRuntimeStore) runtime.commitCheckpoint(next) else runtime.save(next)
                revision = next.revision
            } catch (failure: Exception) { throw PersistenceException(failure) }
        }
        fun apply(next: Message) {
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
        fun open(): ModelSession {
            requestUnconfirmed = true
            checkpoint() // open may itself send a request; persist uncertainty first.
            return if (tool is CatalogTool) model.open(modelHistory, tool.definitions) else model.open(modelHistory)
        }
        fun boundary(): Boolean {
            synchronized(terminalLock) {
                setPhase(if (pauseRequested) ExecutionPhase.PAUSED else ExecutionPhase.BOUNDARY)
                checkpoint()
                while (pauseRequested && !stopped.get()) terminalLock.wait()
                if (stopped.get()) throw InterruptedException()
                val reopen = breakStream || supplements.isNotEmpty()
                if (reopen) {
                    val accepted = current.copy(status = RunStatus.COMPLETED, error = null, protocolItems = emptyList(),
                        blocks = current.blocks.drop(historyBlockStart).filter { it !is MessageBlock.Tool || it.result != null })
                    modelHistory = modelHistory + accepted + supplements
                    historyBlockStart = current.blocks.size
                    supplements.clear(); outputs = emptyList(); breakStream = false
                }
                setPhase(ExecutionPhase.BOUNDARY)
                checkpoint()
                return reopen
            }
        }
        try {
            boundary()
            var active = open()
            session = active
            if (stopped.get()) { cancelSession(active); throw InterruptedException() }
            while (round < options.maxRounds) {
                if (boundary()) { cancelSession(active); active = open(); session = active }
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
                val token = synchronized(terminalLock) { setPhase(ExecutionPhase.STREAMING); ++generation }
                requestUnconfirmed = true
                checkpoint()
                val sink: (ModelEvent) -> Unit = { event ->
                    synchronized(terminalLock) {
                    if (!accepting || token != generation || terminal || stopped.get()) return@synchronized
                    try {
                    sinkFailure?.let { throw it }
                    repetitiveSurface?.let { throw RepetitiveOutputException(it) }
                    when (event) {
                        is ModelEvent.Usage -> options.onUsage(event)
                        is ModelEvent.ReasoningDelta -> { checkText(event.itemId, event.text, final = false, reasoning = true) }
                        is ModelEvent.ReasoningFinal -> { checkText(event.itemId, event.text, final = true, reasoning = true) }
                        is ModelEvent.ProtocolOutput -> apply(current.copy(protocolItems = event.items))
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
                            if (attempt == 0) active.next(outputs, sink)
                            else (active as ResumableModelSession).resume(sink)
                            break
                        } catch (failure: Exception) {
                            if (stopped.get() || breakStream || sinkFailure != null) throw failure
                            val resumable = active as? ResumableModelSession
                            val classified = (failure as? ModelRequestException)?.failure
                            if (attempt >= options.reconnect.maxAttempts || classified !in options.reconnect.failures ||
                                resumable?.canResume != true) throw failure
                            synchronized(terminalLock) {
                                setPhase(ExecutionPhase.RECONNECTING, ++attempt)
                                checkpoint()
                                if (options.reconnect.delayMillis > 0) terminalLock.wait(options.reconnect.delayMillis)
                                if (stopped.get() || breakStream) throw InterruptedException()
                                setPhase(ExecutionPhase.STREAMING, attempt)
                            }
                        }
                    }
                } catch (failure: Exception) { transportFailure = failure }
                finally { synchronized(terminalLock) { accepting = false; generation++ } }
                if (stopped.get()) throw InterruptedException()
                sinkFailure?.let { throw it }
                repetitiveSurface?.let { throw RepetitiveOutputException(it) }
                if (breakStream) {
                    // A cancelled stream never confirms pending tools. Only accepted text is retained.
                    cancelSession(active)
                    boundary()
                    active = open(); session = active
                    continue
                }
                transportFailure?.let { throw it }
                requestUnconfirmed = false
                round++
                synchronized(terminalLock) { setPhase(ExecutionPhase.BOUNDARY) }
                checkpoint()
                require(pending.none { it.name == "ask_user" } || pending.size == 1) { "ask_user必须独占工具批次" }
                options.validateBatch(pending.toList())
                if (pending.isEmpty()) {
                    if (boundary()) { cancelSession(active); active = open(); session = active; continue }
                    val completed = synchronized(terminalLock) {
                        if (stopped.get()) throw InterruptedException()
                        if (pauseRequested || supplements.isNotEmpty() || breakStream) false
                        else { apply(current.copy(status = RunStatus.COMPLETED)); true }
                    }
                    if (completed) return current
                    continue
                }
                // Pause/supplement requests during this batch wait until ALL calls finish.
                synchronized(terminalLock) { setPhase(ExecutionPhase.TOOL_EXECUTING) }
                checkpoint()
                val batch = options.batchExecutor
                if (batch != null) {
                    pending.forEach { records[it.id] = ToolRecord(it, ToolDisposition.IN_FLIGHT) }
                    checkpoint()
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
            val status = synchronized(terminalLock) {
                terminal = true; setPhase(ExecutionPhase.TERMINAL)
                if (stopped.get()) RunStatus.STOPPED else RunStatus.FAILED
            }
            val next = current.copy(status = status, error = if (status == RunStatus.STOPPED) null else
                if (failure is PersistenceException) "保存失败，已显示内容可能未落盘" else
                if (failure is UnknownToolOutcomeException) "工具结果未知；已停止，禁止重放，请仅使用只读方式核实" else
                if (failure is RepetitiveOutputException) "检测到模型${failure.surface}持续高度重复，已中止本次请求；已接收内容和工具结果已保留" else if (failure is ModelRequestException) failure.failure.userMessage else "请求失败，请检查模型设置或网络")
            try { apply(next) } catch (_: Exception) {
                current = next.copy(error = "保存失败，已显示内容可能未落盘")
                try { publish(current) } catch (_: Exception) { }
            }
            return current
        } finally { cancelSession(session); session = null }
    }
}
class PersistenceException(cause: Throwable) : RuntimeException("Conversation persistence failed", cause)

private fun cancelSession(session: ModelSession?) {
    try { session?.cancel() } catch (_: Exception) { }
}

private class RepetitiveOutputException(val surface: String) : RuntimeException("Repetitive model output")
private class UnknownToolOutcomeException : RuntimeException("Unknown tool outcome")
