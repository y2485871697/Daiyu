package app.daiyu.android.question

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.question.*
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class QuestionCardBindingTest : InstrumentationTestCase() {
    private val owner = QuestionIdentity("conversation", "run", "owner", "call")
    private class Queue : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.add(command) }
        fun drain() { while (work.isNotEmpty()) work.remove().run() }
    }
    private class Store : QuestionStore {
        val rows = linkedMapOf<String, QuestionRecord>()
        var failInterrupt = false
        var failReadAfterAnswer = false
        override fun get(callId: String) = rows[callId]
        override fun records(): List<QuestionRecord> {
            if (failReadAfterAnswer && rows.values.any { it.answer != null }) error("read failed")
            return rows.values.toList()
        }
        override fun create(record: QuestionRecord): Boolean {
            if (record.callId in rows) return false
            rows[record.callId] = record; return true
        }
        override fun answer(callId: String, answer: QuestionAnswer): Boolean {
            val row = rows[callId] ?: return false
            if (row.answer != null || row.interrupted) return false
            rows[callId] = row.copy(answer = answer); return true
        }
        override fun interruptPending() = interruptPending("")
        override fun interruptPending(ownerPrefix: String) {
            if (failInterrupt) error("write failed")
            rows.replaceAll { key, value -> if (key.startsWith(ownerPrefix) && value.answer == null) value.copy(interrupted = true) else value }
        }
    }
    private fun registry(store: Store): QuestionRegistry {
        val registry = QuestionRegistry(store, owner)
        registry.barrier.register(owner.storageKey, UserQuestion("Title", "Choose",
            listOf(QuestionOption("one", "First"), QuestionOption("two", "Second"))))
        return registry
    }
    private fun onMain(work: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { work() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun await(latch: CountDownLatch) { assertTrue("callback timed out", latch.await(5, TimeUnit.SECONDS)) }

    fun testWrongRegistryOwnerRejected() {
        val registry = registry(Store())
        try { QuestionCardBinding(registry, owner.copy(ownerId = "wrong"), Queue()) { true }; fail("owner accepted") }
        catch (_: IllegalArgumentException) { }
        registry.close()
    }
    fun testStopFencesAlreadyQueuedAnswerBeforeWriterExecutes() {
        val store = Store(); val registry = registry(store); val queue = Queue()
        val binding = QuestionCardBinding(registry, owner, queue) { true }
        val done = CountDownLatch(2)
        var reply: QuestionCardView.Reply? = null
        var stopped = false
        binding.submit(owner, QuestionAnswer.Selected("one")) { reply = it; done.countDown() }
        binding.stop { stopped = it; done.countDown() }
        queue.drain(); await(done)
        assertEquals(QuestionCardView.Reply.STOPPED, reply)
        assertTrue(stopped)
        assertNull(store.get(owner.storageKey)!!.answer)
        assertTrue(store.get(owner.storageKey)!!.interrupted)
        registry.close()
    }
    fun testDetachSuppressesQueuedAttachmentRefresh() {
        val registry = registry(Store()); val queue = Queue()
        val binding = QuestionCardBinding(registry, owner, queue) { true }
        var displayed = 0
        var unavailable = false
        var cleanup = false
        val detached = CountDownLatch(1)
        onMain {
            binding.attach({ displayed++ }, { unavailable = true })
            binding.detach { cleanup = it; detached.countDown() }
        }
        queue.drain(); await(detached); instrumentation.waitForIdleSync()
        assertEquals(0, displayed)
        assertFalse(unavailable)
        assertTrue(cleanup)
        registry.close()
    }
    fun testDetachImmediatelySuppressesStaleAttachBeforeWriterRuns() {
        val store = Store(); val registry = registry(store); val queue = Queue()
        val binding = QuestionCardBinding(registry, owner, queue) { true }
        var reads = 0
        val detached = CountDownLatch(1)
        onMain {
            binding.attach({ reads++ }, { fail("stale attachment must not publish") })
            binding.detach { detached.countDown() }
        }
        assertEquals(2, queue.work.size)
        queue.work.remove().run()
        assertEquals("stale attach must not enqueue a database refresh", 1, queue.work.size)
        queue.drain(); await(detached); instrumentation.waitForIdleSync()
        assertEquals(0, reads)
        assertFalse(store.get(owner.storageKey)!!.interrupted)
        registry.close()
    }

    fun testDetachedCallbackCannotEnqueueMoreReadsBeforeQueuedDetachRuns() {
        val registry = registry(Store()); val queue = Queue()
        val binding = QuestionCardBinding(registry, owner, queue) { true }
        onMain { binding.attach({}, { fail("unexpected unavailable") }) }
        queue.drain(); instrumentation.waitForIdleSync()
        val detached = CountDownLatch(1)
        onMain { binding.detach { detached.countDown() } }
        assertEquals(1, queue.work.size)
        registry.submit(owner, QuestionAnswer.Selected("one"))
        assertEquals("old listener must not enqueue work after detach fence", 1, queue.work.size)
        queue.drain(); await(detached)
        registry.close()
    }

    fun testRejectedDetachReportsFailureWithoutThrowingOnMain() {
        val registry = registry(Store())
        val binding = QuestionCardBinding(registry, owner, Executor { throw RejectedExecutionException() }) { true }
        val done = CountDownLatch(1); var detached = true
        onMain { binding.detach { detached = it; done.countDown() } }
        await(done); assertFalse(detached); registry.close()
    }
    fun testInterruptFailureCanBeRetriedWithoutReenablingAnswers() {
        val store = Store(); val registry = registry(store); val queue = Queue()
        val binding = QuestionCardBinding(registry, owner, queue) { true }
        store.failInterrupt = true
        val first = CountDownLatch(1); var committed = true
        binding.stop { committed = it; first.countDown() }; queue.drain(); await(first)
        assertFalse(committed)
        store.failInterrupt = false
        val next = CountDownLatch(1)
        binding.stop { committed = it; next.countDown() }; queue.drain(); await(next)
        assertTrue(committed); assertTrue(store.get(owner.storageKey)!!.interrupted)
        registry.close()
    }
    fun testCommittedAnswerWithRefreshFailureIsNotWriteFailure() {
        val store = Store(); val registry = registry(store); val queue = Queue()
        val binding = QuestionCardBinding(registry, owner, queue) { true }
        store.failReadAfterAnswer = true
        val done = CountDownLatch(1); var reply: QuestionCardView.Reply? = null
        binding.submit(owner, QuestionAnswer.Selected("one")) { reply = it; done.countDown() }
        queue.drain(); await(done)
        assertEquals(QuestionCardView.Reply.SAVED_REFRESH_FAILED, reply)
        assertEquals(QuestionAnswer.Selected("one"), store.get(owner.storageKey)!!.answer)
        registry.close()
    }
}
