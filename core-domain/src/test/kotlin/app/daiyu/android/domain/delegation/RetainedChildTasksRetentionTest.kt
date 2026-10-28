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

/** Lifecycle regressions independent of the shared host/database fixtures. */
class RetainedChildTasksRetentionTest {
    private class Journal : ChildTaskStore {
        private val rows = linkedMapOf<String, ChildTaskRecord>()
        val enqueues = AtomicInteger()
        var beforeEnqueue: (() -> Unit)? = null
        var failEnqueue = false
        var failFinish = false
        override fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>) {
            enqueues.incrementAndGet()
            beforeEnqueue?.invoke()
            synchronized(this) { tasks.forEach { rows[it.owner.childRunId] = it } }
            check(!failEnqueue)
        }
        @Synchronized override fun started(task: ChildTaskRecord) {
            rows[task.owner.childRunId] = task.copy(status = ChildTaskStatus.RUNNING)
        }
        @Synchronized override fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String, detail: String?) {
            check(!failFinish)
            rows[task.owner.childRunId] = task.copy(status = status, text = text, detail = detail)
        }
        @Synchronized override fun list(conversationId: String, limit: Int) =
            rows.values.filter { it.owner.conversationId == conversationId }.take(limit)
    }

    private class Parent(id: String) {
        val owner = RunExecutionOwner.issue(Message(MessageId("message-$id"), ConversationId("conversation"),
            RunId("run-$id"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        val session = SessionCapabilities("session-$id", emptySet())
        val authorization = TaskAuthorization(session.sessionId, owner.runId.value)
        fun context(call: String) = ToolExecutionContext(owner,
            ToolInvocation(ToolCallId(call), "start_child_tasks", "{}"), authorization) { false }
        fun bind(supervisor: RetainedChildTasks, model: ModelPort, pool: String = "pool", limit: Int = 1) =
            supervisor.bind(owner, session, authorization, mapOf("p" to ChildModelDefinition(pool, limit) { model }))
    }

    private class Model(private val held: Boolean = false) : ModelPort {
        val opens = AtomicInteger()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(if (held) 1 else 0)
        override fun open(history: List<Message>): ModelSession = error("explicit tool definitions required")
        override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
            assertTrue(definitions.isEmpty())
            opens.incrementAndGet()
            return object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    sink(ModelEvent.TextFinal("answer", "done"))
                }
                override fun cancel() { cancelled.countDown() }
            }
        }
    }

    private val input = listOf(ChildModelTask("p", "review"))
    private fun submit(supervisor: RetainedChildTasks, binding: ChildTaskBinding, parent: Parent, call: String) =
        supervisor.submit(binding, parent.context(call), input)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("condition before deadline", condition())
    }
    private fun settled(supervisor: RetainedChildTasks, binding: ChildTaskBinding, id: String) =
        supervisor.snapshots(binding).any { it.record.owner.childRunId == id && it.settled }
    private fun drain(supervisor: RetainedChildTasks) {
        val done = CountDownLatch(1)
        supervisor.closeWhenIdle { done.countDown() }
        assertTrue("real drain", done.await(5, TimeUnit.SECONDS))
    }

    @Test fun deletionKeepsLiveProfileUntilEveryAcceptedTaskPhysicallySettles() {
        val store = Journal(); val model = Model(held = true); val parent = Parent("retired")
        val supervisor = RetainedChildTasks(store)
        var deleted = false
        val definition = ChildModelDefinition("pool", 0, null, latest = null, selection = "saved-model",
            admissionAllowed = { !deleted }) { model }
        val binding = supervisor.bind(parent.owner, parent.session, parent.authorization, mapOf("p" to definition))
        try {
            val accepted = supervisor.submit(binding, parent.context("first"), listOf(
                ChildModelTask("p", "one"), ChildModelTask("p", "two"))) as ChildTaskAdmission.Accepted
            assertTrue(model.entered.await(5, TimeUnit.SECONDS))
            deleted = true
            assertEquals(setOf("conversation" to "p"), supervisor.liveProfiles())
            assertEquals(ChildTaskAdmission.Rejected("child_model_deleted"), submit(supervisor, binding, parent, "new"))
            assertEquals(1, store.enqueues.get())
            assertEquals(1L, model.cancelled.count)
            model.release.countDown()
            await { accepted.tasks.all { settled(supervisor, binding, it.owner.childRunId) } }
            assertTrue(supervisor.liveProfiles().isEmpty())
            assertEquals(2, model.opens.get())
            assertTrue(store.list("conversation", 10).all { it.status == ChildTaskStatus.COMPLETED })
        } finally { model.release.countDown(); drain(supervisor) }
    }

    @Test fun moreThan64LifetimeTasksReuseCapacityButEvictedCallsCannotResend() {
        val store = Journal(); val model = Model(); val parent = Parent("long-lived")
        val supervisor = RetainedChildTasks(store, maxRetainedTasks = 1)
        val binding = parent.bind(supervisor, model)
        try {
            repeat(70) { index ->
                val result = submit(supervisor, binding, parent, "call-$index") as ChildTaskAdmission.Accepted
                await { settled(supervisor, binding, result.tasks.single().owner.childRunId) }
            }
            assertEquals(70, store.list("conversation", 100).size)
            assertTrue(store.list("conversation", 100).all { it.status == ChildTaskStatus.COMPLETED })
            assertEquals(1, supervisor.snapshots(binding).size)
            assertEquals(ChildTaskAdmission.Rejected("child_request_settled"), submit(supervisor, binding, parent, "call-0"))
            assertEquals(ChildTaskAdmission.Rejected("child_request_conflict"),
                supervisor.submit(binding, parent.context("call-0"), listOf(ChildModelTask("p", "changed"))))
            assertEquals(70, model.opens.get()); assertEquals(70, store.enqueues.get())
            // No completed request retains its original Accepted records/prompts or invocation.
            assertEquals(70, binding.requests.size)
            assertTrue(binding.requests.values.all {
                it.fingerprint.size == 32 && it.remaining == 0 && it.admission is ChildTaskAdmission.Rejected
            })
            supervisor.finishParent(binding)
            assertNull(binding.resources); assertTrue(binding.requests.isEmpty())
            assertEquals(ChildTaskAdmission.Rejected("child_binding_closed"), submit(supervisor, binding, parent, "call-0"))
        } finally { drain(supervisor) }
    }

    @Test fun sealedDrainedBindingReleasesFrozenFactoriesAndPoolButCannotBeRebound() {
        val store = Journal(); val selected = Model(); val replacement = Model(); val parent = Parent("frozen")
        val supervisor = RetainedChildTasks(store)
        val models = mutableMapOf("p" to ChildModelDefinition("pool", 1) { selected })
        val binding = supervisor.bind(parent.owner, parent.session, parent.authorization, models)
        models["p"] = ChildModelDefinition("pool", 2) { replacement }
        try {
            val accepted = submit(supervisor, binding, parent, "first") as ChildTaskAdmission.Accepted
            await { settled(supervisor, binding, accepted.tasks.single().owner.childRunId) }
            assertEquals(1, selected.opens.get()); assertEquals(0, replacement.opens.get())
            assertNotNull(binding.resources) // An accepting parent still needs its frozen selection.
            supervisor.finishParent(binding)
            assertNull(binding.resources)
            assertThrows(IllegalStateException::class.java) { parent.bind(supervisor, replacement) }
            val next = Parent("next")
            val nextBinding = next.bind(supervisor, replacement, limit = 2) // Old pool reference is gone.
            val nextResult = submit(supervisor, nextBinding, next, "first") as ChildTaskAdmission.Accepted
            await { settled(supervisor, nextBinding, nextResult.tasks.single().owner.childRunId) }
            assertEquals(1, replacement.opens.get())
        } finally { drain(supervisor) }
    }

    @Test fun cancelAndParentSealKeepCapacityAndSharedPoolUntilTransportActuallyExits() {
        val store = Journal(); val held = Model(true); val fast = Model()
        val first = Parent("held"); val second = Parent("waiting")
        val supervisor = RetainedChildTasks(store, maxRetainedTasks = 1)
        val a = first.bind(supervisor, held)
        try {
            val accepted = submit(supervisor, a, first, "first") as ChildTaskAdmission.Accepted
            assertTrue(held.entered.await(3, TimeUnit.SECONDS))
            supervisor.stop(a, accepted.tasks.single().owner); supervisor.finishParent(a)
            assertTrue(held.cancelled.await(3, TimeUnit.SECONDS))
            assertNotNull(a.resources)
            assertThrows(IllegalArgumentException::class.java) { second.bind(supervisor, fast, limit = 2) }
            val b = second.bind(supervisor, fast)
            assertEquals(ChildTaskAdmission.Rejected("child_retention_limit"), submit(supervisor, b, second, "second"))
            assertEquals(0, fast.opens.get())
            held.release.countDown()
            await { settled(supervisor, a, accepted.tasks.single().owner.childRunId) }
            assertNull(a.resources)
            assertEquals(ChildTaskStatus.UNKNOWN, supervisor.snapshots(a).single().record.status)
            val next = submit(supervisor, b, second, "second") as ChildTaskAdmission.Accepted
            await { settled(supervisor, b, next.tasks.single().owner.childRunId) }
            assertEquals(1, held.opens.get()); assertEquals(1, fast.opens.get())
            assertEquals(2, store.enqueues.get())
        } finally { held.release.countDown(); drain(supervisor) }
    }

    @Test fun uncertainEnqueueReleasesResourcesWithoutLosingUnknownOrReplayingItsCall() {
        val store = Journal().apply { failEnqueue = true }; val model = Model(); val parent = Parent("uncertain")
        val supervisor = RetainedChildTasks(store, maxRetainedTasks = 1)
        val binding = parent.bind(supervisor, model)
        try {
            val result = submit(supervisor, binding, parent, "first")
            assertEquals(ChildTaskAdmission.Unconfirmed("child_admission_unconfirmed"), result)
            assertNull(binding.resources)
            assertEquals(result, submit(supervisor, binding, parent, "first"))
            assertEquals(ChildTaskAdmission.Rejected("child_request_conflict"),
                supervisor.submit(binding, parent.context("first"), listOf(ChildModelTask("p", "different"))))
            assertEquals(0, model.opens.get()); assertEquals(1, store.enqueues.get())
            assertTrue(supervisor.snapshots(binding).single().journalUnconfirmed)
            assertEquals(ChildTaskStatus.QUEUED, store.list("conversation").single().status)
            store.failEnqueue = false
            val next = Parent("after-uncertain"); val nextBinding = next.bind(supervisor, model, limit = 2)
            val accepted = submit(supervisor, nextBinding, next, "next") as ChildTaskAdmission.Accepted
            await { settled(supervisor, nextBinding, accepted.tasks.single().owner.childRunId) }
            assertEquals(1, model.opens.get()) // No automatic recovery of the uncertain first row.
            assertEquals(2, store.list("conversation").size)
        } finally { drain(supervisor) }
    }

    @Test fun failedTerminalPersistenceRetainsEvidenceButDoesNotExhaustLiveCapacity() {
        val store = Journal().apply { failFinish = true }; val model = Model(); val parent = Parent("finish-failure")
        val supervisor = RetainedChildTasks(store, maxRetainedTasks = 1)
        val binding = parent.bind(supervisor, model)
        try {
            val accepted = submit(supervisor, binding, parent, "first") as ChildTaskAdmission.Accepted
            await { settled(supervisor, binding, accepted.tasks.single().owner.childRunId) }
            val snapshot = supervisor.snapshots(binding).single()
            assertTrue(snapshot.journalUnconfirmed); assertEquals(ChildTaskStatus.RUNNING, snapshot.record.status)
            assertEquals(ChildTaskAdmission.Rejected("child_request_settled"), submit(supervisor, binding, parent, "first"))
            store.failFinish = false
            val next = submit(supervisor, binding, parent, "next") as ChildTaskAdmission.Accepted
            await { settled(supervisor, binding, next.tasks.single().owner.childRunId) }
            assertEquals(2, model.opens.get()); assertEquals(2, store.enqueues.get())
            assertEquals(ChildTaskStatus.RUNNING, store.list("conversation").first().status)
        } finally { drain(supervisor) }
    }

    @Test fun closeDuringJournalReservationDoesNotReleaseBindingBeforeJournalReturns() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val idle = CountDownLatch(1)
        val store = Journal().apply { beforeEnqueue = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val model = Model(); val parent = Parent("enqueue"); val supervisor = RetainedChildTasks(store)
        val binding = parent.bind(supervisor, model)
        val failure = AtomicReference<Throwable>()
        val thread = Thread { try { submit(supervisor, binding, parent, "first") } catch (t: Throwable) { failure.set(t) } }
        try {
            thread.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
            supervisor.closeWhenIdle { idle.countDown() }
            assertNotNull(binding.resources); assertEquals(1L, idle.count)
            release.countDown(); thread.join(3000)
            assertFalse(thread.isAlive); assertNull(failure.get()); assertTrue(idle.await(3, TimeUnit.SECONDS))
            assertNull(binding.resources); assertTrue(binding.requests.isEmpty()); assertEquals(0, model.opens.get())
        } finally { release.countDown(); thread.join(3000); drain(supervisor) }
    }

    @Test fun idleSealNotifiesAfterReleaseAndSharedPoolLivesUntilItsLastBindingCloses() {
        val supervisor = RetainedChildTasks(Journal()); val model = Model()
        val first = Parent("idle-one"); val second = Parent("idle-two"); val third = Parent("idle-three")
        val releasedNotice = AtomicInteger()
        lateinit var a: ChildTaskBinding
        a = supervisor.bind(first.owner, first.session, first.authorization,
            mapOf("p" to ChildModelDefinition("pool", 1) { model })) {
            if (supervisor.isReleased(a)) releasedNotice.incrementAndGet()
        }
        val b = second.bind(supervisor, model)
        try {
            assertFalse(supervisor.isReleased(a)); assertFalse(supervisor.isReleased(b))
            supervisor.finishParent(a)
            assertTrue(supervisor.isReleased(a)); assertEquals(1, releasedNotice.get())
            assertThrows(IllegalArgumentException::class.java) { third.bind(supervisor, model, limit = 2) }
            supervisor.finishParent(b)
            assertTrue(supervisor.isReleased(b))
            val c = third.bind(supervisor, model, limit = 2)
            supervisor.finishParent(c)
            assertTrue(supervisor.isReleased(c)); assertEquals(0, model.opens.get())
        } finally { drain(supervisor) }
    }

    @Test fun smallRequestBudgetBoundsTombstonesWithoutEvictingSentCallsOrConsumingLiveCapacity() {
        val store = Journal(); val model = Model(); val first = Parent("budget")
        val supervisor = RetainedChildTasks(store, maxRetainedTasks = 1, maxRequestsPerBinding = 2)
        val a = first.bind(supervisor, model)
        try {
            for (call in listOf("first", "second")) {
                val result = submit(supervisor, a, first, call) as ChildTaskAdmission.Accepted
                await { settled(supervisor, a, result.tasks.single().owner.childRunId) }
            }
            repeat(20) {
                assertEquals(ChildTaskAdmission.Rejected("child_request_budget_exhausted"),
                    submit(supervisor, a, first, "new-$it"))
            }
            // Even after the recent UI cache evicts it, the old call must never be re-sent.
            assertEquals(1, supervisor.snapshots(a).size)
            assertEquals(ChildTaskAdmission.Rejected("child_request_settled"), submit(supervisor, a, first, "first"))
            assertEquals(ChildTaskAdmission.Rejected("child_request_conflict"),
                supervisor.submit(a, first.context("first"), listOf(ChildModelTask("p", "changed"))))
            assertEquals(2, a.requests.size)
            assertTrue(a.requests.keys.all { it.length <= 200 })
            assertEquals(2, store.enqueues.get()); assertEquals(2, model.opens.get())
            // The replay budget is per binding, while the live capacity is reusable across runs.
            val second = Parent("independent-budget"); val b = second.bind(supervisor, model)
            val accepted = submit(supervisor, b, second, "first") as ChildTaskAdmission.Accepted
            await { settled(supervisor, b, accepted.tasks.single().owner.childRunId) }
            assertEquals(3, model.opens.get())
        } finally { drain(supervisor) }
    }

    @Test fun budgetKeepsPendingAndActiveReplayAheadOfLimitAndBoundsCallIds() {
        val store = Journal(); val model = Model(true); val parent = Parent("pending-budget")
        val supervisor = RetainedChildTasks(store, maxRequestsPerBinding = 1)
        val binding = parent.bind(supervisor, model)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val result = AtomicReference<ChildTaskAdmission>(); val failure = AtomicReference<Throwable>()
        val thread = Thread {
            try { result.set(submit(supervisor, binding, parent, "first")) }
            catch (t: Throwable) { failure.set(t) }
        }
        try {
            assertEquals(ChildTaskAdmission.Rejected("invalid_child_call_id"),
                submit(supervisor, binding, parent, "x".repeat(201)))
            assertTrue(binding.requests.isEmpty()); assertEquals(0, store.enqueues.get())
            store.beforeEnqueue = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            thread.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(ChildTaskAdmission.Unconfirmed("child_admission_pending"), submit(supervisor, binding, parent, "first"))
            assertEquals(ChildTaskAdmission.Rejected("child_request_budget_exhausted"), submit(supervisor, binding, parent, "second"))
            release.countDown(); thread.join(3000)
            assertFalse(thread.isAlive); assertNull(failure.get()); assertTrue(result.get() is ChildTaskAdmission.Accepted)
            assertTrue(model.entered.await(3, TimeUnit.SECONDS))
            assertEquals(result.get(), submit(supervisor, binding, parent, "first"))
            assertEquals(ChildTaskAdmission.Rejected("child_request_conflict"),
                supervisor.submit(binding, parent.context("first"), listOf(ChildModelTask("p", "changed"))))
            assertEquals(1, store.enqueues.get()); assertEquals(1, model.opens.get()); assertEquals(1, binding.requests.size)
        } finally { release.countDown(); thread.join(3000); model.release.countDown(); drain(supervisor) }
    }

    @Test fun unknownReplaySurvivesAFullBudgetWithoutReenqueueing() {
        val store = Journal().apply { failEnqueue = true }; val model = Model(); val parent = Parent("unknown-budget")
        val supervisor = RetainedChildTasks(store, maxRequestsPerBinding = 1)
        val binding = parent.bind(supervisor, model)
        try {
            val unknown = submit(supervisor, binding, parent, "first")
            assertEquals(ChildTaskAdmission.Unconfirmed("child_admission_unconfirmed"), unknown)
            assertTrue(supervisor.isReleased(binding))
            assertEquals(unknown, submit(supervisor, binding, parent, "first"))
            assertEquals(ChildTaskAdmission.Rejected("child_request_conflict"),
                supervisor.submit(binding, parent.context("first"), listOf(ChildModelTask("p", "changed"))))
            assertEquals(1, binding.requests.size); assertEquals(1, store.enqueues.get()); assertEquals(0, model.opens.get())
        } finally { drain(supervisor) }
    }

    @Test fun requestBudgetConfigurationMustBeFiniteAndPositive() {
        for (budget in listOf(0, -1, 4097, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { RetainedChildTasks(Journal(), maxRequestsPerBinding = budget) }
        }
    }

    private class BlockingCancelModel(private val failCancel: Boolean = false) : ModelPort {
        val entered = CountDownLatch(1)
        val returnNext = CountDownLatch(1)
        val nextReturned = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val returnCancel = CountDownLatch(1)
        val cancels = AtomicInteger()
        override fun open(history: List<Message>): ModelSession = error("explicit tool definitions required")
        override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession = object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                entered.countDown()
                try {
                    check(returnNext.await(15, TimeUnit.SECONDS))
                    sink(ModelEvent.TextFinal("answer", "done"))
                } finally { nextReturned.countDown() }
            }
            override fun cancel() {
                cancels.incrementAndGet(); cancelEntered.countDown()
                check(returnCancel.await(15, TimeUnit.SECONDS))
                check(!failCancel) { "provider cancel failed after cleanup" }
            }
        }
    }

    @Test fun returnedNextDoesNotReleaseCapacityPoolOrBindingUntilBlockingCancelFinallyReturns() {
        for (outcome in listOf("stop", "timeout", "complete", "cancel-throws")) {
            val completes = outcome == "complete" || outcome == "cancel-throws"
            val store = Journal(); val model = BlockingCancelModel(failCancel = outcome == "cancel-throws"); val fast = Model()
            val parent = Parent("cleanup-$outcome"); val next = Parent("next-$outcome")
            val supervisor = RetainedChildTasks(store, timeoutMillis = if (outcome == "timeout") 500 else 120_000,
                maxRetainedTasks = 1)
            val a = parent.bind(supervisor, model)
            val idle = CountDownLatch(1); val callbacks = AtomicInteger()
            try {
                val accepted = submit(supervisor, a, parent, "first") as ChildTaskAdmission.Accepted
                assertTrue(model.entered.await(3, TimeUnit.SECONDS))
                if (outcome == "stop") supervisor.stop(a, accepted.tasks.single().owner)
                supervisor.finishParent(a)
                if (completes) model.returnNext.countDown()
                assertTrue(model.cancelEntered.await(3, TimeUnit.SECONDS))
                model.returnNext.countDown()
                assertTrue(model.nextReturned.await(3, TimeUnit.SECONDS))
                val expected = if (completes) ChildTaskStatus.COMPLETED else ChildTaskStatus.UNKNOWN
                await { store.list("conversation").single().status == expected }
                // Journal completion proves next has finished, but is not a cleanup boundary.
                assertFalse(supervisor.isReleased(a)); assertFalse(supervisor.snapshots(a).single().settled)
                assertThrows(IllegalArgumentException::class.java) { next.bind(supervisor, fast, limit = 2) }
                val b = next.bind(supervisor, fast)
                assertEquals(ChildTaskAdmission.Rejected("child_retention_limit"), submit(supervisor, b, next, "second"))
                assertEquals(0, fast.opens.get())
                supervisor.closeWhenIdle { callbacks.incrementAndGet(); idle.countDown() }
                assertFalse("blocking cancel must pin drain", idle.await(150, TimeUnit.MILLISECONDS))
                assertFalse(supervisor.isReleased(a)); assertEquals(1, model.cancels.get())
                model.returnCancel.countDown()
                assertTrue(idle.await(3, TimeUnit.SECONDS))
                assertTrue(supervisor.isReleased(a)); assertTrue(supervisor.snapshots(a).single().settled)
                assertEquals(expected, supervisor.snapshots(a).single().record.status)
                assertEquals(1, callbacks.get()); assertEquals(1, model.cancels.get())
            } finally { model.returnNext.countDown(); model.returnCancel.countDown(); drain(supervisor) }
        }
    }

    @Test fun sharedPoolPermitRemainsHeldWhenNextHasReturnedButCancelIsStillBlocked() {
        val store = Journal(); val model = BlockingCancelModel(); val fast = Model()
        val first = Parent("blocked-pool"); val second = Parent("waiting-pool")
        val supervisor = RetainedChildTasks(store, maxRetainedTasks = 2)
        val a = first.bind(supervisor, model); val b = second.bind(supervisor, fast)
        try {
            val accepted = submit(supervisor, a, first, "first") as ChildTaskAdmission.Accepted
            assertTrue(model.entered.await(3, TimeUnit.SECONDS))
            supervisor.stop(a, accepted.tasks.single().owner); supervisor.finishParent(a)
            assertTrue(model.cancelEntered.await(3, TimeUnit.SECONDS))
            model.returnNext.countDown(); assertTrue(model.nextReturned.await(3, TimeUnit.SECONDS))
            await { store.list("conversation").single().status == ChildTaskStatus.UNKNOWN }
            val waiting = submit(supervisor, b, second, "second") as ChildTaskAdmission.Accepted
            assertFalse("next run must not enter a still-cleaning pool", fast.entered.await(150, TimeUnit.MILLISECONDS))
            assertFalse(supervisor.isReleased(a)); assertFalse(supervisor.snapshots(b).single().settled)
            model.returnCancel.countDown()
            assertTrue(fast.entered.await(3, TimeUnit.SECONDS))
            await { settled(supervisor, b, waiting.tasks.single().owner.childRunId) }
            await { supervisor.isReleased(a) }
            assertEquals(1, model.cancels.get())
            assertEquals(ChildTaskStatus.COMPLETED, supervisor.snapshots(b).single().record.status)
        } finally { model.returnNext.countDown(); model.returnCancel.countDown(); drain(supervisor) }
    }

    @Test fun controllerCloseWaitsForEveryParentTransportNotJustTheFirstCancellation() {
        val first = Parent("one"); val second = Parent("two"); val one = Model(true); val two = Model(true)
        val supervisor = RetainedChildTasks(Journal())
        val a = first.bind(supervisor, one, "one"); val b = second.bind(supervisor, two, "two")
        val idle = CountDownLatch(1); val callbacks = AtomicInteger()
        try {
            submit(supervisor, a, first, "call"); submit(supervisor, b, second, "call")
            assertTrue(one.entered.await(3, TimeUnit.SECONDS)); assertTrue(two.entered.await(3, TimeUnit.SECONDS))
            supervisor.closeWhenIdle { callbacks.incrementAndGet(); idle.countDown() }
            assertTrue(one.cancelled.await(3, TimeUnit.SECONDS)); assertTrue(two.cancelled.await(3, TimeUnit.SECONDS))
            assertEquals(1L, idle.count)
            one.release.countDown(); await { supervisor.snapshots(a).single().settled }
            assertNull(a.resources); assertNotNull(b.resources); assertEquals(1L, idle.count)
            two.release.countDown(); assertTrue(idle.await(3, TimeUnit.SECONDS))
            assertNull(b.resources); assertEquals(1, callbacks.get())
        } finally { one.release.countDown(); two.release.countDown(); drain(supervisor) }
    }
}
