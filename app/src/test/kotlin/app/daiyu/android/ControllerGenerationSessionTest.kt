package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.*
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.GenerationAttemptBinding
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Offline only: real Runner-issued owners, no HTTP, paid services, SQLite or media creation. */
class ControllerGenerationSessionTest {
    private val identity = GenerationPreparationIdentity("account", ConversationId("conversation"),
        RunId("run"), MessageId("assistant"))
    private val editor = GenerationEditorSnapshot(3, " exact text ", listOf("immutable-photo"))
    private val saved = Conversation(identity.conversationId, "saved", "", 1)

    private fun preparation() = ControllerGenerationPreparation.prepare(identity, editor,
        listOf(GenerationModelProfile("image", "explicit image", GenerationProtocol.OPENAI_IMAGES_JSON,
            "https://api.example/images/generations", "private-key", "explicit-model")),
        listOf(GenerationBatchSelection("batch", "image", listOf(GenerationRunConfig.Item("item", "draw")))),
        0, GenerationDownloadHosts(setOf("cdn.example")))

    private fun session(
        stop: () -> Unit = {},
        drain: (Long) -> GenerationToolsCloseResult = { GenerationToolsCloseResult.Drained },
    ): ControllerGenerationSession {
        val p = preparation()
        return ControllerGenerationSession(p.confirm(p.preview, editor, identity), stop, drain)
    }

