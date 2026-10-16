package app.daiyu.android.domain.pet

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.RunStatus
import java.util.UUID

/** Retained independent runtime. Serialized transitions; asynchronous paths check their generation.
 * Owns neither the main agent nor a credential/session repository. Store failure is fail-closed. */
class PetController(
    private val store: PetStore, private val model: PetModelPort,
    private val scheduler: PetScheduler, private val clock: PetClock,
    private val voice: PetVoicePort? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) : AutoCloseable {
    private var state: PetState
    private var error: String? = null
    private var closed = false
    private var operational = true
    private var generation = 0L
    private var active: Flight? = null
    private var pendingTitle: String? = null
    private var modelCancel: PetCancel? = null
    private var deadline: PetCancel? = null
    private var poseTimer: PetCancel? = null
    private var speechTimer: PetCancel? = null
    private var audio: PetCancel? = null
    private val listeners = linkedSetOf<(PetSnapshot) -> Unit>()
    private data class Flight(val id: String, val generation: Long, val event: PetEvent,
        val food: PetFood?, val title: String, val prompt: String) {
        var receiptReceived = false
    }
    init {
        state = try { store.load().forRestore().also { store.save(it) } }
        catch (_: Exception) { operational = false; error = "桌宠存储读取/恢复失败；未启动"; PetState() }
    }
    @Synchronized fun snapshot(): PetSnapshot = PetSnapshot(state, error, closed)
    @Synchronized fun observe(listener: (PetSnapshot) -> Unit): PetCancel {
        listeners.add(listener); runCatching { listener(snapshot()) }
        return PetCancel { synchronized(this) { listeners.remove(listener) } }
    }
    private fun publish() { val next = snapshot(); listeners.toList().forEach { runCatching { it(next) } } }
    private fun cancel(handle: PetCancel?) { runCatching { handle?.cancel() } }
    private fun stopEffects() {
        val request = modelCancel; modelCancel = null
        val timeout = deadline; deadline = null
        cancel(request); cancel(timeout)
        cancel(poseTimer); poseTimer = null; cancel(speechTimer); speechTimer = null
        cancel(audio); audio = null
    }
    private fun invalidate(): PetState {
        generation++; active = null; pendingTitle = null
        // Terminalize before cancel: an adapter may synchronously deliver its final usage receipt.
        state = state.copy(thinking = false, speech = "", speechVisible = false, mood = PetMood.IDLE,
            usage = state.usage.map { if (it.status == PetRequestStatus.PENDING) it.copy(status = PetRequestStatus.CANCELLED) else it })
        stopEffects()
        return state
    }
    private fun save(next: PetState): Boolean = try {
        store.save(next); state = next; error = null; publish(); true
    } catch (_: Exception) {
        operational = false; state = invalidate(); error = "桌宠存储写入失败；操作未确认，运行已停止"; publish(); false
    }
    private fun result(ok: Boolean): PetCommandResult = if (ok) PetCommandResult.Applied else PetCommandResult.Failed(error ?: "失败")
    private fun allowed(): Boolean = !closed && operational && state.settings.enabled

    @Synchronized fun setEnabled(enabled: Boolean): PetCommandResult {
        if (closed) return PetCommandResult.Rejected("控制器已关闭")
        val next = invalidate().copy(settings = state.settings.copy(enabled = enabled))
        if (!operational && error?.contains("读取") == true) return PetCommandResult.Failed(error!!)
        val ok = save(next); if (ok) operational = true
        return result(ok)
    }
    @Synchronized fun updateSettings(settings: PetSettings): PetCommandResult {
        if (closed || !operational) return PetCommandResult.Rejected("运行不可用")
        val reset = settings.enabled != state.settings.enabled || settings.model != state.settings.model ||
            settings.workReactions != state.settings.workReactions
        val base = if (reset) invalidate() else state
        if (!settings.audioEnabled) { cancel(audio); audio = null }
        return result(save(base.copy(settings = settings)))
    }
    @Synchronized fun setScale(scale: Float): PetCommandResult {
        if (!scale.isFinite()) return PetCommandResult.Rejected("缩放无效")
        return updateSettings(state.settings.copy(scale = ((scale.coerceIn(0.5f, 1.8f) * 20).toInt() / 20f)))
    }
    @Synchronized fun setPosition(x: Int, y: Int): PetCommandResult = updateSettings(state.settings.copy(x = x, y = y))
    @Synchronized fun clearMemories(): PetCommandResult {
        if (closed || !operational) return PetCommandResult.Rejected("运行不可用")
        return result(save(invalidate().copy(memories = emptyList())))
    }
    @Synchronized fun dismissSpeech(): PetCommandResult {
        if (!allowed() || state.thinking) return PetCommandResult.Rejected("当前无可关闭反应")
        generation++; stopEffects()
        return result(save(state.copy(speechVisible = false, speech = "", mood = PetMood.IDLE)))
    }
    @Synchronized fun feed(food: PetFood): PetCommandResult {
        if (!allowed()) return PetCommandResult.Rejected("桌宠未开启或已停止")
        if (active != null) return PetCommandResult.Rejected("正在思考")
        if (!save(state.copy(satiety = (state.satiety + food.satiety).coerceAtMost(PET_MAX_SATIETY)))) return result(false)
        // A real feed remains a game-state action without a model, never a simulated model completion.
        if (!state.settings.model.configured) {
            generation++; stopEffects()
            val ticket = generation
            if (!save(state.copy(mood = PetMood.EATING))) return result(false)
            try {
                poseTimer = scheduler.after(1_500) { synchronized(this) {
                    if (allowed() && generation == ticket) save(state.copy(mood = PetMood.IDLE))
                } }
                error = "已投喂；未配置独立桌宠assistant/model，未请求反应"
            } catch (_: Exception) { error = "已投喂；桌宠动作定时服务不可用" }
            publish()
            return PetCommandResult.Applied
        }
        return start(PetEvent.FEED, food, "")
    }
    /** Parent must deliver a LIVE persisted owner transition, not loaded history or UI recomposition.
     * No title-only completion API exists. Failed/stopped/replayed/foreign transitions do nothing. */
    @Synchronized fun onRunTransition(before: Message, after: Message, title: String): PetCommandResult {
        if (before.status != RunStatus.RUNNING || after.status != RunStatus.COMPLETED || after.error != null ||
            before.role != Message.ASSISTANT_ROLE || after.role != Message.ASSISTANT_ROLE ||
            before.id != after.id || before.runId != after.runId || before.conversationId != after.conversationId)
            return PetCommandResult.Rejected("不是同一运行的真实成功终态")
        if (!allowed() || !state.settings.workReactions) return PetCommandResult.Rejected("完工反应已关闭")
        val key = PetCompletedRun(after.conversationId.value, after.runId.value)
        if (key in state.completedRuns) return PetCommandResult.Rejected("该运行已接收")
        // Record before requesting; crash recovery never replays a completed run as a fresh event.
        if (!save(state.copy(completedRuns = state.completedRuns + key))) return result(false)
        return onWorkDone(title)
    }
    private fun onWorkDone(title: String): PetCommandResult {
        if (!allowed() || !state.settings.workReactions) return PetCommandResult.Rejected("完工反应已关闭")
        if (state.satiety <= 0) return PetCommandResult.Rejected("粮食不足")
        if (!state.settings.model.configured) return PetCommandResult.Rejected("需配置独立桌宠assistant/model")
        val safe = title.trim().take(200)
        if (active != null) { pendingTitle = safe; return PetCommandResult.Applied }
        return start(PetEvent.WORK_DONE, null, safe)
    }
    private fun start(event: PetEvent, food: PetFood?, title: String): PetCommandResult {
        generation++; stopEffects()
        val now = clock.nowMillis()
        val tasks = state.recentTasks.filter { now >= it.atMillis && now - it.atMillis <= PET_TASK_WINDOW_MS && it.title != title }
        val prepared = state.copy(recentTasks = if (event == PetEvent.WORK_DONE && title.isNotBlank())
            (listOf(PetTask(title, now)) + tasks).take(8) else tasks)
        val id = newId()
        val prompt = petPrompt(event, prepared, food, title, now)
        val flight = Flight(id, generation, event, food, title, prompt)
        val selection = state.settings.model
        val usage = PetUsage(id, selection.assistantId, selection.providerId, selection.modelId, event, PetRequestStatus.PENDING)
        if (!save(prepared.copy(thinking = true, mood = PetMood.THINKING, speechVisible = false,
                usage = prepared.usage + usage))) return result(false)
        if (!allowed() || generation != flight.generation) return PetCommandResult.Rejected("请求在启动前已取消")
        active = flight
        try {
            deadline = scheduler.after(45_000) { synchronized(this) {
                if (live(flight)) { val handle = modelCancel; complete(flight, PetModelResult.Failed("桌宠请求超时")); cancel(handle) }
            } }
            val handle = model.complete(PetModelRequest(id, selection, event, prompt, "pet:$id")) { response ->
                synchronized(this) { complete(flight, response) }
            }
            if (live(flight)) modelCancel = handle else cancel(handle)
        } catch (_: Exception) { complete(flight, PetModelResult.Failed("桌宠模型服务不可用")) }
        return if (error == null) PetCommandResult.Applied else PetCommandResult.Failed(error!!)
    }
    private fun live(flight: Flight): Boolean = allowed() && active == flight && generation == flight.generation
    private fun complete(flight: Flight, response: PetModelResult) {
        if (!live(flight)) {
            if (!closed && operational && response is PetModelResult.Completed && !flight.receiptReceived) {
                val row = state.usage.firstOrNull { it.requestId == flight.id } ?: return
                if (row.status in setOf(PetRequestStatus.CANCELLED, PetRequestStatus.FAILED)) {
                    flight.receiptReceived = true
                    save(state.copy(usage = state.usage.map { if (it.requestId == flight.id)
                        it.copy(inputTokens = response.inputTokens, outputTokens = response.outputTokens) else it }))
                }
            }
            return
        }
        active = null; cancel(deadline); deadline = null; modelCancel = null
        val queued = pendingTitle; pendingTitle = null
        if (response is PetModelResult.Failed) {
            val ok = save(state.copy(thinking = false, mood = PetMood.IDLE, usage = state.usage.map {
                if (it.requestId == flight.id) it.copy(status = PetRequestStatus.FAILED) else it }))
            if (ok) { error = "桌宠反应失败；未生成记忆/语音"; publish() }
        } else if (response is PetModelResult.Completed) {
            flight.receiptReceived = true
            val reaction = parsePetReaction(response.raw, flight.event, state.satiety, flight.food, flight.title)
            val spent = petSatietyCost(flight.prompt, reaction.speech)
            val memory = PetMemory(reaction.mood, reaction.speech, spent, clock.nowMillis(), flight.event)
            val ok = save(state.copy(thinking = false, mood = reaction.mood, speech = reaction.speech,
                speechVisible = true, satiety = (state.satiety - spent).coerceAtLeast(0),
                memories = (listOf(memory) + state.memories).take(20), usage = state.usage.map {
                    if (it.requestId == flight.id) it.copy(status = PetRequestStatus.COMPLETED,
                        inputTokens = response.inputTokens, outputTokens = response.outputTokens) else it }))
            if (ok && allowed() && generation == flight.generation) {
                try {
                    poseTimer = scheduler.after(1_500) { synchronized(this) {
                        if (allowed() && generation == flight.generation && !state.thinking) save(state.copy(mood = PetMood.IDLE))
                    } }
                    speechTimer = scheduler.after(3_100) { synchronized(this) {
                        if (allowed() && generation == flight.generation && !state.thinking) dismissSpeech()
                    } }
                    if (state.settings.audioEnabled && voice != null) {
                        val handle = voice.speak(flight.id, reaction.speech)
                        if (allowed() && generation == flight.generation) audio = handle else cancel(handle)
                    }
                } catch (_: Exception) { error = "桌宠定时/语音服务失败"; publish() }
            }
        }
        if (queued != null && allowed() && state.settings.workReactions) onWorkDone(queued)
    }
    @Synchronized override fun close() {
        if (closed) return
        val canPersist = operational
        val next = invalidate()
        if (canPersist) save(next) else state = next
        closed = true; operational = false; publish(); listeners.clear()
    }
}
