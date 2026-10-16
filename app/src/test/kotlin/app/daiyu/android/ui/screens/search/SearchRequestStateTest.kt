package app.daiyu.android.ui.screens.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRequestStateTest {
    @Test fun lateSuccessCannotReplaceNewQuery() = runTest {
        val requests = SearchRequestState<String, String>(this, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        requests.start("old") { withContext(NonCancellable) { gate.await(); listOf("old row") } }
        runCurrent()
        requests.start("new") { listOf("new row") }
        runCurrent()
        val expected = requests.state.value
        gate.complete(Unit)
        runCurrent()
        assertEquals(expected, requests.state.value)
        assertEquals(SearchPhase.Results, expected.phase)
        assertEquals(listOf("new row"), expected.results)
    }

    @Test fun lateFailureCannotChangeNewLoadingOrErrorState() = runTest {
        val requests = SearchRequestState<String, String>(this, StandardTestDispatcher(testScheduler))
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        requests.start("old") { withContext(NonCancellable) { oldGate.await(); error("private history") } }
        runCurrent()
        requests.start("new") { newGate.await(); listOf("new row") }
        runCurrent()
        val expected = requests.state.value
        oldGate.complete(Unit)
        runCurrent()
        assertEquals(expected, requests.state.value)
        assertEquals(SearchPhase.Loading, requests.state.value.phase)
        newGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("new row"), requests.state.value.results)
    }

    @Test fun cancelInvalidatesBeforeNonCooperativeReaderCompletes() = runTest {
        val requests = SearchRequestState<String, String>(this, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        requests.start("query") { withContext(NonCancellable) { gate.await(); listOf("late") } }
        runCurrent()
        val startedGeneration = requests.state.value.generation
        requests.cancel()
        val cancelled = requests.state.value
        assertTrue(cancelled.generation > startedGeneration)
        assertEquals(SearchPhase.Cancelled, cancelled.phase)
        assertTrue(cancelled.results.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(cancelled, requests.state.value)
    }

    @Test fun debounceSkipsSupersededReadAndBlankCanBeCancelled() = runTest {
        val requests = SearchRequestState<String, String>(this, StandardTestDispatcher(testScheduler))
        val reads = mutableListOf<String>()
        val read: suspend (String) -> List<String> = { reads.add(it); listOf(it) }
        requests.start("a", 180, read)
        runCurrent()
        advanceTimeBy(100)
        requests.start("ab", 180, read)
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        assertEquals(listOf("ab"), reads)
        requests.start("abc", 180, read)
        requests.cancel()
        advanceTimeBy(180)
        runCurrent()
        assertEquals(listOf("ab"), reads)
        assertEquals(SearchPhase.Cancelled, requests.state.value.phase)
    }

    @Test fun errorsAreDistinctFromEmptyResultsAndRetryHasNewGeneration() = runTest {
        val requests = SearchRequestState<String, String>(this, StandardTestDispatcher(testScheduler))
        requests.start("query") { error("database read failed") }
        runCurrent()
        val failed = requests.state.value
        assertEquals(SearchPhase.ReadError, failed.phase)
        assertTrue(failed.results.isEmpty())
        requests.start("query") { emptyList() }
        runCurrent()
        assertEquals(SearchPhase.Results, requests.state.value.phase)
        assertTrue(requests.state.value.generation > failed.generation)
        assertTrue(requests.state.value.results.isEmpty())
    }

    @Test fun changingConversationWithSameKeywordInvalidatesOldScope() = runTest {
        val requests = SearchRequestState<MessageSearchRequest, String>(this, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        requests.start(MessageSearchRequest("same", "a")) {
            withContext(NonCancellable) { gate.await(); listOf("a") }
        }
        runCurrent()
        requests.start(MessageSearchRequest("same", "b")) { listOf(checkNotNull(it.conversationId)) }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(MessageSearchRequest("same", "b"), requests.state.value.request)
        assertEquals(listOf("b"), requests.state.value.results)
    }

    @Test fun retrySameQueryRejectsTheFirstAttemptsLateError() = runTest {
        val requests = SearchRequestState<String, String>(this, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        requests.start("same") { withContext(NonCancellable) { gate.await(); error("late error") } }
        runCurrent()
        requests.start("same") { listOf("retried") }
        runCurrent()
        val expected = requests.state.value
        gate.complete(Unit)
        runCurrent()
        assertEquals(expected, requests.state.value)
        assertEquals(listOf("retried"), requests.state.value.results)
    }

    @Test fun closingRejectsLateErrorsAsWellAsRows() = runTest {
        val requests = SearchRequestState<String, String>(this, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        requests.start("query") { withContext(NonCancellable) { gate.await(); error("late error") } }
        runCurrent()
        requests.cancel()
        val cancelled = requests.state.value
        gate.complete(Unit)
        runCurrent()
        assertEquals(cancelled, requests.state.value)
        assertEquals(SearchPhase.Cancelled, requests.state.value.phase)
    }

    @Test fun readerCancellationIsNotAReadError() = runTest {
        val requests = SearchRequestState<String, String>(this, StandardTestDispatcher(testScheduler))
        requests.start("query") { throw CancellationException("cancelled") }
        runCurrent()
        assertEquals(SearchPhase.Cancelled, requests.state.value.phase)
        assertTrue(requests.state.value.results.isEmpty())
    }
}
