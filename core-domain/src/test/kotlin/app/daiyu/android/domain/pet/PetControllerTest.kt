package app.daiyu.android.domain.pet

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Retained cancellation/receipt cases plus production transition admission. No Android/transport IO. */
class PetControllerTest {
    private class Scheduler : PetScheduler {
        data class Entry(val delay: Long, val task: () -> Unit, var cancelled: Boolean = false)
        val entries = mutableListOf<Entry>()
        var fail = false
        override fun after(delayMillis: Long, task: () -> Unit): PetCancel {
            if (fail) error("scheduler unavailable")
            val row = Entry(delayMillis, task); entries.add(row); return PetCancel { row.cancelled = true }
        }
        fun fire(delay: Long, evenCancelled: Boolean = false) {
            entries.filter { it.delay == delay && (evenCancelled || !it.cancelled) }.toList().forEach { it.task() }
        }
    }
    private class Model : PetModelPort {
        val requests = mutableListOf<PetModelRequest>()
        val callbacks = mutableListOf<(PetModelResult) -> Unit>()
        var cancels = 0
        var onCancel: ((Int) -> Unit)? = null
        override fun complete(request: PetModelRequest, completion: (PetModelResult) -> Unit): PetCancel {
            requests.add(request); callbacks.add(completion)
            val index = callbacks.lastIndex
            return PetCancel { cancels++; onCancel?.invoke(index) }
        }
        fun reply(index: Int = callbacks.lastIndex) {
            callbacks[index](PetModelResult.Completed("{\"mood\":\"eating\",\"speech\":\"米饭真香\"}", 100, 15))
        }
    }
    private class Voice : PetVoicePort {
        var spoken = 0; var stopped = 0
        override fun speak(requestId: String, text: String): PetCancel { spoken++; return PetCancel { stopped++ } }
    }
    private fun initial() = PetState(settings = PetSettings(enabled = true, audioEnabled = true,
        model = PetModelSelection("pet-assistant", "pet-provider", "pet-model")))
    private class Fixture(val store: PetStore) {
        val scheduler = Scheduler(); val model = Model(); val voice = Voice(); var now = 10_000L; var ids = 0
        val c = PetController(store, model, scheduler, PetClock { now }, voice, { "req-${++ids}" })
    }
    private fun running(id: String = "run") = Message(MessageId("answer-$id"), ConversationId("conversation"),
        RunId(id), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private fun Fixture.work(id: String, title: String = id): PetCommandResult {
        val before = running(id)
        return c.onRunTransition(before, before.copy(status = RunStatus.COMPLETED), title)
    }
    @Test fun feedingPersistsIndependentRequestMemoryActionAndUsage() {
        val f = Fixture(InMemoryPetStore(initial()))
        assertTrue(f.c.feed(PetFood.BOWL) is PetCommandResult.Applied)
        assertEquals(8_000, f.c.snapshot().state.satiety)
        val request = f.model.requests.single()
        assertEquals("pet-assistant", request.selection.assistantId)
        assertEquals("pet:req-1", request.sessionId); assertEquals("pet", request.usageOwner)
        assertFalse(request.toolsAllowed); assertFalse(request.webSearchAllowed); assertFalse(request.reasoningAllowed)
        assertEquals(120, request.maxOutputTokens)
        f.model.reply()
        val state = f.c.snapshot().state
        assertFalse(state.thinking); assertEquals(PetMood.EATING, state.mood); assertEquals(1, state.memories.size)
        assertTrue(state.satiety < 8_000); assertTrue(state.satiety >= 0)
        assertEquals(100L, state.usage.single().inputTokens)
        assertEquals(PetRequestStatus.COMPLETED, state.usage.single().status)
        assertEquals(state, f.store.load())
    }
    @Test fun onlyRealMatchingSuccessfulTransitionsAndNeverLoadedHistory() {
        val f = Fixture(InMemoryPetStore(initial()))
        val before = running()
        listOf(RunStatus.RUNNING, RunStatus.STOPPED, RunStatus.FAILED, RunStatus.INTERRUPTED).forEach {
            assertTrue(f.c.onRunTransition(before, before.copy(status = it), "title") is PetCommandResult.Rejected)
        }
        val done = before.copy(status = RunStatus.COMPLETED)
        assertTrue(f.c.onRunTransition(done, done, "loaded") is PetCommandResult.Rejected)
        assertTrue(f.c.onRunTransition(before, done.copy(error = "failed"), "title") is PetCommandResult.Rejected)
        assertTrue(f.c.onRunTransition(before, done.copy(runId = RunId("other")), "title") is PetCommandResult.Rejected)
        assertTrue(f.c.onRunTransition(before, done.copy(role = Message.USER_ROLE), "title") is PetCommandResult.Rejected)
        assertTrue(f.model.requests.isEmpty())
        assertTrue(f.c.onRunTransition(before, done, "real") is PetCommandResult.Applied)
        assertTrue(f.c.onRunTransition(before, done, "duplicate") is PetCommandResult.Rejected)
        f.model.reply()
        f.c.close()
        val restored = Fixture(f.store)
        assertTrue(restored.c.onRunTransition(before, done, "replay") is PetCommandResult.Rejected)
        assertTrue(restored.model.requests.isEmpty())
        assertEquals(1, restored.c.snapshot().state.memories.size)
        assertEquals(1, restored.c.snapshot().state.completedRuns.size)
    }
    @Test fun latestPendingWorkAfterFeedRetainsOriginalBehaviourAndTaskWindow() {
        val f = Fixture(InMemoryPetStore(initial()))
        f.c.feed(PetFood.SPOON); f.work("old"); f.work("latest")
        assertEquals(1, f.model.requests.size)
        f.model.reply()
        assertEquals(2, f.model.requests.size)
        assertEquals(PetEvent.WORK_DONE, f.model.requests.last().event)
        assertTrue(f.model.requests.last().prompt.contains("latest"))
        assertEquals(listOf("latest"), f.c.snapshot().state.recentTasks.map { it.title })
        f.model.reply(); f.now += PET_TASK_WINDOW_MS + 1; f.work("after-window")
        assertEquals(listOf("after-window"), f.c.snapshot().state.recentTasks.map { it.title })
    }
    @Test fun synchronousCancelReceiptSurvivesInvalidationWithoutRestoringEffects() {
        val commands: List<(PetController) -> Unit> = listOf(
            { it.setEnabled(false) },
            { it.updateSettings(it.snapshot().state.settings.copy(model = PetModelSelection("next-a", "next-p", "next-m"))) },
            { it.updateSettings(it.snapshot().state.settings.copy(workReactions = false)) },
            { it.clearMemories() }, { it.close() }
        )
        commands.forEach { command ->
            val f = Fixture(InMemoryPetStore(initial()))
            f.model.onCancel = { index ->
                f.model.reply(index)
                f.model.callbacks[index](PetModelResult.Completed("duplicate", 999, 999))
            }
            f.c.feed(PetFood.BOWL); f.work("must-not-run")
            val satiety = f.c.snapshot().state.satiety
            command(f.c); f.model.reply(0)
            f.scheduler.fire(45_000, true); f.scheduler.fire(1_500, true); f.scheduler.fire(3_100, true)
            val state = f.c.snapshot().state
            assertEquals(PetRequestStatus.CANCELLED, state.usage.single().status)
            assertEquals(100L, state.usage.single().inputTokens)
            assertEquals(15L, state.usage.single().outputTokens)
            assertEquals(state, f.store.load())
            assertFalse(state.thinking); assertFalse(state.speechVisible)
            assertEquals(PetMood.IDLE, state.mood); assertTrue(state.memories.isEmpty())
            assertEquals(satiety, state.satiety); assertEquals(0, f.voice.spoken)
            assertEquals(1, f.model.requests.size); assertEquals(1, f.model.cancels)
        }
    }
    @Test fun timeoutIsNotSuccessfulMemoryAndLateReceiptIsCountedOnlyOnce() {
        val f = Fixture(InMemoryPetStore(initial()))
        f.model.onCancel = { index -> f.model.reply(index) }
        f.c.feed(PetFood.BOWL); val satiety = f.c.snapshot().state.satiety
        f.scheduler.fire(45_000)
        f.model.callbacks.single()(PetModelResult.Completed("duplicate", 999, 999))
        val state = f.c.snapshot().state
        assertEquals(PetRequestStatus.FAILED, state.usage.single().status)
        assertEquals(100L, state.usage.single().inputTokens)
        assertEquals(satiety, state.satiety); assertTrue(state.memories.isEmpty())
        assertFalse(state.speechVisible); assertEquals(0, f.voice.spoken)
    }
    @Test fun disabledLateCompletionCannotResurrectAndAudioStops() {
        val f = Fixture(InMemoryPetStore(initial()))
        f.c.feed(PetFood.SPOON); f.model.reply(); assertEquals(1, f.voice.spoken)
        f.c.setEnabled(false); assertEquals(1, f.voice.stopped)
        f.c.setEnabled(true); f.c.feed(PetFood.GRAIN); f.c.setEnabled(false)
        f.model.reply(1); f.scheduler.fire(1_500, true); f.scheduler.fire(3_100, true)
        assertEquals(1, f.voice.spoken); assertEquals(1, f.c.snapshot().state.memories.size)
        assertFalse(f.c.snapshot().state.speechVisible)
    }
    @Test fun restoreInterruptsPendingRequestWithoutFabricatedCompletion() {
        val pending = PetUsage("r", "a", "p", "m", PetEvent.FEED, PetRequestStatus.PENDING)
        val f = Fixture(InMemoryPetStore(initial().copy(thinking = true, mood = PetMood.THINKING, usage = listOf(pending))))
        assertFalse(f.c.snapshot().state.thinking)
        assertEquals(PetRequestStatus.INTERRUPTED, f.c.snapshot().state.usage.single().status)
        assertTrue(f.model.requests.isEmpty()); assertTrue(f.c.snapshot().state.memories.isEmpty())
    }
    @Test fun unconfiguredFeedIsRealGameActionButNotModelCompletion() {
        val f = Fixture(InMemoryPetStore(PetState(settings = PetSettings(enabled = true))))
        val errors = mutableListOf<String?>()
        val observation = f.c.observe { errors.add(it.error) }
        assertTrue(f.c.feed(PetFood.POT) is PetCommandResult.Applied)
        val s = f.c.snapshot().state
        assertEquals(15_000, s.satiety); assertEquals(PetMood.EATING, s.mood)
        assertTrue(s.memories.isEmpty()); assertTrue(s.usage.isEmpty()); assertFalse(s.speechVisible)
        assertNull(f.c.snapshot().error)
        assertTrue(f.model.requests.isEmpty()); assertEquals(0, f.voice.spoken)
        f.scheduler.fire(1_500); assertEquals(PetMood.IDLE, f.c.snapshot().state.mood)
        assertTrue(errors.all { it == null })
        assertEquals(f.c.snapshot().state, f.store.load())
        observation.cancel()
    }
    @Test fun unconfiguredFeedStillReportsActualTimerFailure() {
        val f = Fixture(InMemoryPetStore(PetState(settings = PetSettings(enabled = true))))
        f.scheduler.fail = true
        assertTrue(f.c.feed(PetFood.POT) is PetCommandResult.Applied)
        assertEquals(15_000, f.c.snapshot().state.satiety)
        assertEquals("已投喂；桌宠动作定时服务不可用", f.c.snapshot().error)
        assertTrue(f.model.requests.isEmpty()); assertTrue(f.c.snapshot().state.usage.isEmpty())
        assertEquals(f.c.snapshot().state, f.store.load())
    }
    @Test fun failedStoreDoesNotConfirmFeedOrOverwriteFailedReadWithDefaults() {
        var saves = 0
        val brokenRead = object : PetStore {
            override fun load(): PetState = error("corrupt")
            override fun save(state: PetState) { saves++ }
        }
        val f = Fixture(brokenRead)
        assertTrue(f.c.setEnabled(true) is PetCommandResult.Failed)
        assertEquals(0, saves); assertTrue(f.model.requests.isEmpty())
        var fail = false
        val brokenWrite = object : PetStore {
            override fun load() = initial()
            override fun save(state: PetState) { if (fail) error("disk full") }
        }
        val g = Fixture(brokenWrite); fail = true
        assertTrue(g.c.feed(PetFood.BOWL) is PetCommandResult.Failed)
        assertEquals(5_000, g.c.snapshot().state.satiety); assertTrue(g.model.requests.isEmpty())
    }
}
