package app.daiyu.android.browser

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.bridge.BrowserHostBridge
import app.daiyu.android.browser.bridge.BrowserOutcome
import app.daiyu.android.browser.runtime.AsyncBrowserBackend
import app.daiyu.android.browser.runtime.BrowserQueuedExecutionPort
import app.daiyu.android.browser.runtime.UiDispatcher
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.browser.session.BrowserOwner
import app.daiyu.android.browser.session.BrowserOwnerKind
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BrowserQueuedExecutionPortTest {
    private class FakeUi : UiDispatcher {
        val queue = LinkedBlockingQueue<() -> Unit>()
        private val onUi = ThreadLocal.withInitial { false }
        override fun isUiThread() = onUi.get()
        override fun post(task: () -> Unit) { queue.add(task) }
        fun asUi(task: () -> Unit) {
            check(!isUiThread())
            onUi.set(true)
            try { task() } finally { onUi.set(false) }
        }
        fun next(): () -> Unit = checkNotNull(queue.poll(3, TimeUnit.SECONDS)) { "UI task missing" }
        fun runNext() { val task = next(); asUi(task) }
    }

    private class FakeBackend(private val ui: FakeUi) : AsyncBrowserBackend {
        val starts = CopyOnWriteArrayList<BrowserTabLease>()
        val cancellations = LinkedBlockingQueue<BrowserTabLease>()
        val futures = CopyOnWriteArrayList<CompletableFuture<BrowserExecutionResult>>()
        var onStart: (() -> Unit)? = null
        var immediate: BrowserExecutionResult? = null
        override fun execute(command: BrowserCommand, lease: BrowserTabLease): CompletableFuture<BrowserExecutionResult> {
            check(ui.isUiThread())
            starts.add(lease)
            onStart?.invoke()
            return CompletableFuture<BrowserExecutionResult>().also {
                futures.add(it)
                immediate?.let { value -> it.complete(value) }
            }
        }
        override fun cancel(lease: BrowserTabLease) { cancellations.add(lease) }
        fun cancelled(): BrowserTabLease = checkNotNull(cancellations.poll(3, TimeUnit.SECONDS))
    }

    private class Call<T>(block: () -> T) {
        val result = CompletableFuture<T>()
        @Volatile var interruptedAtExit = false
        val worker = thread(isDaemon = true, name = "browser-port-test-worker") {
            try {
                val value = block()
                interruptedAtExit = Thread.currentThread().isInterrupted
                result.complete(value)
            } catch (e: Throwable) {
                interruptedAtExit = Thread.currentThread().isInterrupted
                result.completeExceptionally(e)
            }
        }
        fun await(): T = result.get(3, TimeUnit.SECONDS)
    }

    private class Fixture(timeout: Long = 2_000, access: BrowserAccess = BrowserAccess.FULL) {
        val owner = BrowserOwner("queued-test", BrowserOwnerKind.MAIN)
        val sessions = BrowserSessionRegistry().apply { open(BrowserGrant(owner, access)) }
        val lease = sessions.newTab(owner)
        val ui = FakeUi()
        val backend = FakeBackend(ui)
        val port = BrowserQueuedExecutionPort(sessions, ui, backend, timeout)
        val bridge = BrowserHostBridge(sessions, port)
        fun call() = Call { bridge.executeSafely(owner, BrowserCommand("get_text", lease.tabId)) }
    }

    private fun assertFailure(block: () -> Unit) {
        try { block(); fail("expected failure") } catch (_: IllegalStateException) { }
    }

    @Test fun normalExecutionStartsOnUiWithoutWaitingForBackendAndUnregisters() {
        val f = Fixture()
        val call = f.call()
        f.ui.runNext()
        assertEquals(listOf(f.lease), f.backend.starts)
        assertFalse(call.result.isDone)
        f.backend.futures.single().complete(BrowserExecutionResult("ok"))
        assertEquals(BrowserOutcome.Confirmed(BrowserExecutionResult("ok")), call.await())
        // A completed request must not retain its invalidation listener.
        f.sessions.close(f.owner)
        assertTrue(f.backend.cancellations.isEmpty())
    }

    @Test fun alreadyCompletedFutureIsSupported() {
        val f = Fixture()
        f.backend.immediate = BrowserExecutionResult("inline completion")
        val call = f.call()
        f.ui.runNext()
        assertEquals(BrowserOutcome.Confirmed(BrowserExecutionResult("inline completion")), call.await())
        assertEquals(1, f.backend.starts.size)
    }

    @Test fun closeOrTakeoverBeforeEnqueueRejectsStaleLease() {
        for (takeover in listOf(false, true)) {
            val f = Fixture()
            if (takeover) f.sessions.releaseTakeover(f.sessions.takeover(f.lease)) else f.sessions.close(f.owner)
            val call = Call { f.port.execute(BrowserCommand("get_text", f.lease.tabId), f.lease) }
            try { call.await(); fail("expected stale-lease failure") } catch (_: ExecutionException) { }
            assertTrue(f.ui.queue.isEmpty())
            assertTrue(f.backend.starts.isEmpty())
            assertEquals(f.lease, f.backend.cancelled())
        }
    }

    @Test fun closeOrTakeoverAfterEnqueueButBeforeUiStartIsUnknownAndNeverExecutes() {
        for (takeover in listOf(false, true)) {
            val f = Fixture()
            val call = f.call()
            val queued = f.ui.next()
            if (takeover) f.sessions.releaseTakeover(f.sessions.takeover(f.lease)) else f.sessions.close(f.owner)
            assertEquals(BrowserOutcome.Unknown, call.await())
            assertEquals(f.lease, f.backend.cancelled())
            f.ui.asUi(queued)
            assertTrue(f.backend.starts.isEmpty())
            assertTrue(f.backend.cancellations.isEmpty())
        }
    }

    @Test fun closeOrTakeoverWhileInFlightWakesWaiterAndFencesLateResult() {
        for (takeover in listOf(false, true)) {
            val f = Fixture()
            val call = f.call()
            f.ui.runNext()
            if (takeover) f.sessions.releaseTakeover(f.sessions.takeover(f.lease)) else f.sessions.close(f.owner)
            assertEquals(BrowserOutcome.Unknown, call.await())
            assertEquals(f.lease, f.backend.cancelled())
            f.backend.futures.single().complete(BrowserExecutionResult("late"))
            assertEquals(BrowserOutcome.Unknown, call.await())
            assertEquals(1, f.backend.starts.size)
            assertTrue(f.backend.cancellations.isEmpty())
        }
    }

    @Test fun completionFencesEvenBeforeInvalidationCallbackCanRun() {
        val f = Fixture()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val firstListener = f.sessions.onInvalidated(f.lease) {
            callbackEntered.countDown()
            check(releaseCallback.await(3, TimeUnit.SECONDS))
        }
        val call = f.call()
        f.ui.runNext()
        val closing = Call { f.sessions.close(f.owner) }
        try {
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
            f.backend.futures.single().complete(BrowserExecutionResult("too late"))
            assertEquals(BrowserOutcome.Unknown, call.await())
            assertEquals(f.lease, f.backend.cancelled())
        } finally {
            releaseCallback.countDown()
            firstListener.close()
            closing.await()
        }
        assertTrue(f.backend.cancellations.isEmpty())
    }

    @Test fun timeoutBeforeUiStartIsUnknownDoesNotReplayAndDetachesListener() {
        val f = Fixture(timeout = 100)
        val call = f.call()
        val queued = f.ui.next()
        assertEquals(BrowserOutcome.Unknown, call.await())
        assertEquals(f.lease, f.backend.cancelled())
        f.ui.asUi(queued)
        f.sessions.close(f.owner)
        assertTrue(f.backend.starts.isEmpty())
        assertTrue(f.backend.cancellations.isEmpty())
    }

    @Test fun timeoutInFlightIsUnknownAndLateFutureCannotBecomeSuccess() {
        val f = Fixture(timeout = 500)
        val call = f.call()
        f.ui.runNext()
        assertEquals(1, f.backend.starts.size)
        assertEquals(BrowserOutcome.Unknown, call.await())
        assertEquals(f.lease, f.backend.cancelled())
        f.backend.futures.single().complete(BrowserExecutionResult("late timeout result"))
        f.sessions.close(f.owner)
        assertEquals(BrowserOutcome.Unknown, call.await())
        assertEquals(1, f.backend.starts.size)
        assertTrue(f.backend.cancellations.isEmpty())
    }

    @Test fun interruptBeforeOrAfterUiStartIsUnknownAndPreservesInterrupt() {
        for (started in listOf(false, true)) {
            val f = Fixture()
            val call = f.call()
            val queued = f.ui.next()
            if (started) f.ui.asUi(queued)
            call.worker.interrupt()
            assertEquals(BrowserOutcome.Unknown, call.await())
            assertTrue(call.interruptedAtExit)
            assertEquals(f.lease, f.backend.cancelled())
            if (started) f.backend.futures.single().complete(BrowserExecutionResult("late interrupt result"))
            else f.ui.asUi(queued)
            f.sessions.close(f.owner)
            assertEquals(if (started) 1 else 0, f.backend.starts.size)
            assertTrue(f.backend.cancellations.isEmpty())
        }
    }

    @Test fun alreadyInterruptedWorkerDoesNotEnqueueOrClearInterrupt() {
        val f = Fixture()
        val call = Call {
            Thread.currentThread().interrupt()
            f.bridge.executeSafely(f.owner, BrowserCommand("get_text", f.lease.tabId))
        }
        assertEquals(BrowserOutcome.Unknown, call.await())
        assertTrue(call.interruptedAtExit)
        assertTrue(f.ui.queue.isEmpty())
        assertTrue(f.backend.starts.isEmpty())
        assertTrue(f.backend.cancellations.isEmpty())
    }

    @Test fun uiThreadSynchronousWaitIsRejectedWithoutPosting() {
        val f = Fixture()
        f.ui.asUi {
            assertFailure { f.port.execute(BrowserCommand("get_text", f.lease.tabId), f.lease) }
            assertEquals(BrowserOutcome.Unknown, f.bridge.executeSafely(f.owner, BrowserCommand("get_text", f.lease.tabId)))
        }
        assertTrue(f.ui.queue.isEmpty())
        assertTrue(f.backend.starts.isEmpty())
        assertTrue(f.backend.cancellations.isEmpty())
    }

    @Test fun uiAdmissionChecksReadOnlyPermissionEvenForDirectPortCall() {
        val f = Fixture(access = BrowserAccess.READ_ONLY)
        val call = Call { f.port.execute(BrowserCommand("reload", f.lease.tabId), f.lease) }
        f.ui.runNext()
        try { call.await(); fail("expected permission failure") } catch (_: ExecutionException) { }
        assertTrue(f.backend.starts.isEmpty())
        assertEquals(f.lease, f.backend.cancelled())
    }

    @Test fun oldLeaseCancellationCannotBeRetargetedToReusedSlot() {
        val f = Fixture()
        val old = f.call()
        f.ui.runNext()
        f.sessions.closeTab(f.lease)
        val replacement = f.sessions.newTab(f.owner)
        assertEquals(f.lease.tabId, replacement.tabId)
        assertNotEquals(f.lease.tabGeneration, replacement.tabGeneration)
        val next = f.call()
        f.ui.runNext()
        assertEquals(BrowserOutcome.Unknown, old.await())
        // This is also the lease a backend's deferred UI cancellation must preserve verbatim.
        assertEquals(f.lease, f.backend.cancelled())
        assertEquals(listOf(f.lease, replacement), f.backend.starts)
        f.backend.futures[0].complete(BrowserExecutionResult("old reply"))
        assertFalse(next.result.isDone)
        f.backend.futures[1].complete(BrowserExecutionResult("new reply"))
        assertEquals(BrowserOutcome.Confirmed(BrowserExecutionResult("new reply")), next.await())
        f.sessions.close(f.owner)
        assertTrue(f.backend.cancellations.isEmpty())
    }

    @Test fun backendStartAndSessionCloseAreLinearizedByRegistryLock() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        // Deliberately gate this fake start to inspect the admission critical section. Production
        // backends must not wait here; this bounded test gate is not an example implementation.
        f.backend.onStart = {
            entered.countDown()
            check(releaseStart.await(3, TimeUnit.SECONDS))
        }
        val call = f.call()
        val queued = f.ui.next()
        val uiCall = Call { f.ui.asUi(queued) }
        var closing: Call<Unit>? = null
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val attemptingClose = CountDownLatch(1)
            val closed = CountDownLatch(1)
            closing = Call {
                attemptingClose.countDown()
                f.sessions.close(f.owner)
                closed.countDown()
            }
            assertTrue(attemptingClose.await(3, TimeUnit.SECONDS))
            assertFalse("close must not overtake actual backend start", closed.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseStart.countDown()
            uiCall.await()
            closing?.await()
        }
        assertEquals(BrowserOutcome.Unknown, call.await())
        assertEquals(listOf(f.lease), f.backend.starts)
        assertEquals(f.lease, f.backend.cancelled())
        f.backend.futures.single().complete(BrowserExecutionResult("stale"))
        assertTrue(f.backend.cancellations.isEmpty())
    }

    @Test fun backendFailureIsUnknownAndDoesNotRetry() {
        val f = Fixture()
        val call = f.call()
        f.ui.runNext()
        f.backend.futures.single().completeExceptionally(IllegalStateException("backend failure"))
        assertEquals(BrowserOutcome.Unknown, call.await())
        assertEquals(f.lease, f.backend.cancelled())
        f.sessions.close(f.owner)
        assertEquals(1, f.backend.starts.size)
        assertTrue(f.backend.cancellations.isEmpty())
    }

    @Test fun rejectedPostCleansUpListenerAndRemainsUnknown() {
        val f = Fixture()
        val rejectingUi = object : UiDispatcher {
            override fun isUiThread() = false
            override fun post(task: () -> Unit): Unit = error("queue rejected")
        }
        val port = BrowserQueuedExecutionPort(f.sessions, rejectingUi, f.backend)
        val bridge = BrowserHostBridge(f.sessions, port)
        assertEquals(BrowserOutcome.Unknown, bridge.executeSafely(f.owner, BrowserCommand("get_text", f.lease.tabId)))
        assertEquals(f.lease, f.backend.cancelled())
        f.sessions.close(f.owner)
        assertTrue(f.backend.cancellations.isEmpty())
        assertTrue(f.backend.starts.isEmpty())
    }
}
