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
