package app.daiyu.android.domain

import java.util.concurrent.atomic.AtomicBoolean

/** One owner thread applies events. Cancellation only signals and closes transport. */
class ChatRunner(private val model: ModelPort, private val store: ConversationStore,
    private val tool: BatteryTool, private val publish: (Message) -> Unit) {
    private val stopped = AtomicBoolean(false)
    private val terminalLock = Any()
    private var terminal = false
    @Volatile private var session: ModelSession? = null
    fun stop() { synchronized(terminalLock) { if (terminal) return; stopped.set(true) }; session?.cancel() }
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
            if (stopped.get()) { active.cancel(); throw InterruptedException() }
            var outputs = emptyList<ToolOutput>()
            for (round in 1..12) {
                val pending = mutableListOf<ToolInvocation>()
                val repetition = ReasoningRepetitionGuard()
                val responseText = linkedMapOf<String, String>()
                var repetitive = false
                fun checkText(itemId: String, text: String, final: Boolean) {
                    val previous = responseText[itemId].orEmpty()
                    repetitive = if (!final) {
                        repetition.append(text)
                    } else if (text.startsWith(previous)) {
                        repetition.append(text.substring(previous.length))
                    } else {
                        repetition.reset()
                        val corrected = LinkedHashMap(responseText)
                        corrected[itemId] = text
                        corrected.values.any { repetition.append(it) }
                    }
                    if (repetitive) {
                        active.cancel()
                        throw RepetitiveOutputException()
                    }
                    responseText[itemId] = if (final) text else previous + text
                }
                active.next(outputs) { event ->
                    if (stopped.get()) throw InterruptedException()
                    if (repetitive) throw RepetitiveOutputException()
                    when (event) {
                        is ModelEvent.ProtocolOutput -> apply(current.copy(protocolItems = event.items))
                        is ModelEvent.TextDelta -> {
                            checkText(event.itemId, event.text, final = false)
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
                            checkText(event.itemId, event.text, final = true)
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
                if (repetitive) throw RepetitiveOutputException()
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
                if (failure is RepetitiveOutputException) "检测到模型正文持续高度重复，已中止本次请求；已接收内容和工具结果已保留" else "请求失败，请检查模型设置或网络")
            try { apply(next) } catch (_: Exception) { current = next.copy(error = "保存失败，已显示内容可能未落盘"); publish(current) }
            return current
        } finally { session?.cancel(); session = null }
    }
}
class PersistenceException(cause: Throwable) : RuntimeException("Conversation persistence failed", cause)

private class RepetitiveOutputException : RuntimeException("Repetitive model output")
