package app.daiyu.android.ui.screens.search

import android.os.CancellationSignal
import app.daiyu.android.ui.model.MessageSearchCursor
import app.daiyu.android.ui.model.MessageSearchHit
import app.daiyu.android.ui.model.MessageSearchPage
import app.daiyu.android.ui.model.SearchCursorInvalidatedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [26])
@OptIn(ExperimentalCoroutinesApi::class)
class MessageSearchRequestStateTest {
    private val request = MessageSearchRequest("needle", "scope")
    private fun cursor() = MessageSearchCursor("needle", "scope", emptyList(), 0, 200)
    private fun hit(id: String) = MessageSearchHit("scope", "title", id, "needle", "assistant")

    @Test fun failedAppendKeepsRowsAndCursorAndRetriesWithIndependentSignal() = runTest {
        val state = MessageSearchRequestState(this, StandardTestDispatcher(testScheduler))
        val next = cursor()
        val signals = mutableListOf<CancellationSignal>()
        var attempts = 0
        state.start(request) { _, continuation, signal ->
            signals.add(signal)
            when {
                continuation == null -> MessageSearchPage(listOf(hit("first")), next)
                ++attempts == 1 -> error("private database detail")
                else -> MessageSearchPage(listOf(hit("second")))
            }
        }
        runCurrent()
        state.loadMore()
        state.loadMore() // Loading is single-flight.
        runCurrent()
        assertEquals(listOf(hit("first")), state.state.value.results)
        assertSame(next, state.state.value.nextCursor)
        assertEquals(SearchAppendPhase.ReadError, state.state.value.appendPhase)
        state.loadMore()
        runCurrent()
        assertEquals(listOf(hit("first"), hit("second")), state.state.value.results)
        assertNull(state.state.value.nextCursor)
        assertEquals(3, signals.toSet().size)
    }

    @Test fun historyInvalidationKeepsRowsButRequiresRestartNotAppendRetry() = runTest {
        val state = MessageSearchRequestState(this, StandardTestDispatcher(testScheduler))
        var reads = 0
        state.start(request) { _, continuation, _ ->
            reads++
            if (continuation != null) throw SearchCursorInvalidatedException()
            MessageSearchPage(listOf(hit("first")), cursor())
        }
        runCurrent()
        state.loadMore()
        runCurrent()
        assertEquals(SearchAppendPhase.Invalidated, state.state.value.appendPhase)
        assertEquals(listOf(hit("first")), state.state.value.results)
        assertNull(state.state.value.nextCursor)
        state.loadMore()
        runCurrent()
        assertEquals(2, reads)
        state.start(request) { _, _, _ -> MessageSearchPage(listOf(hit("fresh"))) }
        runCurrent()
        assertEquals(listOf(hit("fresh")), state.state.value.results)
    }

    @Test fun scopeReplacementDuringDispatchAndDebounceSkipsSupersededReads() = runTest {
        val state = MessageSearchRequestState(this, StandardTestDispatcher(testScheduler))
        val signals = mutableListOf<CancellationSignal>()
        state.start(request) { _, _, signal ->
            signals.add(signal)
            MessageSearchPage(listOf(hit("old")), cursor())
        }
        runCurrent()
        state.loadMore()
        // Cancel before its dispatched synchronous read has started.
        state.start(MessageSearchRequest("needle", "new-scope"), debounceMillis = 180) { _, _, signal ->
            signals.add(signal)
            MessageSearchPage(listOf(hit("new")))
        }
        runCurrent()
        state.cancel()
        runCurrent()
        assertEquals(1, signals.size)
        assertTrue(state.state.value.results.isEmpty())
        assertEquals(SearchPhase.Cancelled, state.state.value.phase)
    }

    @Test fun cancelledAppendIsNotReadErrorAndKeepsAlreadyPublishedRows() = runTest {
        val state = MessageSearchRequestState(this, StandardTestDispatcher(testScheduler))
        val next = cursor()
        state.start(request) { _, continuation, signal ->
            if (continuation != null) {
                signal.cancel()
                signal.throwIfCanceled()
            }
            MessageSearchPage(listOf(hit("first")), next)
        }
        runCurrent()
        state.loadMore()
        runCurrent()
        assertEquals(SearchPhase.Results, state.state.value.phase)
        assertEquals(SearchAppendPhase.Cancelled, state.state.value.appendPhase)
        assertEquals(listOf(hit("first")), state.state.value.results)
        assertSame(next, state.state.value.nextCursor)
    }

    @Test fun scopeChangeCancelsBlockingReaderAndFencesItsLateSuccess() = runBlocking {
        val state = MessageSearchRequestState(this)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var oldSignal: CancellationSignal? = null
        try {
            state.start(request) { _, _, signal ->
                oldSignal = signal
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                // Deliberately non-cooperative reader, returning after cancellation.
                MessageSearchPage(listOf(hit("late")))
            }
            withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
            val oldRead = requireNotNull(coroutineContext[Job]).children.single()
            state.start(MessageSearchRequest("needle", "other")) { _, _, signal ->
                assertFalse(signal.isCanceled)
                MessageSearchPage(listOf(hit("new")))
            }
            assertTrue(requireNotNull(oldSignal).isCanceled)
            withTimeout(5000) { state.state.first { it.phase == SearchPhase.Results } }
            val expected = state.state.value
            release.countDown()
            withTimeout(5000) { oldRead.join() }
            assertEquals(listOf(hit("new")), expected.results)
            assertEquals(expected, state.state.value)
        } finally { release.countDown(); state.cancel() }
    }

    @Test fun parentCoroutineCancellationReachesSignalWhileReadIsStillBlocking() = runBlocking {
        val owner = CoroutineScope(coroutineContext + Job())
        val state = MessageSearchRequestState(owner)
        val entered = CountDownLatch(1)
        val signalCancelled = CountDownLatch(1)
        try {
            state.start(request) { _, _, signal ->
                signal.setOnCancelListener { signalCancelled.countDown() }
                entered.countDown()
                check(signalCancelled.await(5, TimeUnit.SECONDS))
                signal.throwIfCanceled()
                error("cancelled signal did not throw")
            }
            withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
            // Do NOT use state.cancel(): this proves actual coroutine-parent cancellation is
            // registered before blocking work, rather than just a UI generation fence.
            owner.cancel()
            withContext(Dispatchers.IO) { assertTrue(signalCancelled.await(5, TimeUnit.SECONDS)) }
            withTimeout(5000) { state.state.first { it.phase == SearchPhase.Cancelled } }
            assertTrue(state.state.value.results.isEmpty())
        } finally { owner.cancel(); signalCancelled.countDown() }
    }
}
