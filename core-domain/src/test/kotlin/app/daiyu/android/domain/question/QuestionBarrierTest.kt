package app.daiyu.android.domain.question

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class QuestionBarrierTest {
    private val valid = """{"title":"选择","question":"下一步？","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}],"recommended_option_id":"b"}"""
    private fun question() = AskUserContract.parse(valid)
    private class Store : QuestionStore {
        private val data = linkedMapOf<String, QuestionRecord>()
        val created = CountDownLatch(1)
        var interruptFailure: RuntimeException? = null
        var answerFailure: RuntimeException? = null
        var pendingFailure: RuntimeException? = null
        var interruptAttempts = 0
        override fun pending(): List<QuestionRecord> {
            pendingFailure?.let { throw it }
            return data.values.filter { it.answer == null && !it.interrupted }
        }
        override fun get(callId: String) = data[callId]
        override fun records() = data.values.toList()
        override fun create(record: QuestionRecord): Boolean { if (data.containsKey(record.callId)) return false; data[record.callId] = record; created.countDown(); return true }
        override fun answer(callId: String, answer: QuestionAnswer): Boolean {
            answerFailure?.let { throw it }
            val old = data[callId] ?: return false
            if (old.answer != null || old.interrupted) return false
            data[callId] = old.copy(answer = answer); return true
        }
        override fun interruptPending() = interruptPending("")
        override fun interruptPending(ownerPrefix: String) {
            interruptAttempts++
            interruptFailure?.let { throw it }
            data.replaceAll { _, record -> if (record.callId.startsWith(ownerPrefix) && record.answer == null) record.copy(interrupted = true) else record }
        }
    }
    private fun fails(expected: QuestionFailure, block: () -> Unit) {
        try { block(); fail("Expected $expected") } catch (error: QuestionException) { assertEquals(expected, error.failure) }
    }
    private fun startWorker(block: () -> Unit): Pair<Thread, AtomicReference<Throwable>> {
        val failure = AtomicReference<Throwable>()
        val thread = Thread { try { block() } catch (error: Throwable) { failure.set(error) } }
        thread.isDaemon = true
        thread.start()
        return thread to failure
    }
    private fun finishWorker(worker: Pair<Thread, AtomicReference<Throwable>>) {
        worker.first.join(2000)
        assertFalse("worker must finish", worker.first.isAlive)
        worker.second.get()?.let { throw AssertionError("worker failed", it) }
    }
    private fun await(latch: CountDownLatch) = assertTrue("latch timed out", latch.await(2, TimeUnit.SECONDS))

    @Test fun cleanupCallbackCanReadPendingOnAnotherThreadWithoutMonitorDeadlock() {
        val barrier = QuestionBarrier(Store())
        val ready = CountDownLatch(1)
        val queried = CountDownLatch(1)
        var published = false
        barrier.attach {
            if (it != null) { published = true; ready.countDown() }
            else if (published) {
                // Cancellation may have been observed before wait(), leaving the flag set.
                Thread.interrupted()
                val reader = startWorker { assertNull(barrier.pending()); queried.countDown() }
                await(queried)
                finishWorker(reader)
            }
        }
        val runner = startWorker {
            try { barrier.execute("c", question()); fail("expected interruption") }
            catch (_: InterruptedException) {}
        }
        try {
            await(ready); runner.first.interrupt()
            finishWorker(runner); await(queried)
        } finally { barrier.detach(); barrier.close(); runner.first.interrupt() }
    }

    @Test fun closeDuringInFlightPublishOrdersClearLastAndDropsQueuedPublish() {
        val barrier = QuestionBarrier(Store())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val shown = mutableListOf<String?>()
        barrier.attach {
            shown.add(it?.callId)
            if (it != null) { entered.countDown(); await(release) }
        }
        val publisher = startWorker { barrier.register("c", question()) }
        try {
            await(entered)
            // These calls must finish while the callback is blocked, not wait for its thread.
            barrier.submit("c", QuestionAnswer.Selected("a"))
            barrier.register("next", question())
            barrier.close()
            assertNull(barrier.pending())
            release.countDown(); finishWorker(publisher)
            assertEquals(listOf(null, "c", null), shown)
        } finally { release.countDown(); barrier.detach(); barrier.close() }
    }

    @Test fun queuedOldClearCannotEraseNextQuestion() {
        val barrier = QuestionBarrier(Store())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val shown = mutableListOf<String?>()
        barrier.attach {
            shown.add(it?.callId)
            if (it?.callId == "first") { entered.countDown(); await(release) }
        }
        val publisher = startWorker { barrier.register("first", question()) }
        try {
            await(entered)
            barrier.submit("first", QuestionAnswer.Selected("a"))
            barrier.register("next", question())
            release.countDown(); finishWorker(publisher)
            assertEquals(listOf(null, "first", "next"), shown)
            assertEquals("next", barrier.pending()!!.callId)
        } finally { release.countDown(); barrier.detach(); barrier.close() }
    }

    @Test fun detachReattachInvalidatesQueuedOldAttachment() {
        val barrier = QuestionBarrier(Store())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val old = mutableListOf<String?>(); val replacement = mutableListOf<String?>()
        barrier.attach {
            old.add(it?.callId)
            if (it != null) { entered.countDown(); await(release) }
        }
        val publisher = startWorker { barrier.register("first", question()) }
        try {
            await(entered)
            barrier.submit("first", QuestionAnswer.Selected("a"))
            barrier.register("next", question())
            barrier.detach()
            barrier.attach { replacement.add(it?.callId) }
            release.countDown(); finishWorker(publisher)
            assertEquals(listOf(null, "first"), old)
            assertEquals(listOf("next"), replacement)
        } finally { release.countDown(); barrier.detach(); barrier.close() }
    }

    @Test fun publishFailureExpiresOrphanForRegisterAndExecute() {
        for (execute in listOf(false, true)) {
            val store = Store(); val barrier = QuestionBarrier(store)
            val ui = IllegalStateException("publish failed")
            barrier.attach { if (it != null) throw ui }
            try {
                try {
                    if (execute) barrier.execute("c", question()) else barrier.register("c", question())
                    fail("expected publish failure")
                } catch (error: IllegalStateException) { assertSame(ui, error) }
                assertNull(barrier.pending()); assertTrue(store.get("c")!!.interrupted)
                fails(QuestionFailure.EXPIRED_QUESTION) { barrier.submit("c", QuestionAnswer.Selected("a")) }
            } finally { barrier.detach(); barrier.close() }
        }
    }

    @Test fun deferredPublishFailureExpiresOrphanEvenWhenRegisterAlreadyReturned() {
        val store = Store(); val barrier = QuestionBarrier(store)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val ui = IllegalStateException("late publish failed")
        val attachment = startWorker {
            barrier.attach {
                if (it == null) { entered.countDown(); await(release) } else throw ui
            }
        }
        try {
            await(entered)
            barrier.register("c", question())
            release.countDown(); attachment.first.join(2000)
            assertFalse(attachment.first.isAlive); assertSame(ui, attachment.second.get())
            assertNull(barrier.pending()); assertTrue(store.get("c")!!.interrupted)
            fails(QuestionFailure.EXPIRED_QUESTION) { barrier.submit("c", QuestionAnswer.Selected("a")) }
        } finally { release.countDown(); barrier.detach(); barrier.close() }
    }

    @Test fun deferredPublishFailureKeepsLiveExecuteWaiterAnswerable() {
        val store = Store(); val barrier = QuestionBarrier(store)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val published = CountDownLatch(1)
        val ui = IllegalStateException("late publish failed")
        val result = AtomicReference<QuestionAnswer>()
        val attachment = startWorker {
            barrier.attach {
                if (it == null) { entered.countDown(); await(release) }
                else { published.countDown(); throw ui }
            }
        }
        var runner: Pair<Thread, AtomicReference<Throwable>>? = null
        try {
            await(entered)
            runner = startWorker { result.set(barrier.execute("c", question())) }
            await(store.created)
            // Acquiring monitor after create ensures execute has installed its waiter.
            assertEquals("c", barrier.pending()!!.callId)
            release.countDown(); await(published)
            attachment.first.join(2000)
            assertFalse(attachment.first.isAlive); assertSame(ui, attachment.second.get())
            assertEquals("c", barrier.pending()!!.callId)
            barrier.submit("c", QuestionAnswer.Selected("a"))
            finishWorker(runner)
            assertEquals(QuestionAnswer.Selected("a"), result.get())
        } finally {
            release.countDown(); barrier.detach(); barrier.close(); runner?.first?.interrupt()
        }
    }

    @Test fun committedAnswerWinsPublishFailureAndClose() {
        val store = Store(); val barrier = QuestionBarrier(store)
        val answer = QuestionAnswer.Selected("a")
        barrier.attach {
            if (it != null) {
                barrier.submit(it.callId, answer)
                barrier.close()
                throw IllegalStateException("publish failed after answer")
            }
        }
        assertEquals(answer, barrier.execute("c", question()))
        assertEquals(answer, store.get("c")!!.answer)
        assertFalse(store.get("c")!!.interrupted)
    }

    @Test fun committedAnswerPendingReadFailureIsRefreshFailureAndStillWakesRunner() {
        val store = Store()
        val waiting = CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val observed = object : QuestionStore by store {
            override fun get(callId: String): QuestionRecord? = store.get(callId).also {
                // Existing-record lookup first; execute's wait-loop read second.
                if (reads.incrementAndGet() == 2) waiting.countDown()
            }
        }
        val barrier = QuestionBarrier(observed)
        val ready = CountDownLatch(1); val result = AtomicReference<QuestionAnswer>()
        val read = IllegalStateException("pending read failed")
        val ui = IllegalStateException("clear failed")
        var published = false
        barrier.attach {
            if (it != null) { published = true; ready.countDown() }
            else if (published) throw ui
        }
        // Publish synchronously before starting the waiter so submit owns the clear drainer.
        barrier.register("c", question())
        reads.set(0)
        val runner = startWorker { result.set(barrier.execute("c", question())) }
        try {
            await(ready)
            await(waiting)
            // The loop read still holds monitor; acquiring it proves execute reached wait().
            assertEquals("c", barrier.pending()!!.callId)
            store.pendingFailure = read
            try { barrier.submit("c", QuestionAnswer.Selected("a")); fail("expected refresh failure") }
            catch (error: QuestionRefreshException) {
                assertSame(read, error.cause)
                assertSame(ui, read.suppressed.single())
            }
            finishWorker(runner)
            assertEquals(QuestionAnswer.Selected("a"), result.get())
            assertEquals(QuestionAnswer.Selected("a"), store.get("c")!!.answer)
            assertFalse(store.get("c")!!.interrupted)
            barrier.submit("c", QuestionAnswer.Selected("a"))
            fails(QuestionFailure.DUPLICATE_ANSWER) { barrier.submit("c", QuestionAnswer.Selected("b")) }
        } finally {
            store.pendingFailure = null; barrier.detach(); barrier.close(); runner.first.interrupt()
        }
    }

    @Test fun nonemptyOwnersKeepPendingAnswersAndInterruptionIsolated() {
        val store = Store()
        val a = QuestionBarrier(store, "owner-a/"); val b = QuestionBarrier(store, "owner-b/")
        a.register("owner-a/c", question()); b.register("owner-b/c", question())
        fails(QuestionFailure.INVALID_ARGUMENTS) { a.register("owner-b/new", question()) }
        fails(QuestionFailure.UNKNOWN_QUESTION) { a.submit("owner-b/c", QuestionAnswer.Selected("a")) }
        assertEquals(listOf("owner-a/c"), a.records().map { it.callId })
        a.close()
        assertTrue(store.get("owner-a/c")!!.interrupted)
        assertFalse(store.get("owner-b/c")!!.interrupted)
        assertEquals("owner-b/c", b.pending()!!.callId)
        b.submit("owner-b/c", QuestionAnswer.Selected("b"))
        assertEquals(QuestionAnswer.Selected("b"), b.execute("owner-b/c", question()))
        b.close()
    }

    @Test fun strictContractAndLimits() {
        assertTrue(question().allowOther); assertTrue(question().allowDelegation); assertTrue(question().allowNote)
        assertEquals("b", question().recommendedOptionId)
        val invalid = listOf(
            valid.replace("\"title\":\"选择\"", "\"title\":null"),
            valid.replace("\"id\":\"b\"", "\"id\":\"a\""),
            valid.replace("\"recommended_option_id\":\"b\"", "\"recommended_option_id\":\"missing\""),
            valid.replace("\"title\":\"选择\"", "\"title\":\"选择\",\"title\":\"重复\""),
            valid.replace("\"label\":\"A\"", "\"label\":\"A\",\"recommended\":true"),
            valid.replace("\"title\":\"选择\"", "\"title\":\"选择\",\"allow_other\":\"false\""),
            valid + "junk", "[]", "{\"title\":\"x\"}", valid.replace("选择", "x".repeat(201))
        )
        invalid.forEach { input -> try { AskUserContract.parse(input); fail(input) } catch (_: IllegalArgumentException) {} }
        for (count in 0..9) {
            val options = (1..count).joinToString(",") { "{\"id\":\"$it\",\"label\":\"$it\"}" }
            val input = "{\"title\":\"x\",\"question\":\"y\",\"options\":[$options]}"
            if (count in 2..8) assertEquals(count, AskUserContract.parse(input).options.size)
            else try { AskUserContract.parse(input); fail("count $count") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun mixedBatchZeroRegistrationAndNoRecommendedAnswer() {
        val store = Store(); val barrier = QuestionBarrier(store)
        fails(QuestionFailure.BATCH_CONFLICT) { barrier.register("c", question(), listOf("ask_user", "write_file")) }
        fails(QuestionFailure.BATCH_CONFLICT) { barrier.register("c", question(), listOf("ask_user", "ask_user")) }
        assertTrue(store.records().isEmpty())
        barrier.register("c", question())
        assertNull(store.get("c")!!.answer)
        fails(QuestionFailure.BATCH_CONFLICT) { barrier.register("other", question()) }
        fails(QuestionFailure.CALL_ID_CONFLICT) { barrier.register("c", question().copy(title = "different")) }
    }
    @Test fun idempotentSameAnswerRejectDifferentAndFalseCapabilities() {
        val store = Store(); val barrier = QuestionBarrier(store)
        barrier.register("c", question().copy(allowOther = false, allowDelegation = false, allowNote = false))
        fails(QuestionFailure.INVALID_ANSWER) { barrier.submit("c", QuestionAnswer.Other("x")) }
        fails(QuestionFailure.INVALID_ANSWER) { barrier.submit("c", QuestionAnswer.Delegate()) }
        fails(QuestionFailure.INVALID_ANSWER) { barrier.submit("c", QuestionAnswer.Selected("a", "note")) }
        fails(QuestionFailure.INVALID_ANSWER) { barrier.submit("c", QuestionAnswer.Selected("missing")) }
        val answer = QuestionAnswer.Selected("a")
        barrier.submit("c", answer); barrier.submit("c", answer)
        fails(QuestionFailure.DUPLICATE_ANSWER) { barrier.submit("c", QuestionAnswer.Selected("b")) }
        assertEquals(answer, barrier.execute("c", store.get("c")!!.question))
    }
    @Test fun blockingDetachReattachThenExplicitAnswer() {
        val barrier = QuestionBarrier(Store()); val ready = CountDownLatch(1); val done = CountDownLatch(1)
        val result = AtomicReference<QuestionAnswer>()
        barrier.attach { if (it != null) ready.countDown() }
        val worker = Thread { result.set(barrier.execute("c", question())); done.countDown() }
        worker.start(); assertTrue(ready.await(2, TimeUnit.SECONDS)); assertFalse(done.await(30, TimeUnit.MILLISECONDS))
        barrier.detach(); var shown: QuestionRecord? = null; barrier.attach { shown = it }; assertEquals("c", shown!!.callId)
        barrier.submit("c", QuestionAnswer.Delegate("理由"))
        assertTrue(done.await(2, TimeUnit.SECONDS)); worker.join(2000)
        assertEquals(QuestionAnswer.Delegate("理由"), result.get())
    }
    @Test fun interruptAndCloseExpireWithoutAnswer() {
        for (close in listOf(false, true)) {
            val store = Store(); val barrier = QuestionBarrier(store); val ready = CountDownLatch(1); val done = CountDownLatch(1)
            barrier.attach { if (it != null) ready.countDown() }
            val worker = Thread { try { barrier.execute("c", question()); fail("must not answer") } catch (_: InterruptedException) { done.countDown() } }
            worker.start(); assertTrue(ready.await(2, TimeUnit.SECONDS))
            if (close) barrier.close() else worker.interrupt()
            assertTrue(done.await(2, TimeUnit.SECONDS)); worker.join(2000)
            assertTrue(store.get("c")!!.interrupted); assertNull(store.get("c")!!.answer); assertNull(barrier.pending())
            if (!close) fails(QuestionFailure.EXPIRED_QUESTION) { barrier.submit("c", QuestionAnswer.Selected("a")) }
        }
    }
    @Test fun identityScopeAndRecovery() {
        val a = QuestionIdentity("conversation", "run", "owner", "call")
        assertNotEquals(a.storageKey, a.copy(conversationId = "other").storageKey)
        assertNotEquals(a.storageKey, a.copy(runId = "other").storageKey)
        assertNotEquals(a.storageKey, a.copy(ownerId = "other").storageKey)
        val store = Store(); store.create(QuestionRecord(a.storageKey, question())); store.interruptPending()
        val restored = QuestionBarrier(store); var shown: QuestionRecord? = null; restored.attach { shown = it }
        assertNull(shown); assertTrue(restored.records().single().summary().contains("中断"))
        fails(QuestionFailure.EXPIRED_QUESTION) { restored.execute(a.storageKey, question()) }
        assertNull(store.get(a.storageKey)!!.answer)
    }

    @Test fun failedCloseStillWakesClearsDisplayAndCanRetryDurableInterruption() {
        val store = Store(); val barrier = QuestionBarrier(store)
        val ready = CountDownLatch(1); val done = CountDownLatch(1)
        val result = AtomicReference<Throwable>(); var cleared = 0
        val disk = IllegalStateException("private-store-detail")
        val ui = IllegalStateException("private-display-detail")
        barrier.attach { if (it != null) ready.countDown() else { cleared++; if (cleared == 2) throw ui } }
        val worker = Thread {
            try { barrier.execute("c", question()); result.set(AssertionError("unexpected answer")) }
            catch (error: Throwable) { result.set(error) }
            finally { done.countDown() }
        }
        worker.start()
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            // Acquiring the barrier monitor after ready ensures execute is registered/waiting.
            barrier.records()
            store.interruptFailure = disk
            try { barrier.close(); fail("expected failed durable interruption") } catch (error: IllegalStateException) { assertSame(disk, error); assertSame(ui, error.suppressed.single()) }
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertTrue(result.get() is InterruptedException)
            assertEquals(2, cleared); assertNull(barrier.pending()); assertFalse(store.get("c")!!.interrupted)
            assertNull(QuestionBarrier::class.java.getDeclaredField("display").apply { isAccessible = true }.get(barrier))
            fails(QuestionFailure.CLOSED) { barrier.submit("c", QuestionAnswer.Selected("a")) }
            var shown: QuestionRecord? = store.get("c"); barrier.attach { shown = it }; assertNull(shown)
            store.interruptFailure = null
            barrier.close(); assertTrue(store.get("c")!!.interrupted)
            assertEquals(2, store.interruptAttempts)
            barrier.close(); assertEquals(2, store.interruptAttempts)
        } finally { store.interruptFailure = null; barrier.close(); worker.interrupt(); worker.join(2000) }
    }

    @Test fun failedExecuteCleanupPreservesInterruptAndExpiresInMemoryUntilCloseRetry() {
        val store = Store(); val barrier = QuestionBarrier(store)
        val ready = CountDownLatch(1); val done = CountDownLatch(1)
        val result = AtomicReference<Throwable>(); val disk = IllegalStateException("private-disk-detail")
        val displayFailure = IllegalStateException("private-display-detail")
        var registered = false
        barrier.attach {
            if (it != null) { registered = true; ready.countDown() }
            else if (registered) throw displayFailure
        }
        val worker = Thread {
            try { barrier.execute("c", question()); result.set(AssertionError("unexpected answer")) }
            catch (error: Throwable) { result.set(error) }
            finally { done.countDown() }
        }
        worker.start()
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS)); barrier.records()
            store.interruptFailure = disk; worker.interrupt()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertTrue(result.get() is InterruptedException)
            assertSame(disk, result.get().suppressed.single()); assertSame(displayFailure, disk.suppressed.single())
            assertNull(barrier.pending()); assertFalse(store.get("c")!!.interrupted); assertNull(store.get("c")!!.answer)
            assertNull(QuestionBarrier::class.java.getDeclaredField("display").apply { isAccessible = true }.get(barrier))
            fails(QuestionFailure.EXPIRED_QUESTION) { barrier.submit("c", QuestionAnswer.Selected("a")) }
            fails(QuestionFailure.EXPIRED_QUESTION) { barrier.register("c", question()) }
            store.interruptFailure = null; barrier.close()
            assertTrue(store.get("c")!!.interrupted); assertEquals(2, store.interruptAttempts)
        } finally { store.interruptFailure = null; barrier.close(); worker.interrupt(); worker.join(2000) }
    }

    @Test fun answerWriteFailureDoesNotNotifyAndCommittedRefreshFailureStillWakesRunner() {
        val store = Store(); val barrier = QuestionBarrier(store)
        val ready = CountDownLatch(1); val done = CountDownLatch(1); val result = AtomicReference<QuestionAnswer>()
        val disk = IllegalStateException("private-write-detail"); val ui = IllegalStateException("private-ui-detail")
        var registered = false
        barrier.attach { if (it != null) { registered = true; ready.countDown() } else if (registered) throw ui }
        val worker = Thread { try { result.set(barrier.execute("c", question())) } finally { done.countDown() } }
        worker.start()
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS)); barrier.records()
            store.answerFailure = disk
            try { barrier.submit("c", QuestionAnswer.Selected("a")); fail("expected write failure") } catch (error: IllegalStateException) { assertSame(disk, error) }
            assertNull(store.get("c")!!.answer); assertFalse(done.await(30, TimeUnit.MILLISECONDS))
            store.answerFailure = null
            try { barrier.submit("c", QuestionAnswer.Selected("a")); fail("expected refresh failure") }
            catch (error: QuestionRefreshException) { assertSame(ui, error.cause); assertEquals("question_refresh_failed", error.message) }
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(QuestionAnswer.Selected("a"), result.get())
            barrier.submit("c", QuestionAnswer.Selected("a"))
            fails(QuestionFailure.DUPLICATE_ANSWER) { barrier.submit("c", QuestionAnswer.Selected("b")) }
        } finally { barrier.detach(); barrier.close(); worker.interrupt(); worker.join(2000) }
    }
    @Test fun knownAnswerSkipsFallibleUnansweredCleanupAndRemainsReplayable() {
        val store = Store()
        val answerReads = java.util.concurrent.atomic.AtomicInteger()
        val observed = object : QuestionStore by store {
            override fun get(callId: String): QuestionRecord? = store.get(callId).also { row ->
                if (row?.answer != null && answerReads.incrementAndGet() >= 3)
                    throw IllegalStateException("redundant cleanup read failed")
            }
        }
        val barrier = QuestionBarrier(observed)
        val answer = QuestionAnswer.Selected("a")
        try {
            barrier.register("c", question())
            barrier.submit("c", answer)
            assertEquals(answer, barrier.execute("c", question()))
            assertEquals(2, answerReads.get())
            answerReads.set(0)
            assertEquals(answer, barrier.execute("c", question()))
            assertEquals(2, answerReads.get())
            assertEquals(answer, store.get("c")!!.answer)
            assertFalse(store.get("c")!!.interrupted)
        } finally { barrier.close() }
    }

}
