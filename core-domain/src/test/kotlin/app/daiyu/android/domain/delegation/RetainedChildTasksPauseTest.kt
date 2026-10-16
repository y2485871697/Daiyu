package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.ToolExecutionContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RetainedChildTasksPauseTest {
    private class Journal : ChildTaskStore {
        private val rows = linkedMapOf<String, ChildTaskRecord>()
        var onStarted: () -> Unit = {}
        var failFinished = false
        @Synchronized override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {
            tasks.forEach { rows[it.owner.childRunId] = it }
        }
        override fun started(task: ChildTaskRecord) {
            synchronized(this) { rows[task.owner.childRunId] = task.copy(status = ChildTaskStatus.RUNNING) }
            onStarted()
        }
        @Synchronized override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
            check(!failFinished) { "injected terminal journal failure" }
            rows[task.owner.childRunId] = task.copy(status = status, text = text, detail = detail)
        }
        @Synchronized override fun list(conversationId: String, limit: Int) = rows.values.toList().take(limit)
    }

    private class Parent(id: String) {
        val owner = RunExecutionOwner.issue(Message(MessageId("message-$id"), ConversationId("conversation"),
            RunId("run-$id"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        val session = SessionCapabilities("session-$id", emptySet())
        val authorization = TaskAuthorization(session.sessionId, owner.runId.value)
        fun bind(supervisor: RetainedChildTasks, model: ModelPort, onChanged: () -> Unit = {}) =
            supervisor.bind(owner, session, authorization,
                mapOf("p" to ChildModelDefinition("pool", 1) { model }), onChanged)
        fun context(call: String) = ToolExecutionContext(owner,
            ToolInvocation(ToolCallId(call), "start_child_tasks", "{}"), authorization) { false }
    }

    private class Model(val resumable: Boolean = true, val fail: Boolean = true, holdCancel: Boolean = false) : ModelPort {
        val opens = AtomicInteger()
        val reads = AtomicInteger()
        val resumes = AtomicInteger()
        val cancels = AtomicInteger()
        val entered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(if (holdCancel) 1 else 0)
        @Volatile var oldSink: ((ModelEvent) -> Unit)? = null
        override fun open(history: List<Message>): ModelSession = error("explicit definitions required")
        override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
            assertTrue(definitions.isEmpty())
            opens.incrementAndGet()
            return object : ResumableModelSession {
                override val canResume get() = resumable
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    assertTrue(outputs.isEmpty())
                    reads.incrementAndGet()
                    oldSink = sink
                    sink(ModelEvent.TextDelta("answer", "before "))
                    entered.countDown()
                    check(releaseRead.await(5, TimeUnit.SECONDS))
                    if (fail) throw ModelRequestException(ModelFailure.NETWORK)
                    sink(ModelEvent.TextFinal("answer", "complete"))
                }
                override fun resume(sink: (ModelEvent) -> Unit) {
                    resumes.incrementAndGet()
                    sink(ModelEvent.TextDelta("answer", "after"))
                }
                override fun cancel() {
                    cancels.incrementAndGet()
                    cancelEntered.countDown()
                    check(releaseCancel.await(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    private fun submit(supervisor: RetainedChildTasks, binding: ChildTaskBinding, parent: Parent, call: String = "call") =
        supervisor.submit(binding, parent.context(call), listOf(ChildModelTask("p", "review")))
    private fun owner(supervisor: RetainedChildTasks, binding: ChildTaskBinding, parent: Parent) =
        (submit(supervisor, binding, parent) as ChildTaskAdmission.Accepted).tasks.single().owner
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("condition before deadline", condition())
    }
    private fun snapshot(supervisor: RetainedChildTasks, binding: ChildTaskBinding) = supervisor.snapshots(binding).single()
    private fun drain(supervisor: RetainedChildTasks, model: Model) {
        model.releaseRead.countDown()
        model.releaseCancel.countDown()
        val done = CountDownLatch(1)
        supervisor.closeWhenIdle { done.countDown() }
        assertTrue("real worker and transport drain", done.await(5, TimeUnit.SECONDS))
    }

    @Test fun unconfirmedJournalCannotResumeAStillRetainedChild() {
        val journal = Journal().apply { failFinished = true }
        val model = Model(fail = false, holdCancel = true)
        val parent = Parent("unconfirmed")
        val supervisor = RetainedChildTasks(journal)
        val binding = parent.bind(supervisor, model)
        try {
            val child = owner(supervisor, binding, parent)
            assertTrue(model.entered.await(5, TimeUnit.SECONDS))
            model.releaseRead.countDown()
            await { snapshot(supervisor, binding).journalUnconfirmed }
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.pause(binding, child))
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.resume(binding, child))
            assertEquals(1, model.opens.get())
        } finally { drain(supervisor, model) }
    }

    @Test fun blockedReadIsOnlyPendingThenSameSessionResumesWithoutPostOrLateEvents() {
        val journal = Journal(); val model = Model(); val parent = Parent("resume")
        val supervisor = RetainedChildTasks(journal); val binding = parent.bind(supervisor, model)
        try {
            val child = owner(supervisor, binding, parent)
            assertTrue(model.entered.await(5, TimeUnit.SECONDS))
            assertEquals(ChildTaskControlReceipt.PENDING, supervisor.pause(binding, child))
            assertEquals(ChildTaskControlReceipt.PENDING, supervisor.pause(binding, child))
            assertEquals(ChildTaskPauseState.PENDING, snapshot(supervisor, binding).pauseState)
            assertEquals(ChildTaskStatus.RUNNING, snapshot(supervisor, binding).record.status)
            assertEquals(0, model.cancels.get()); assertEquals(0, model.resumes.get())
            model.releaseRead.countDown()
            await { snapshot(supervisor, binding).pauseState == ChildTaskPauseState.PAUSED }
            assertEquals(ChildTaskControlReceipt.PAUSED, supervisor.pause(binding, child))
            model.oldSink!!.invoke(ModelEvent.TextDelta("answer", "LATE"))
            assertEquals(0, model.cancels.get())
            assertEquals(ChildTaskControlReceipt.RESUMED, supervisor.resume(binding, child))
            await { snapshot(supervisor, binding).settled }
            assertEquals(ChildTaskStatus.COMPLETED, snapshot(supervisor, binding).record.status)
            assertEquals("before after", snapshot(supervisor, binding).record.text)
            assertEquals(1, model.opens.get()); assertEquals(1, model.reads.get()); assertEquals(1, model.resumes.get())
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.resume(binding, child))
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.pause(binding, child))
        } finally { drain(supervisor, model) }
    }

    @Test fun initialBoundaryPausesBeforeOpenAndParentSealDoesNotResumeCancelOrRelease() {
        val journal = Journal(); val model = Model(fail = false); val parent = Parent("initial")
        val started = CountDownLatch(1); val releaseStarted = CountDownLatch(1)
        journal.onStarted = { started.countDown(); check(releaseStarted.await(5, TimeUnit.SECONDS)) }
        val supervisor = RetainedChildTasks(journal, timeoutMillis = 1_000, maxRetainedTasks = 1)
        val binding = parent.bind(supervisor, model)
        try {
            val child = owner(supervisor, binding, parent)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(ChildTaskControlReceipt.PENDING, supervisor.pause(binding, child))
            releaseStarted.countDown()
            await { snapshot(supervisor, binding).pauseState == ChildTaskPauseState.PAUSED }
            supervisor.finishParent(binding)
            assertFalse(supervisor.isReleased(binding))
            assertEquals(0, model.opens.get()); assertEquals(0, model.cancels.get())
            // Confirmed pause, unlike pending, does not exhaust the existing execution timeout.
            Thread.sleep(1_100)
            assertEquals(ChildTaskPauseState.PAUSED, snapshot(supervisor, binding).pauseState)
            assertEquals(ChildTaskControlReceipt.RESUMED, supervisor.resume(binding, child))
            assertTrue(model.entered.await(5, TimeUnit.SECONDS))
            model.releaseRead.countDown()
            await { supervisor.isReleased(binding) }
            assertEquals(1, model.opens.get()); assertEquals(0, model.resumes.get())
            assertEquals(ChildTaskStatus.COMPLETED, snapshot(supervisor, binding).record.status)
        } finally { releaseStarted.countDown(); drain(supervisor, model) }
    }

    @Test fun stopWakesPausedWorkerButKeepsCapacityAndBindingUntilTransportCleanupReturns() {
        val journal = Journal(); val model = Model(holdCancel = true); val parent = Parent("stop-paused")
        val supervisor = RetainedChildTasks(journal, maxRetainedTasks = 1)
        val binding = parent.bind(supervisor, model)
        val other = Parent("other"); val otherBinding = other.bind(supervisor, model)
        try {
            val child = owner(supervisor, binding, parent)
            assertTrue(model.entered.await(5, TimeUnit.SECONDS))
            supervisor.pause(binding, child)
            model.releaseRead.countDown()
            await { snapshot(supervisor, binding).pauseState == ChildTaskPauseState.PAUSED }
            supervisor.finishParent(binding)
            assertEquals(ChildTaskStop.REQUESTED, supervisor.stop(binding, child))
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.resume(binding, child))
            assertTrue(model.cancelEntered.await(5, TimeUnit.SECONDS))
            await { snapshot(supervisor, binding).record.status == ChildTaskStatus.UNKNOWN }
            assertFalse(snapshot(supervisor, binding).settled)
            assertFalse(supervisor.isReleased(binding))
            assertEquals(ChildTaskAdmission.Rejected("child_retention_limit"), submit(supervisor, otherBinding, other))
            assertEquals(0, model.resumes.get())
            model.releaseCancel.countDown()
            await { supervisor.isReleased(binding) }
            assertTrue(snapshot(supervisor, binding).settled)
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.pause(binding, child))
        } finally { drain(supervisor, model) }
    }

    @Test fun pendingStopDoesNotReleaseAStillBlockedRealWorker() {
        val model = Model(); val parent = Parent("blocked-stop")
        val supervisor = RetainedChildTasks(Journal(), maxRetainedTasks = 1)
        val binding = parent.bind(supervisor, model)
        try {
            val child = owner(supervisor, binding, parent)
            assertTrue(model.entered.await(5, TimeUnit.SECONDS))
            assertEquals(ChildTaskControlReceipt.PENDING, supervisor.pause(binding, child))
            supervisor.finishParent(binding)
            supervisor.stop(binding, child)
            assertTrue(model.cancelEntered.await(5, TimeUnit.SECONDS))
            assertFalse(supervisor.isReleased(binding)); assertFalse(snapshot(supervisor, binding).settled)
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.resume(binding, child))
            model.releaseRead.countDown()
            await { supervisor.isReleased(binding) }
            assertEquals(0, model.resumes.get())
        } finally { drain(supervisor, model) }
    }

    @Test fun rejectsEveryStaleChildIdentityAndCrossOwnerWithoutMutatingTarget() {
        val model = Model(fail = false); val parent = Parent("identity")
        val supervisor = RetainedChildTasks(Journal()); val binding = parent.bind(supervisor, model)
        val other = Parent("cross-owner"); val otherBinding = other.bind(supervisor, model)
        val foreign = RetainedChildTasks(Journal())
        try {
            val child = owner(supervisor, binding, parent)
            assertTrue(model.entered.await(5, TimeUnit.SECONDS))
            val stale = listOf(child.copy(conversationId = "old-conversation"), child.copy(parentRunId = "old-parent"),
                child.copy(taskGroupId = "old-group"), child.copy(generation = child.generation + 1),
                child.copy(childRunId = "old-child"), child.copy(assistantMessageId = "old-message"))
            stale.forEach {
                assertEquals(ChildTaskControlReceipt.STALE, supervisor.pause(binding, it))
                assertEquals(ChildTaskControlReceipt.STALE, supervisor.resume(binding, it))
            }
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.pause(otherBinding, child))
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.resume(otherBinding, child))
            try { foreign.pause(binding, child); fail("foreign binding") } catch (_: IllegalArgumentException) { }
            assertEquals(ChildTaskPauseState.ACTIVE, snapshot(supervisor, binding).pauseState)
            assertEquals(0, model.cancels.get())
            // Retracting a pending pause never reopens or resumes the network request.
            assertEquals(ChildTaskControlReceipt.PENDING, supervisor.pause(binding, child))
            assertEquals(ChildTaskControlReceipt.RESUMED, supervisor.resume(binding, child))
            model.releaseRead.countDown()
            await { snapshot(supervisor, binding).settled }
            assertEquals(1, model.opens.get()); assertEquals(0, model.resumes.get())
        } finally { foreign.close(); drain(supervisor, model) }
    }

    @Test fun successfulReadWithPendingIntentCompletesWithoutPretendingToPause() {
        val model = Model(fail = false); val parent = Parent("pending-complete")
        val supervisor = RetainedChildTasks(Journal())
        val pausedNotifications = AtomicInteger()
        val handle = AtomicReference<ChildTaskBinding>()
        val binding = parent.bind(supervisor, model) {
            handle.get()?.let {
                if (supervisor.snapshots(it).singleOrNull()?.pauseState == ChildTaskPauseState.PAUSED)
                    pausedNotifications.incrementAndGet()
            }
        }
        handle.set(binding)
        try {
            val child = owner(supervisor, binding, parent)
            assertTrue(model.entered.await(5, TimeUnit.SECONDS))
            assertEquals(ChildTaskControlReceipt.PENDING, supervisor.pause(binding, child))
            assertEquals(ChildTaskPauseState.PENDING, snapshot(supervisor, binding).pauseState)
            model.releaseRead.countDown()
            await { snapshot(supervisor, binding).settled }
            assertEquals(ChildTaskPauseState.ACTIVE, snapshot(supervisor, binding).pauseState)
            assertEquals(0, pausedNotifications.get())
            assertEquals(ChildTaskStatus.COMPLETED, snapshot(supervisor, binding).record.status)
            assertEquals(0, model.resumes.get())
        } finally { drain(supervisor, model) }
    }

    @Test fun safeTransportFailureWithoutPauseIntentDoesNotAutomaticallyResume() {
        val model = Model(); val parent = Parent("no-auto-resume")
        val supervisor = RetainedChildTasks(Journal()); val binding = parent.bind(supervisor, model)
        try {
            owner(supervisor, binding, parent)
            assertTrue(model.entered.await(5, TimeUnit.SECONDS))
            model.releaseRead.countDown()
            await { snapshot(supervisor, binding).settled }
            assertEquals(ChildTaskStatus.UNKNOWN, snapshot(supervisor, binding).record.status)
            assertEquals(1, model.opens.get()); assertEquals(0, model.resumes.get())
        } finally { drain(supervisor, model) }
    }

    @Test fun nonResumableFailureNeverClaimsPausedOrReposts() {
        val model = Model(resumable = false); val parent = Parent("nonresumable")
        val supervisor = RetainedChildTasks(Journal()); val binding = parent.bind(supervisor, model)
        try {
            val child = owner(supervisor, binding, parent)
            assertTrue(model.entered.await(5, TimeUnit.SECONDS))
            assertEquals(ChildTaskControlReceipt.PENDING, supervisor.pause(binding, child))
            model.releaseRead.countDown()
            await { snapshot(supervisor, binding).settled }
            assertEquals(ChildTaskStatus.UNKNOWN, snapshot(supervisor, binding).record.status)
            assertEquals(ChildTaskPauseState.ACTIVE, snapshot(supervisor, binding).pauseState)
            assertEquals(ChildTaskControlReceipt.STALE, supervisor.resume(binding, child))
            assertEquals(1, model.opens.get()); assertEquals(1, model.reads.get()); assertEquals(0, model.resumes.get())
        } finally { drain(supervisor, model) }
    }
}
