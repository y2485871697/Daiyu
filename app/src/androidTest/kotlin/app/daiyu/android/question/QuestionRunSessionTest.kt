package app.daiyu.android.question

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.ToolCallId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.question.*
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class QuestionRunSessionTest : InstrumentationTestCase() {
    private val owner = QuestionIdentity("conversation", "run", "owner", "provider-call")
    private val arguments = """{"title":"Choose","question":"Which?","options":[{"id":"one","label":"One"},{"id":"two","label":"Two"}]}"""
    private fun invocation(id: String = owner.callId, body: String = arguments) =
        ToolInvocation(ToolCallId(id), ASK_USER_TOOL_NAME, body)
    private class Queue : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.add(command) }
        fun drain() { while (work.isNotEmpty()) work.remove().run() }
    }
    private class Store : QuestionStore {
        val rows = linkedMapOf<String, QuestionRecord>()
        var creates = 0
        var interruptFails = false
        override fun get(callId: String) = rows[callId]
        override fun records() = rows.values.toList()
        override fun create(record: QuestionRecord): Boolean {
            creates++
            if (rows.containsKey(record.callId)) return false
            rows[record.callId] = record; return true
        }
        override fun answer(callId: String, answer: QuestionAnswer): Boolean {
            val old = rows[callId] ?: return false
            if (old.answer != null || old.interrupted) return false
            rows[callId] = old.copy(answer = answer); return true
        }
        override fun interruptPending() = interruptPending("")
        override fun interruptPending(ownerPrefix: String) {
            if (interruptFails) error("interrupt failed")
            rows.replaceAll { key, old -> if (key.startsWith(ownerPrefix) && old.answer == null)
                old.copy(interrupted = true) else old }
        }
    }
    private fun await(latch: CountDownLatch) = assertTrue("callback timed out", latch.await(5, TimeUnit.SECONDS))
    private fun idle() = instrumentation.waitForIdleSync()
    private fun stop(session: QuestionRunSession, writer: Queue, expected: Boolean = true) {
        val done = CountDownLatch(1); var result = !expected
        session.stop { result = it; done.countDown() }; writer.drain(); await(done)
        assertEquals(expected, result)
    }
    fun testBeginDoesNotReadOrWriteUntilWriterRunsAndAnswerBeforeWaiterIsDeliveredOnce() {
        val store = Store(); val registry = QuestionRegistry(store, owner)
        val writer = Queue(); val completion = Queue()
        val session = QuestionRunSession(registry, owner, writer, completion, { true }, { _, _ -> })
        val pending = CountDownLatch(1); val result = CountDownLatch(1)
        var value: QuestionRunResult? = null; var results = 0
        assertTrue(session.begin(owner, invocation(), onPending = { pending.countDown() }, onResult = {
            value = it; results++; result.countDown()
        }))
        assertTrue(store.rows.isEmpty()); assertTrue(completion.work.isEmpty())
        writer.drain(); await(pending)
        val submitted = CountDownLatch(1)
        session.binding.submit(owner, QuestionAnswer.Selected("one")) {
            assertEquals(QuestionCardView.Reply.SAVED, it); submitted.countDown()
        }
        writer.drain(); await(submitted)
        completion.drain(); await(result); idle()
        assertEquals(QuestionRunResult.Answered(owner, QuestionAnswer.Selected("one")), value)
        assertEquals(1, results); assertEquals(1, store.creates)
        assertFalse(session.begin(owner, invocation(), onPending = {}, onResult = { fail("duplicate") }))
        stop(session, writer); registry.close()
    }
    fun testIdentityBatchAndStrictJsonRejectWithoutSchedulingOrStorage() {
        val store = Store(); val registry = QuestionRegistry(store, owner)
        val writer = Queue(); val completion = Queue()
        val session = QuestionRunSession(registry, owner, writer, completion, { true }, { _, _ -> })
        for (wrong in listOf(owner.copy(conversationId = "other"), owner.copy(runId = "other"),
            owner.copy(ownerId = "other"), owner.copy(callId = "other"))) {
            assertFalse(session.begin(wrong, invocation(), onPending = {}, onResult = { fail("rejected input") }))
        }
        assertFalse(session.begin(owner, invocation(), listOf(ASK_USER_TOOL_NAME, "device_status"), {}, {}))
        assertFalse(session.begin(owner, invocation(body = arguments.replace("\"Choose\"", "\"Choose\",\"title\":\"duplicate\"")), onPending = {}, onResult = {}))
        assertTrue(writer.work.isEmpty()); assertTrue(completion.work.isEmpty()); assertTrue(store.rows.isEmpty())
        registry.close()
    }
    fun testSecondConsumerAndSecondPendingCannotReplaceFirstCallback() {
        val store = Store(); val registry = QuestionRegistry(store, owner)
        val writer = Queue(); val completion = Queue()
        val session = QuestionRunSession(registry, owner, writer, completion, { true }, { _, _ -> })
        val result = CountDownLatch(1)
        assertTrue(session.begin(owner, invocation(), onPending = {}, onResult = { result.countDown() }))
        assertFalse(session.begin(owner, invocation(body = arguments.replace("Which?", "Different?")), onPending = {}, onResult = { fail("second") }))
        val next = owner.copy(callId = "second")
        assertFalse(session.begin(next, invocation("second"), onPending = {}, onResult = { fail("second") }))
        writer.drain(); registry.submit(owner, QuestionAnswer.Selected("two")); completion.drain(); await(result)
        assertEquals(1, store.creates); stop(session, writer); registry.close()
    }
    fun testStopBeforeQueuedRegistrationCreatesNoQuestionAndCompletesCleanup() {
        val store = Store(); val registry = QuestionRegistry(store, owner)
        val writer = Queue(); val completion = Queue()
        val session = QuestionRunSession(registry, owner, writer, completion, { true }, { _, _ -> })
        val result = CountDownLatch(1); var value: QuestionRunResult? = null
        assertTrue(session.begin(owner, invocation(), onPending = { fail("stopped pending") }, onResult = { value = it; result.countDown() }))
        stop(session, writer); await(result)
        assertEquals(QuestionRunResult.Stopped(owner), value)
        assertTrue(store.rows.isEmpty()); assertTrue(completion.work.isEmpty())
        assertFalse(session.begin(owner, invocation(), onPending = {}, onResult = {})); registry.close()
    }
    fun testStopDoesNotReportCleanupBeforeQueuedWaiterExits() {
        val registry = QuestionRegistry(Store(), owner); val writer = Queue(); val completion = Queue()
        val session = QuestionRunSession(registry, owner, writer, completion, { true }, { _, _ -> })
        session.begin(owner, invocation(), onPending = {}, onResult = {})
        writer.drain(); idle()
        val done = CountDownLatch(1)
        session.stop { assertTrue(it); done.countDown() }; writer.drain(); idle()
        assertEquals(1L, done.count)
        completion.drain(); await(done); registry.close()
    }
    fun testDurableAnswerSavedBeforeStopDoesNotDeliverLiveContinuationAfterFence() {
        val store = Store(); val registry = QuestionRegistry(store, owner)
        val writer = Queue(); val completion = Queue()
        val session = QuestionRunSession(registry, owner, writer, completion, { true }, { _, _ -> })
        val result = CountDownLatch(1); var value: QuestionRunResult? = null
        session.begin(owner, invocation(), onPending = {}, onResult = { value = it; result.countDown() })
        writer.drain(); registry.submit(owner, QuestionAnswer.Selected("one"))
        val done = CountDownLatch(1); session.stop { assertTrue(it); done.countDown() }
        writer.drain(); completion.drain(); await(done); await(result)
        assertEquals(QuestionRunResult.Stopped(owner), value)
        assertEquals(QuestionAnswer.Selected("one"), store.get(owner.storageKey)!!.answer); registry.close()
    }
    fun testCompletionRejectionFencesSessionAndInterruptsOrphanWithoutReplay() {
        val store = Store(); val registry = QuestionRegistry(store, owner); val writer = Queue()
        val session = QuestionRunSession(registry, owner, writer, Executor { throw RejectedExecutionException() }, { true }, { _, _ -> })
        val result = CountDownLatch(1); var value: QuestionRunResult? = null
        session.begin(owner, invocation(), onPending = {}, onResult = { value = it; result.countDown() })
        writer.drain(); await(result)
        assertTrue(value is QuestionRunResult.Unconfirmed)
        assertTrue(store.get(owner.storageKey)!!.interrupted)
        assertFalse(session.begin(owner, invocation(), onPending = {}, onResult = {}))
        stop(session, writer); assertEquals(1, store.creates); registry.close()
    }
    fun testFailedInterruptionCanBeRetriedAndCannotReenableAnswers() {
        val store = Store(); val registry = QuestionRegistry(store, owner)
        val writer = Queue(); val completion = Queue()
        val session = QuestionRunSession(registry, owner, writer, completion, { true }, { _, _ -> })
        session.begin(owner, invocation(), onPending = {}, onResult = {})
        writer.drain(); idle(); store.interruptFails = true
        stop(session, writer, false); completion.drain()
        assertFalse(session.begin(owner, invocation(), onPending = {}, onResult = {}))
        store.interruptFails = false; stop(session, writer)
        assertTrue(store.get(owner.storageKey)!!.interrupted); registry.close()
    }
    fun testOwnerDeathSuppressesLateAnswerAndNewOwnerDoesNotConsumeOldAnswer() {
        val store = Store(); val registry = QuestionRegistry(store, owner)
        val writer = Queue(); val completion = Queue(); var live = true
        val session = QuestionRunSession(registry, owner, writer, completion, { live }, { _, _ -> })
        val result = CountDownLatch(1); var value: QuestionRunResult? = null
        session.begin(owner, invocation(), onPending = {}, onResult = { value = it; result.countDown() })
        writer.drain(); registry.submit(owner, QuestionAnswer.Selected("one")); live = false
        completion.drain(); await(result); assertEquals(QuestionRunResult.Stopped(owner), value)
        stop(session, writer); registry.close()
        val nextOwner = owner.copy(ownerId = "new-owner")
        val nextRegistry = QuestionRegistry(store, nextOwner)
        val nextSession = QuestionRunSession(nextRegistry, nextOwner, writer, completion, { true }, { _, _ -> })
        nextSession.begin(nextOwner, invocation(), onPending = {}, onResult = {})
        writer.drain(); assertNull(store.get(nextOwner.storageKey)!!.answer)
        val done = CountDownLatch(1); nextSession.stop { done.countDown() }; writer.drain(); completion.drain(); await(done)
        nextRegistry.close()
    }
    fun testWaitingAnswerDoesNotOccupySerialWriterOrControlThread() {
        val store = Store(); val registry = QuestionRegistry(store, owner)
        val writer = Executors.newSingleThreadExecutor(); val completion = Executors.newSingleThreadExecutor()
        val session = QuestionRunSession(registry, owner, writer, completion, { true }, { _, _ -> })
        val pending = CountDownLatch(1); val result = CountDownLatch(1)
        val value = AtomicReference<QuestionRunResult>()
        try {
            session.begin(owner, invocation(), onPending = { pending.countDown() }, onResult = { value.set(it); result.countDown() })
            await(pending)
            val read = CountDownLatch(1); writer.execute { registry.records(); read.countDown() }; await(read)
            val submitted = CountDownLatch(1)
            session.binding.submit(owner, QuestionAnswer.Selected("two")) { submitted.countDown() }
            await(submitted); await(result)
            assertEquals(QuestionRunResult.Answered(owner, QuestionAnswer.Selected("two")), value.get())
            val done = CountDownLatch(1); session.stop { assertTrue(it); done.countDown() }; await(done)
        } finally { registry.close(); writer.shutdownNow(); completion.shutdownNow() }
    }
    fun testPreInterruptedCompletionFencesAndCleansDurablePending() {
        val store = Store(); val registry = QuestionRegistry(store, owner); val writer = Queue()
        val completion = Executor { command ->
            Thread.currentThread().interrupt()
            try { command.run() } finally { Thread.interrupted() }
        }
        val session = QuestionRunSession(registry, owner, writer, completion, { true }, { _, _ -> })
        val result = CountDownLatch(1); var value: QuestionRunResult? = null
        session.begin(owner, invocation(), onPending = {}, onResult = { value = it; result.countDown() })
        writer.drain(); await(result)
        assertEquals(QuestionRunResult.Stopped(owner), value)
        assertTrue("pre-interrupted waiter must not orphan an answerable question", store.get(owner.storageKey)!!.interrupted)
        val reply = CountDownLatch(1)
        session.binding.submit(owner, QuestionAnswer.Selected("one")) {
            assertEquals(QuestionCardView.Reply.STOPPED, it); reply.countDown()
        }
        writer.drain(); await(reply)
        assertFalse(session.begin(owner.copy(callId = "next"), invocation("next"), onPending = {}, onResult = {}))
        stop(session, writer); registry.close()
    }

    fun testThrowingResultIsReportedOnceAndFailedCleanupRemainsRetryable() {
        val store = Store(); val registry = QuestionRegistry(store, owner)
        val writer = Queue(); val completion = Queue()
        val failure = IllegalStateException("owner continuation failed")
        val reported = CountDownLatch(2); val failures = mutableListOf<Exception>(); var calls = 0
        val session = QuestionRunSession(registry, owner, writer, completion, { true }, { _, problem ->
            failures.add(problem); reported.countDown()
        })
        session.begin(owner, invocation(), onPending = {}, onResult = { calls++; throw failure })
        writer.drain(); registry.submit(owner, QuestionAnswer.Selected("one")); store.interruptFails = true
        completion.drain(); idle(); writer.drain(); await(reported)
        assertEquals(1, calls); assertTrue(failures.any { it === failure })
        assertTrue(failures.any { it.message == "question_cleanup_unconfirmed" })
        assertFalse(session.begin(owner.copy(callId = "next"), invocation("next"), onPending = {}, onResult = {}))
        store.interruptFails = false; stop(session, writer); assertEquals(1, calls); registry.close()
    }

    fun testOwnerDeathBeforeWaiterStartsInterruptsPendingWithoutExternalStop() {
        val store = Store(); val registry = QuestionRegistry(store, owner)
        val writer = Queue(); val completion = Queue(); var live = true
        val session = QuestionRunSession(registry, owner, writer, completion, { live }, { _, _ -> })
        val result = CountDownLatch(1)
        session.begin(owner, invocation(), onPending = {}, onResult = {
            assertEquals(QuestionRunResult.Stopped(owner), it); result.countDown()
        })
        writer.drain(); live = false; completion.drain(); writer.drain(); await(result)
        assertTrue(store.get(owner.storageKey)!!.interrupted)
        live = true
        assertFalse(session.begin(owner.copy(callId = "next"), invocation("next"), onPending = {}, onResult = {}))
        stop(session, writer); registry.close()
    }

    fun testRealPrivateStoreAnswerPersistsAndReopenDoesNotStartContinuation() {
        val context = app.daiyu.android.testContext(instrumentation.targetContext, "question-session-${java.util.UUID.randomUUID()}-")
        val store = PrivateQuestionStore(context); val registry = QuestionRegistry(store, owner)
        val writer = Queue(); val completion = Queue()
        val session = QuestionRunSession(registry, owner, writer, completion, { true }, { _, _ -> })
        val result = CountDownLatch(1)
        try {
            session.begin(owner, invocation(), onPending = {}, onResult = { assertTrue(it is QuestionRunResult.Answered); result.countDown() })
            writer.drain(); registry.submit(owner, QuestionAnswer.Selected("one")); completion.drain(); await(result)
            stop(session, writer); registry.close(); store.close()
            PrivateQuestionStore(context).use { reopened ->
                assertEquals(QuestionAnswer.Selected("one"), reopened.get(owner.storageKey)!!.answer)
                assertTrue(reopened.pending().isEmpty())
            }
        } finally { registry.close(); store.close(); context.deleteDatabase("questions.db") }
    }
}
