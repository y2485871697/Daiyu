package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.domain.ToolDefinition
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ChildTaskHotConfigurationTest {
    private class Journal : ChildTaskStore {
        val rows = linkedMapOf<String, ChildTaskRecord>()
        val enqueues = AtomicInteger()
        var onStarted: () -> Unit = {}
        var onSelected: () -> Unit = {}
        @Synchronized override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {
            enqueues.incrementAndGet(); tasks.forEach { rows[it.owner.childRunId] = it }
        }
        override fun started(task: ChildTaskRecord) {
            synchronized(this) { rows[task.owner.childRunId] = task.copy(status = ChildTaskStatus.RUNNING) }
            onStarted()
        }
        override fun selected(task: ChildTaskRecord, display: ChildTaskDisplay?) {
            synchronized(this) { rows[task.owner.childRunId] = rows.getValue(task.owner.childRunId).copy(display = display) }
            onSelected()
        }
        @Synchronized override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
            rows[task.owner.childRunId] = rows.getValue(task.owner.childRunId).copy(status = status, text = text, detail = detail)
        }
        @Synchronized override fun get(owner: ChildRunOwner) = rows[owner.childRunId]?.takeIf { it.owner == owner }
        @Synchronized override fun list(conversationId: String, limit: Int) = rows.values.toList().take(limit)
    }
    private class Parent(id: String = "parent") {
        val owner = RunExecutionOwner.issue(Message(MessageId("message-$id"), ConversationId("conversation"),
            RunId(id), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        val caps = SessionCapabilities("session-$id", emptySet())
        val auth = TaskAuthorization(caps.sessionId, id)
        fun context(id: String) = ToolExecutionContext(owner, ToolInvocation(ToolCallId(id), "start_child_tasks", "{}"), auth) { false }
    }
    private fun answer(opens: AtomicInteger, text: String) = object : ModelPort {
        override fun open(history: List<Message>): ModelSession = open(history, emptyList())
        override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
            opens.incrementAndGet(); assertTrue(definitions.isEmpty())
            return object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { sink(ModelEvent.TextFinal("text", text)) }
                override fun cancel() {}
            }
        }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(condition())
    }
    private fun close(supervisor: RetainedChildTasks) {
        val done = CountDownLatch(1); supervisor.closeWhenIdle { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }
    @Test fun emptyAuthorizedGroupCanAdmitAnAddedProfileWithoutRebindingParent() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth, emptyMap())
        val opens = AtomicInteger()
        try {
            assertTrue(supervisor.configure(binding, mapOf("added" to ChildModelDefinition("new-pool", 1) { answer(opens, "added") })))
            val accepted = supervisor.submit(binding, parent.context("one"), listOf(ChildModelTask("added", "task"))) as ChildTaskAdmission.Accepted
            await { supervisor.snapshots(binding).single().settled }
            assertEquals("added", journal.get(accepted.tasks.single().owner)?.text); assertEquals(1, opens.get())
        } finally { close(supervisor) }
    }

    @Test fun idleBoundPoolAcceptsConfigurationChangeBeforeAnyTaskIsCreated() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val creates = AtomicInteger(); val entered = CountDownLatch(2); val finish = CountDownLatch(1)
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth, mapOf("p" to ChildModelDefinition("pool", 4) {
            object : ModelPort {
                override fun open(history: List<Message>): ModelSession {
                    creates.incrementAndGet()
                    return object : ModelSession {
                        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                            entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("text", "done"))
                        }
                        override fun cancel() { finish.countDown() }
                    }
                }
            }
        }))
        try {
            supervisor.reconcile("conversation") { 2 }
            repeat(4) { index -> assertTrue(supervisor.submit(binding, parent.context("task-$index"), listOf(ChildModelTask("p", "task"))) is ChildTaskAdmission.Accepted) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            await { supervisor.snapshots(binding).count { it.queueState == ChildTaskQueueState.PENDING } == 2 }
            assertEquals(2, creates.get()); finish.countDown()
            await { supervisor.snapshots(binding).all { it.settled } }; assertEquals(4, creates.get())
        } finally { finish.countDown(); close(supervisor) }
    }

    @Test fun shrinkingFourToTwoParksLaterTasksAndResumesTheirOriginalRequests() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val entered = List(4) { CountDownLatch(1) }; val fail = List(4) { CountDownLatch(1) }
        val finish = List(4) { CountDownLatch(1) }; val resumed = List(4) { CountDownLatch(1) }
        val opens = List(4) { AtomicInteger() }; val nexts = List(4) { AtomicInteger() }
        val definitions = (0..3).associate { index -> "p$index" to ChildModelDefinition("pool", 4) {
            object : ModelPort {
                override fun open(history: List<Message>): ModelSession {
                    opens[index].incrementAndGet()
                    return object : ResumableModelSession {
                        override val canResume = true
                        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                            nexts[index].incrementAndGet(); sink(ModelEvent.TextDelta("text", "partial-$index"))
                            entered[index].countDown()
                            if (index < 2) {
                                check(finish[index].await(5, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("text", "done-$index"))
                            } else {
                                check(fail[index].await(5, TimeUnit.SECONDS)); throw ModelRequestException(ModelFailure.NETWORK)
                            }
                        }
                        override fun resume(sink: (ModelEvent) -> Unit) {
                            resumed[index].countDown(); check(finish[index].await(5, TimeUnit.SECONDS))
                            sink(ModelEvent.TextFinal("text", "done-$index"))
                        }
                        override fun cancel() { fail[index].countDown(); finish[index].countDown() }
                    }
                }
            }
        } }
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth, definitions)
        val records = mutableListOf<ChildTaskRecord>()
        try {
            repeat(4) { index ->
                records += (supervisor.submit(binding, parent.context("call-$index"), listOf(ChildModelTask("p$index", "task"))) as ChildTaskAdmission.Accepted).tasks.single()
                assertTrue(entered[index].await(5, TimeUnit.SECONDS))
            }
            supervisor.reconcile("conversation") { 2 }
            await { supervisor.snapshots(binding).count { it.queueState == ChildTaskQueueState.PENDING } == 2 }
            fail[2].countDown(); fail[3].countDown()
            await { supervisor.snapshots(binding).count { it.queueState == ChildTaskQueueState.PENDING } == 2 }
            assertEquals(1L, resumed[2].count); assertEquals(1L, resumed[3].count)
            assertEquals("partial-2", supervisor.progress(records[2].owner))
            assertEquals(ChildTaskControlReceipt.PENDING, supervisor.pause(binding, records[3].owner))
            await { supervisor.snapshots(binding).single { it.record.owner == records[3].owner }.pauseState == ChildTaskPauseState.PAUSED }
            finish[0].countDown(); finish[1].countDown()
            assertTrue(resumed[2].await(5, TimeUnit.SECONDS)); assertEquals(1L, resumed[3].count)
            assertEquals(ChildTaskControlReceipt.RESUMED, supervisor.resume(binding, records[3].owner))
            assertTrue(resumed[3].await(5, TimeUnit.SECONDS))
            finish[2].countDown(); finish[3].countDown()
            await { supervisor.snapshots(binding).all { it.settled } }
            assertTrue(opens.all { it.get() == 1 }); assertTrue(nexts.all { it.get() == 1 })
            assertEquals(4, journal.enqueues.get()); assertEquals(records.map { it.owner }, journal.list("conversation").map { it.owner })
        } finally { fail.forEach { it.countDown() }; finish.forEach { it.countDown() }; close(supervisor) }
    }

    @Test fun revokedProfileStopsOriginalTaskAndKeepsProgressWithoutStoppingNewLifetime() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val valid = java.util.concurrent.atomic.AtomicBoolean(true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val definition = ChildModelDefinition("pool", 1, null, null, "old", valid = valid::get) {
            object : ModelPort {
                override fun open(history: List<Message>): ModelSession = object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        sink(ModelEvent.TextDelta("text", "retained partial")); entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("text", "done"))
                    }
                    override fun cancel() { release.countDown() }
                }
            }
        }
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth, mapOf("p" to definition))
        try {
            val old = (supervisor.submit(binding, parent.context("old"), listOf(ChildModelTask("p", "task"))) as ChildTaskAdmission.Accepted).tasks.single()
            assertTrue(entered.await(5, TimeUnit.SECONDS)); valid.set(false)
            val opens = AtomicInteger()
            assertTrue(supervisor.configure(binding, mapOf("p" to ChildModelDefinition("pool", 1) { answer(opens, "new") })))
            val fresh = (supervisor.submit(binding, parent.context("fresh"), listOf(ChildModelTask("p", "task"))) as ChildTaskAdmission.Accepted).tasks.single()
            supervisor.reconcile("conversation") { 1 }
            await { supervisor.snapshots(binding).all { it.settled } }
            val snapshots = supervisor.snapshots(binding)
            assertTrue(snapshots.single { it.record.owner == old.owner }.stopRequested)
            assertFalse(snapshots.single { it.record.owner == fresh.owner }.stopRequested)
            assertEquals("retained partial", supervisor.progress(old.owner)); assertNotNull(journal.get(old.owner))
            assertEquals("new", journal.get(fresh.owner)?.text); assertEquals(1, opens.get())
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.resume(binding, old.owner))
        } finally { release.countDown(); close(supervisor) }
    }

    @Test fun acceptedProgressNotifiesBindingBeforeTerminal() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val entered = CountDownLatch(1); val emit = CountDownLatch(1); val emitted = CountDownLatch(1)
        val release = CountDownLatch(1); val notified = CountDownLatch(1)
        val active = AtomicReference<ChildRunOwner>()
        val port = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    entered.countDown(); check(emit.await(5, TimeUnit.SECONDS))
                    sink(ModelEvent.TextDelta("text", "partial")); emitted.countDown()
                    check(release.await(5, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("text", "done"))
                }
                override fun cancel() {}
            }
        }
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth,
            mapOf("p" to ChildModelDefinition("pool", 1) { port }), onChanged = {
                active.get()?.let { if (supervisor.progress(it) == "partial") notified.countDown() }
            })
        try {
            val task = (supervisor.submit(binding, parent.context("one"), listOf(ChildModelTask("p", "task"))) as ChildTaskAdmission.Accepted).tasks.single()
            active.set(task.owner); assertTrue(entered.await(5, TimeUnit.SECONDS)); emit.countDown()
            assertTrue(emitted.await(5, TimeUnit.SECONDS))
            assertTrue("accepted progress must publish before terminal", notified.await(1, TimeUnit.SECONDS))
            assertFalse(supervisor.snapshots(binding).single().settled)
        } finally { emit.countDown(); release.countDown(); close(supervisor) }
    }

    @Test fun activeProgressSurvivesSequentialCompletedTaskEviction() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal, maxRetainedTasks = 2); val parent = Parent()
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        val held = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.TextDelta("text", "partial")); entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("text", "done"))
                }
                override fun cancel() {}
            }
        }
        val opens = AtomicInteger()
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth, mapOf(
            "held" to ChildModelDefinition("held", 1) { held },
            "other" to ChildModelDefinition("other", 1) { answer(opens, "done") }))
        try {
            val task = (supervisor.submit(binding, parent.context("held"), listOf(ChildModelTask("held", "task"))) as ChildTaskAdmission.Accepted).tasks.single()
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals("partial", supervisor.progress(task.owner))
            assertEquals(ChildTaskControlReceipt.PENDING, supervisor.pause(binding, task.owner))
            repeat(3) { index ->
                val next = (supervisor.submit(binding, parent.context("other-$index"), listOf(ChildModelTask("other", "task"))) as ChildTaskAdmission.Accepted).tasks.single()
                await { supervisor.snapshots(binding).any { it.record.owner == next.owner && it.settled } }
                assertEquals("live progress must not be evicted by completed tasks", "partial", supervisor.progress(task.owner))
                assertEquals(ChildTaskPauseState.PENDING, supervisor.snapshots(binding).single { it.record.owner == task.owner }.pauseState)
            }
            assertEquals(3, opens.get()); assertEquals(4, journal.enqueues.get())
        } finally { release.countDown(); close(supervisor) }
    }

    @Test fun stoppingTerminalTaskDoesNotChangeIntentOrReleaseCleanupPin() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val cancelling = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = AtomicInteger()
        val port = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { sink(ModelEvent.TextFinal("text", "done")) }
                override fun cancel() { cancelled.incrementAndGet(); cancelling.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }
        }
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth, mapOf("p" to ChildModelDefinition("pool", 1) { port }))
        try {
            val task = (supervisor.submit(binding, parent.context("one"), listOf(ChildModelTask("p", "task"))) as ChildTaskAdmission.Accepted).tasks.single()
            assertTrue(cancelling.await(5, TimeUnit.SECONDS))
            await { supervisor.snapshots(binding).single().record.status == ChildTaskStatus.COMPLETED }
            assertEquals(ChildTaskStop.ALREADY_SETTLED, supervisor.stop(binding, task.owner))
            val snapshot = supervisor.snapshots(binding).single()
            assertFalse(snapshot.stopRequested); assertFalse(snapshot.settled)
            assertEquals(1, cancelled.get())
        } finally { release.countDown(); close(supervisor) }
        assertTrue(supervisor.snapshots(binding).single().settled)
        assertEquals(1, cancelled.get())
    }

    @Test fun preOpenPauseSwitchesModelAndPoolWithoutReadmittingTask() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val oldOpens = AtomicInteger(); val newOpens = AtomicInteger()
        val old = ChildModelDefinition("old-pool", 1) { answer(oldOpens, "old") }
        val next = ChildModelDefinition("new-pool", 1) { answer(newOpens, "new") }
        val selection = AtomicReference(old)
        val dynamic = ChildModelDefinition("old-pool", 1, null, { selection.get() }, "old") { answer(oldOpens, "old") }
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth, mapOf("p" to dynamic))
        journal.onStarted = { supervisor.pauseGroup(binding) }
        val admission = supervisor.submit(binding, parent.context("one"), listOf(ChildModelTask("p", "task"))) as ChildTaskAdmission.Accepted
        val owner = admission.tasks.single().owner
        try {
            await { supervisor.snapshots(binding).single().pauseState == ChildTaskPauseState.PAUSED }
            selection.set(next); supervisor.finishParent(binding)
            assertEquals(ChildTaskControlReceipt.RESUMED, supervisor.resume(binding, owner))
            await { supervisor.snapshots(binding).single().settled }
            assertEquals(owner, journal.get(owner)?.owner)
            assertEquals("new", journal.get(owner)?.text)
            assertEquals(0, oldOpens.get()); assertEquals(1, newOpens.get()); assertEquals(1, journal.enqueues.get())
        } finally { close(supervisor) }
    }
    @Test fun selectionCallbackCanPauseAndChangeModelWithoutSendingStaleFactory() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val oldOpens = AtomicInteger(); val newOpens = AtomicInteger(); val selected = AtomicInteger()
        val selection = AtomicReference(ChildModelDefinition("a", 1) { answer(oldOpens, "old") })
        val dynamic = ChildModelDefinition("a", 1, null, { selection.get() }, "a") { answer(oldOpens, "old") }
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth, mapOf("p" to dynamic))
        journal.onSelected = {
            if (selected.incrementAndGet() == 1) {
                selection.set(ChildModelDefinition("b", 1) { answer(newOpens, "new") })
                supervisor.pauseGroup(binding)
            }
        }
        val task = (supervisor.submit(binding, parent.context("one"), listOf(ChildModelTask("p", "task"))) as ChildTaskAdmission.Accepted).tasks.single()
        try {
            await { supervisor.snapshots(binding).single().pauseState == ChildTaskPauseState.PAUSED }
            assertEquals(0, oldOpens.get()); assertEquals(0, newOpens.get())
            assertEquals(ChildTaskControlReceipt.RESUMED, supervisor.resume(binding, task.owner))
            await { supervisor.snapshots(binding).single().settled }
            assertEquals(0, oldOpens.get()); assertEquals(1, newOpens.get())
            assertEquals("new", journal.get(task.owner)?.text); assertEquals(1, journal.enqueues.get())
        } finally { close(supervisor) }
    }

    @Test fun newAdmissionsUseNewProfilesWithoutChangingAcceptedDefinitionOrPause() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val a = AtomicInteger(); val b = AtomicInteger()
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth,
            mapOf("old" to ChildModelDefinition("a", 1) { answer(a, "old") }))
        val group = supervisor.pauseGroup(binding)
        val first = supervisor.submit(binding, parent.context("one"), listOf(ChildModelTask("old", "first"))) as ChildTaskAdmission.Accepted
        try {
            await { supervisor.snapshots(binding).single().pauseState == ChildTaskPauseState.PAUSED }
            assertTrue(supervisor.configure(binding, mapOf("new" to ChildModelDefinition("b", 1) { answer(b, "new") })))
            val second = supervisor.submit(binding, parent.context("two"), listOf(ChildModelTask("new", "second"))) as ChildTaskAdmission.Accepted
            assertEquals(0, a.get()); assertEquals(0, b.get())
            assertTrue(supervisor.finishGroup(group, false))
            assertEquals(ChildTaskControlReceipt.RESUMED, supervisor.resume(binding, first.tasks.single().owner))
            assertEquals(ChildTaskControlReceipt.RESUMED, supervisor.resume(binding, second.tasks.single().owner))
            await { supervisor.snapshots(binding).all { it.settled } }
            assertEquals("old", journal.get(first.tasks.single().owner)?.text)
            assertEquals("new", journal.get(second.tasks.single().owner)?.text)
            assertEquals(1, a.get()); assertEquals(1, b.get()); assertEquals(2, journal.enqueues.get())
        } finally { close(supervisor) }
    }
    @Test fun selectedCallbackPauseIsAcknowledgedBeforeTransportOpen() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val opens = AtomicInteger(); val captured = AtomicReference<ChildTaskPauseGroup>()
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth,
            mapOf("p" to ChildModelDefinition("pool", 1) { answer(opens, "done") }))
        journal.onSelected = { captured.set(supervisor.pauseGroup(binding)) }
        supervisor.submit(binding, parent.context("selected-pause"), listOf(ChildModelTask("p", "task")))
        try {
            await { supervisor.snapshots(binding).single().pauseState == ChildTaskPauseState.PAUSED }
            assertEquals(0, opens.get())
            journal.onSelected = {}
            assertTrue(supervisor.resumeGroup(captured.get()))
            await { supervisor.snapshots(binding).single().settled }
            assertEquals(1, opens.get()); assertEquals(1, journal.enqueues.get())
        } finally { close(supervisor) }
    }

    @Test fun continueChoiceAllowsLaterModelChangeWithoutReadmissionOrAnotherPause() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val oldOpens = AtomicInteger(); val newOpens = AtomicInteger()
        val creating = CountDownLatch(1); val release = CountDownLatch(1)
        val old = ChildModelDefinition("a", 1) {
            creating.countDown(); check(release.await(5, TimeUnit.SECONDS)); answer(oldOpens, "old")
        }
        val chosen = AtomicReference(old)
        val dynamic = ChildModelDefinition("a", 1, null, { chosen.get() }, "a") { answer(oldOpens, "old") }
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth, mapOf("p" to dynamic))
        val captured = AtomicReference<ChildTaskPauseGroup>()
        journal.onStarted = { captured.set(supervisor.pauseGroup(binding)) }
        val task = (supervisor.submit(binding, parent.context("one"), listOf(ChildModelTask("p", "task"))) as ChildTaskAdmission.Accepted).tasks.single()
        try {
            await { supervisor.snapshots(binding).single().pauseState == ChildTaskPauseState.PAUSED }
            supervisor.finishParent(binding)
            assertTrue(supervisor.resumeGroup(captured.get()))
            assertTrue(creating.await(5, TimeUnit.SECONDS))
            assertEquals(ChildTaskPauseState.ACTIVE, supervisor.snapshots(binding).single().pauseState)
            chosen.set(ChildModelDefinition("b", 1) { answer(newOpens, "new") })
            release.countDown()
            await { supervisor.snapshots(binding).single().settled }
            assertEquals(task.owner, journal.get(task.owner)?.owner)
            assertEquals("new", journal.get(task.owner)?.text)
            assertEquals(0, oldOpens.get()); assertEquals(1, newOpens.get()); assertEquals(1, journal.enqueues.get())
        } finally { release.countDown(); close(supervisor) }
    }

    @Test fun continueAfterResumablePauseKeepsTheSentRequestOnItsOriginalTransport() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val entered = CountDownLatch(1); val failRead = CountDownLatch(1)
        val opens = AtomicInteger(); val nexts = AtomicInteger(); val resumes = AtomicInteger(); val newOpens = AtomicInteger()
        val port = ModelPort {
            opens.incrementAndGet()
            object : ResumableModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts.incrementAndGet(); sink(ModelEvent.TextDelta("text", "partial")); entered.countDown()
                    check(failRead.await(5, TimeUnit.SECONDS)); throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun resume(sink: (ModelEvent) -> Unit) { resumes.incrementAndGet(); sink(ModelEvent.TextFinal("text", "complete")) }
                override fun cancel() { failRead.countDown() }
            }
        }
        val chosen = AtomicReference(ChildModelDefinition("a", 1) { port })
        val dynamic = ChildModelDefinition("a", 1, null, { chosen.get() }, "a") { port }
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth, mapOf("p" to dynamic))
        val task = (supervisor.submit(binding, parent.context("one"), listOf(ChildModelTask("p", "task"))) as ChildTaskAdmission.Accepted).tasks.single()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); await { supervisor.progress(task.owner) == "partial" }
            val group = supervisor.pauseGroup(binding); supervisor.finishParent(binding)
            chosen.set(ChildModelDefinition("b", 1) { answer(newOpens, "new") })
            failRead.countDown()
            await { supervisor.snapshots(binding).single().pauseState == ChildTaskPauseState.PAUSED }
            assertEquals("partial", supervisor.progress(task.owner))
            assertTrue(supervisor.resumeGroup(group))
            await { supervisor.snapshots(binding).single().settled }
            assertEquals("complete", journal.get(task.owner)?.text)
            assertEquals(1, opens.get()); assertEquals(1, nexts.get()); assertEquals(1, resumes.get())
            assertEquals(0, newOpens.get()); assertEquals(1, journal.enqueues.get())
        } finally { failRead.countDown(); close(supervisor) }
    }

    @Test fun sentRequestKeepsTransportAndReadableProgressAfterStop() {
        val journal = Journal(); val supervisor = RetainedChildTasks(journal); val parent = Parent()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val opens = AtomicInteger(); val other = AtomicInteger()
        val model = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = open(history, emptyList())
            override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                opens.incrementAndGet()
                return object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        sink(ModelEvent.TextDelta("text", "partial")); entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)); sink(ModelEvent.TextFinal("text", "done"))
                    }
                    override fun cancel() { release.countDown() }
                }
            }
        }
        val chosen = AtomicReference(ChildModelDefinition("a", 1) { model })
        val dynamic = ChildModelDefinition("a", 1, null, { chosen.get() }, "a") { model }
        val binding = supervisor.bind(parent.owner, parent.caps, parent.auth, mapOf("p" to dynamic))
        val task = (supervisor.submit(binding, parent.context("one"), listOf(ChildModelTask("p", "task"))) as ChildTaskAdmission.Accepted).tasks.single()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); await { supervisor.progress(task.owner) == "partial" }
            chosen.set(ChildModelDefinition("b", 1) { answer(other, "new") })
            assertEquals(ChildTaskControlReceipt.PENDING, supervisor.pause(binding, task.owner))
            assertEquals(ChildTaskControlReceipt.RESUMED, supervisor.resume(binding, task.owner))
            assertEquals(1, opens.get()); assertEquals(0, other.get())
            supervisor.stop(binding, task.owner); await { supervisor.snapshots(binding).single().settled }
            assertEquals("partial", supervisor.progress(task.owner))
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.resume(binding, task.owner))
            assertEquals(1, journal.enqueues.get())
        } finally { release.countDown(); close(supervisor) }
    }
}
