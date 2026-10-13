package app.daiyu.android.domain

import java.util.concurrent.atomic.AtomicBoolean

/** One owner thread applies events. Cancellation only signals and closes transport. */
class ChatRunner(private val model: ModelPort, private val store: ConversationStore,
    private val tool: BatteryTool, private val publish: (Message) -> Unit) {
    private val stopped = AtomicBoolean(false)
    private val terminalLock = Any()
    private var terminal = false
    @Volatile private var session: ModelSession? = null
    fun stop() { synchronized(terminalLock) { if (terminal) return; stopped.set(true) }; cancelSession(session) }
    fun run(initial: Message, history: List<Message>): Message {
        require(initial.status == RunStatus.RUNNING && initial.role == "assistant")
        var current = initial
        fun apply(next: Message) {
            store.save(next)
            current = next
            publish(next)
        }
        val calls = linkedMapOf<ToolCallId, ToolInvocation>()
        val results = mutableMapOf<ToolCallId, String>()
        try {
            if (stopped.get()) throw InterruptedException()
            val active = model.open(history)
            session = active
            if (stopped.get()) { cancelSession(active); throw InterruptedException() }
            var outputs = emptyList<ToolOutput>()
            for (round in 1..12) {
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
                active.next(outputs) { event ->
                    if (stopped.get()) throw InterruptedException()
                    repetitiveSurface?.let { throw RepetitiveOutputException(it) }
                    when (event) {
                        is ModelEvent.ReasoningDelta -> { checkText(event.itemId, event.text, final = false, reasoning = true) }
                        is ModelEvent.ReasoningFinal -> { checkText(event.itemId, event.text, final = true, reasoning = true) }
                        is ModelEvent.ProtocolOutput -> apply(current.copy(protocolItems = event.items))
                        is ModelEvent.TextDelta -> {
                            bodyProgress(checkText(event.itemId, event.text, final = false))
                            val blocks = current.blocks.toMutableList()
                            val at = blocks.indexOfFirst { it is MessageBlock.Text && it.itemId == event.itemId }
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
                            val at = blocks.indexOfFirst { it is MessageBlock.Text && it.itemId == event.itemId }
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
                                apply(current.copy(blocks = current.blocks + MessageBlock.Tool(call)))
                            }
                        }
                    }
                }
                if (stopped.get()) throw InterruptedException()
                repetitiveSurface?.let { throw RepetitiveOutputException(it) }
                if (pending.isEmpty()) {
                    synchronized(terminalLock) {
                        if (stopped.get()) throw InterruptedException()
                        apply(current.copy(status = RunStatus.COMPLETED)); terminal = true
                    }
                    return current
                }
                outputs = pending.map { call ->
                    if (stopped.get()) throw InterruptedException()
                    val result = results.getOrPut(call.id) { tool.execute(call) }
                    // Persist the result before handing it back to a model.
                    apply(current.copy(blocks = current.blocks.map {
                        if (it is MessageBlock.Tool && it.invocation.id == call.id) it.copy(result = result) else it
                    }))
                    ToolOutput(call.id, result)
                }
            }
            throw IllegalStateException("工具续轮达到12轮上限，已停止追加请求")
        } catch (failure: Exception) {
            val status = synchronized(terminalLock) { terminal = true; if (stopped.get()) RunStatus.STOPPED else RunStatus.FAILED }
            val next = current.copy(status = status, error = if (status == RunStatus.STOPPED) null else
                if (failure is PersistenceException) "保存失败，已显示内容可能未落盘" else
                if (failure is RepetitiveOutputException) "检测到模型${failure.surface}持续高度重复，已中止本次请求；已接收内容和工具结果已保留" else "请求失败，请检查模型设置或网络")
            try { apply(next) } catch (_: Exception) { current = next.copy(error = "保存失败，已显示内容可能未落盘"); publish(current) }
            return current
        } finally { cancelSession(session); session = null }
    }
}
class PersistenceException(cause: Throwable) : RuntimeException("Conversation persistence failed", cause)

private fun cancelSession(session: ModelSession?) {
    try { session?.cancel() } catch (_: Exception) { }
}

private class RepetitiveOutputException(val surface: String) : RuntimeException("Repetitive model output")