    private fun owner(id: GenerationPreparationIdentity = identity): RunExecutionOwner {
        var result: RunExecutionOwner? = null
        val receiver = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { result = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) = error("unused")
        }
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("unused") },
            RunnerOptions(receiptBatchExecutor = receiver)) {}.run(
            Message(id.assistantMessageId, id.conversationId, id.runId, Message.ASSISTANT_ROLE,
                emptyList(), RunStatus.RUNNING), emptyList())
        return checkNotNull(result)
    }

    private fun rejects(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun acceptedBeforeBindingCannotConsumeButExactBindingReleasesItOnce() {
        val s = session()
        val receipt = GenerationPersistenceReceipt.Accepted(saved)
        s.recordPersistence(identity, receipt)
        assertSame(receipt, s.persistenceReceipt)
        assertNull(s.consumeAccepted(editor, identity))
        s.bindOwner(owner(), editor, identity, "session")
        assertNull(s.consumeAccepted(GenerationEditorSnapshot(4, editor.text, editor.attachmentIds), identity))
        assertNull(s.consumeAccepted(editor, identity.copy(accountScope = "other")))
        assertSame(receipt, s.consumeAccepted(editor, identity))
        assertNull(s.consumeAccepted(editor, identity))
        s.cleanupOnWorker(0)
        assertTrue(s.canRetire)
    }

    @Test fun explicitStopBeforeBindPreservesTrueAcceptedAndAllowsOnlyReceiptValidation() {
        for (stopFirst in listOf(false, true)) {
            val s = session()
            val receipt = GenerationPersistenceReceipt.Accepted(saved)
            if (stopFirst) s.revokeAdmission()
            s.recordPersistence(identity, receipt)
            s.revokeAdmission()
            assertSame(receipt, s.persistenceReceipt)
            assertNull(s.consumeAccepted(editor, identity))
            val real = owner()
            rejects { s.bindOwner(real, editor, identity, "session") }
            s.validateOwnerForReceipt(real, editor, identity)
            assertSame(receipt, s.consumeAccepted(editor, identity))
            rejects { s.recordPersistence(identity, GenerationPersistenceReceipt.Rejected) }
            rejects { s.token.paidWrites(real, editor, identity) }
            s.cleanupOnWorker(0)
            assertTrue(s.canRetire)
        }
    }

    @Test fun mismatchNeverTurnsDurableAcceptedIntoRejectedOrGrantsPaidWork() {
        val s = session()
        val receipt = GenerationPersistenceReceipt.Accepted(saved)
        s.recordPersistence(identity, receipt)
        rejects { s.bindOwner(owner(), GenerationEditorSnapshot(5, editor.text, editor.attachmentIds), identity, "session") }
        rejects { s.bindOwner(owner(identity.copy(assistantMessageId = MessageId("foreign"))), editor, identity, "session") }
        assertNull(s.consumeAccepted(editor, identity))
        s.revokeAdmission(); s.cleanupOnWorker(0)
        assertSame(receipt, s.persistenceReceipt)
        assertFalse(s.canRetire)
    }

    @Test fun uniqueMixedTaskAndLeafFactoryReuseTokenWithoutAnyIo() {
        val s = session(); val real = owner(); val token = s.token
        val other = WriteEffect("other", "write", "target", "digest")
        val task = s.bindOwner(real, editor, identity, "session", setOf(other))
        assertSame(task, s.bindOwner(real, editor, identity, "session", setOf(other)))
        assertTrue(task.allowedWrites.containsAll(s.token.paidWrites(real, editor, identity)))
        assertTrue(other in task.allowedWrites)
        rejects { s.bindOwner(owner(), editor, identity, "session", setOf(other)) }
        rejects { s.bindOwner(real, editor, identity, "different-session", setOf(other)) }
        val io = AtomicInteger()
        val resources = ControllerGenerationResources("account", { io.incrementAndGet(); error("no DB") },
            { "account" }, File("unused-private-root"))
        val capabilities = SessionCapabilities("session", setOf(RunGenerationTools.CAPABILITY))
        rejects { s.openLeaf(real, capabilities, TaskAuthorization("session", "run", task.allowedWrites), resources, { true }) }
        val leaf = s.openLeaf(real, capabilities, task, resources, { true })
        val tool = leaf.registeredTools.single()
        tool.prepare(tool.definition.schema.parse("{\"batch_id\":\"batch\"}"))
        assertEquals(0, io.get())
        rejects { s.openLeaf(real, capabilities, task, resources, { true }) }
        assertFalse(ControllerGenerationResources.DENY_DOWNLOADS.allows(GenerationAssetOwner("account",
            GenerationAttemptBinding("conversation", "assistant", "run", "call", RunGenerationTools.IMAGE_TOOL)),
            "https://cdn.example/signed-video"))
        s.cleanupOnWorker(0)
        assertSame(token, s.token)
        assertEquals(GenerationToolsCloseResult.Drained, s.closeResult)
    }

    @Test fun unknownParentDrainIsStickyAndRetainsTokenAndNeverRetriesPaidWork() {
        val calls = AtomicInteger()
        val s = session { calls.incrementAndGet(); GenerationToolsCloseResult.Unknown("receipt_unconfirmed") }
        val token = s.token; val real = owner()
        s.bindOwner(real, editor, identity, "session")
        s.recordPersistence(identity, GenerationPersistenceReceipt.Unknown)
        s.cleanupOnWorker(0); s.cleanupOnWorker(0)
        assertEquals(1, calls.get())
        assertSame(token, s.token)
        assertTrue(s.closeResult is GenerationToolsCloseResult.Unknown)
        assertFalse(s.canRetire)
        rejects { s.bindOwner(real, editor, identity, "session") }
        assertNull(s.consumeAccepted(editor, identity))
    }

    @Test fun saturatedIndependentCleanupLaneNeverRunsOnCallerAndLeavesRejectedSlotPending() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        val ranOn = AtomicReference<Thread?>()
        val first = session {
            ranOn.set(Thread.currentThread()); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)); GenerationToolsCloseResult.Drained
        }
        val secondDone = CountDownLatch(1)
        val second = session { secondDone.countDown(); GenerationToolsCloseResult.Drained }
        val rejectedCalls = AtomicInteger()
        val rejected = session { rejectedCalls.incrementAndGet(); GenerationToolsCloseResult.Drained }
        val lane = ControllerGenerationCleanupQueue(workers = 1, capacity = 1, timeoutMillis = 0)
        try {
            assertTrue(lane.offer(first)); assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNotSame(caller, ranOn.get())
            assertTrue(lane.offer(second))
            assertFalse(lane.offer(rejected))
            assertTrue(rejected.cleanupRequested)
            assertNull(rejected.closeResult)
            assertEquals(0, rejectedCalls.get())
            assertTrue(lane.offer(first)) // deduplicated, no second cancellation worker
            release.countDown()
            assertTrue(secondDone.await(5, TimeUnit.SECONDS))
        } finally { release.countDown(); lane.close() }
        assertFalse(lane.offer(rejected)) // shutdown never falls back to inline execution
        assertEquals(0, rejectedCalls.get())
        rejected.cleanupOnWorker(0)
    }

    @Test fun existingPositionalAndTrailingDrainConstructorsRemainCompatible() {
        val calls = AtomicInteger()
        val drain: (Long) -> GenerationToolsCloseResult = {
            calls.incrementAndGet(); GenerationToolsCloseResult.Drained
        }
        val first = preparation()
        val positional = ControllerGenerationSession(first.confirm(first.preview, editor, identity), drain)
        val second = preparation()
        val trailing = ControllerGenerationSession(second.confirm(second.preview, editor, identity)) {
            drain(it)
        }
        positional.cleanupOnWorker(0); trailing.cleanupOnWorker(0)
        assertEquals(2, calls.get())
        assertEquals(GenerationToolsCloseResult.Drained, positional.closeResult)
        assertEquals(GenerationToolsCloseResult.Drained, trailing.closeResult)
    }

    @Test fun parentStopAndFinishedStayOnWorkerAndRetirementWaitsForDrain() {
        val caller = Thread.currentThread()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>()
        lateinit var s: ControllerGenerationSession
        s = session(stop = {
            assertNotSame(caller, Thread.currentThread())
            assertFalse(Thread.holdsLock(s))
            events.add("stop"); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }, drain = {
            assertFalse(Thread.holdsLock(s))
            assertEquals(GenerationToolsCloseResult.Drained, s.token.awaitClose(0))
            events.add("drain"); GenerationToolsCloseResult.Drained
        })
        s.recordPersistence(identity, GenerationPersistenceReceipt.Rejected)
        s.revokeAdmission()
        assertTrue(events.isEmpty()) // UI revocation cannot invoke even the parent's stop request.
        val lane = ControllerGenerationCleanupQueue(timeoutMillis = 0)
        try {
            assertTrue(lane.offer(s) {
                try {
                    assertNotSame(caller, Thread.currentThread())
                    assertFalse(Thread.holdsLock(s))
                    assertTrue(s.canRetire)
                    events.add("finished")
                } catch (t: Throwable) { failure.set(t) }
                finally { finished.countDown() }
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("stop"), events.toList())
            assertTrue(s.cleanupRequested)
            assertNull(s.closeResult)
            assertFalse(s.canRetire)
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(listOf("stop", "drain", "finished"), events.toList())
        } finally { release.countDown(); lane.close() }
    }

    @Test fun stopFailureStillClosesTokenButNeverReportsDrainedOrRetriesStop() {
        val stops = AtomicInteger(); val drains = AtomicInteger()
        val finished = CountDownLatch(1)
        val s = session(stop = {
            stops.incrementAndGet(); throw IllegalStateException("private parent failure")
        }, drain = { drains.incrementAndGet(); GenerationToolsCloseResult.Drained })
        s.recordPersistence(identity, GenerationPersistenceReceipt.Rejected)
        val token = s.token
        val lane = ControllerGenerationCleanupQueue(timeoutMillis = 0)
        try {
            assertTrue(lane.offer(s) { finished.countDown() })
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(GenerationToolsCloseResult.Unknown("generation_cleanup_failed"), s.closeResult)
            assertEquals(GenerationToolsCloseResult.Drained, token.awaitClose(0))
            assertSame(token, s.token)
            assertTrue(s.cleanupRequested)
            assertFalse(s.canRetire)
            s.cleanupOnWorker(0)
            assertEquals(1, stops.get())
            assertEquals(0, drains.get())
        } finally { lane.close() }
    }

    @Test fun defaultLaneBoundsTwoWorkersAndEightSlotsWithoutDuplicateOrRejectedNotifications() {
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val finished = CountDownLatch(10)
        val stops = AtomicInteger(); val duplicateNotifications = AtomicInteger()
        val rejectedStops = AtomicInteger(); val rejectedNotifications = AtomicInteger()
        val active = List(2) { session(stop = {
            stops.incrementAndGet(); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }) }
        val waiting = List(8) { session(stop = { stops.incrementAndGet() }) }
        val rejected = session(stop = { rejectedStops.incrementAndGet() })
        val lane = ControllerGenerationCleanupQueue(timeoutMillis = 0)
        try {
            active.forEach { assertTrue(lane.offer(it) { finished.countDown() }) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            waiting.forEach { assertTrue(lane.offer(it) { finished.countDown() }) }
            // Both an active duplicate and a queued duplicate need no extra worker or slot.
            assertTrue(lane.offer(active.first()) { duplicateNotifications.incrementAndGet() })
            assertTrue(lane.offer(waiting.first()) { duplicateNotifications.incrementAndGet() })
            assertFalse(lane.offer(rejected) { rejectedNotifications.incrementAndGet() })
            assertEquals(2, stops.get())
            assertEquals(0, rejectedStops.get())
            assertTrue(rejected.cleanupRequested)
            assertNull(rejected.closeResult)
            assertFalse(rejected.canRetire)
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(10, stops.get())
            assertEquals(0, duplicateNotifications.get())
            assertEquals(0, rejectedNotifications.get())
            assertTrue(lane.offer(active.first()) { duplicateNotifications.incrementAndGet() })
            assertEquals(0, duplicateNotifications.get()) // already terminal: no inline callback
        } finally { release.countDown(); lane.close() }
        assertFalse(lane.offer(rejected) { rejectedNotifications.incrementAndGet() })
        assertEquals(0, rejectedStops.get())
        assertEquals(0, rejectedNotifications.get())
    }

    @Test fun notificationFollowsRemovalOutsideLocksAndObserverFailureDoesNotKillWorker() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val firstFinished = CountDownLatch(1); val secondFinished = CountDownLatch(1)
        val firstThread = AtomicReference<Thread?>(); val secondThread = AtomicReference<Thread?>()
        val failure = AtomicReference<Throwable?>()
        val caller = Thread.currentThread()
        val first = session { entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            GenerationToolsCloseResult.Drained }
        val second = session()
        val lane = ControllerGenerationCleanupQueue(workers = 1, capacity = 1, timeoutMillis = 0)
        // Observe the private marker only to distinguish removal-before-notify from notify-before-removal.
        val field = ControllerGenerationCleanupQueue::class.java.getDeclaredField("queued")
        field.isAccessible = true
        val queued = field.get(lane) as Set<*>
        try {
            assertTrue(lane.offer(first) {
                firstThread.set(Thread.currentThread())
                try {
                    assertNotSame(caller, Thread.currentThread())
                    assertFalse(Thread.holdsLock(queued))
                    assertFalse(Thread.holdsLock(first))
                    synchronized(queued) { assertFalse(queued.contains(first)) }
                    assertEquals(GenerationToolsCloseResult.Drained, first.closeResult)
                } catch (t: Throwable) { failure.set(t) }
                finally { firstFinished.countDown() }
                throw AssertionError("observer failure must be isolated")
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(lane.offer(second) {
                secondThread.set(Thread.currentThread()); secondFinished.countDown()
            })
            release.countDown()
            assertTrue(firstFinished.await(5, TimeUnit.SECONDS))
            assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertSame(firstThread.get(), secondThread.get())
        } finally { release.countDown(); lane.close() }
    }

    @Test fun finishedNotificationDoesNotRetirePendingOrUnconsumedPersistence() {
        val missing = session()
        val unknown = session().also { it.recordPersistence(identity, GenerationPersistenceReceipt.Unknown) }
        val accepted = session().also {
            it.bindOwner(owner(), editor, identity, "session")
            it.recordPersistence(identity, GenerationPersistenceReceipt.Accepted(saved))
        }
        val finished = CountDownLatch(3)
        val failure = AtomicReference<Throwable?>()
        val lane = ControllerGenerationCleanupQueue(timeoutMillis = 0)
        try {
            listOf(missing, unknown, accepted).forEach { s ->
                assertTrue(lane.offer(s) {
                    try {
                        assertEquals(GenerationToolsCloseResult.Drained, s.closeResult)
                        assertFalse(s.canRetire)
                    } catch (t: Throwable) { failure.set(t) }
                    finally { finished.countDown() }
                })
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertNull(missing.persistenceReceipt)
            assertSame(GenerationPersistenceReceipt.Unknown, unknown.persistenceReceipt)
            assertNotNull(accepted.consumeAccepted(editor, identity))
            assertTrue(accepted.canRetire)
            assertFalse(missing.canRetire)
            assertFalse(unknown.canRetire)
        } finally { lane.close() }
    }

    @Test fun acceptedReceiptAndStopRaceDoesNotLosePersistenceTruth() {
        repeat(10) {
            val s = session(); val start = CountDownLatch(1)
            val accepted = GenerationPersistenceReceipt.Accepted(saved)
            val failure = AtomicReference<Throwable?>()
            val writer = Thread {
                try { start.await(); s.recordPersistence(identity, accepted) }
                catch (t: Throwable) { failure.set(t) }
            }
            val stop = Thread { start.await(); s.revokeAdmission() }
            writer.start(); stop.start(); start.countDown()
            writer.join(5_000); stop.join(5_000)
            assertFalse(writer.isAlive); assertFalse(stop.isAlive); assertNull(failure.get())
            assertSame(accepted, s.persistenceReceipt)
            assertNull(s.consumeAccepted(editor, identity))
            s.cleanupOnWorker(0)
        }
    }
    @Test fun fatalStopPublishesUnknownBeforeCallbackAndIsNotRetried() {
        val stops = AtomicInteger()
        val failed = session(stop = { stops.incrementAndGet(); throw AssertionError("stop failed") })
        val notified = CountDownLatch(1)
        val next = CountDownLatch(1)
        val observed = AtomicReference<GenerationToolsCloseResult?>()
        val lane = ControllerGenerationCleanupQueue(workers = 1, capacity = 1, timeoutMillis = 0)
        try {
            assertTrue(lane.offer(failed) { observed.set(failed.closeResult); notified.countDown() })
            assertTrue(notified.await(5, TimeUnit.SECONDS))
            assertTrue(observed.get() is GenerationToolsCloseResult.Unknown)
            assertFalse(failed.canRetire)
            failed.cleanupOnWorker(0)
            assertEquals(1, stops.get())
            assertTrue(lane.offer(session()) { next.countDown() })
            assertTrue(next.await(5, TimeUnit.SECONDS))
        } finally { lane.close() }
    }

}
