package app.daiyu.android.ui.screens.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SearchPhase { Idle, Loading, Results, ReadError, Cancelled }

internal data class SearchSnapshot<Q, T>(
    val generation: Long = 0,
    val request: Q? = null,
    val phase: SearchPhase = SearchPhase.Idle,
    val results: List<T> = emptyList(),
)

/**
 * Page-owned read requests only. Call start/cancel on the owning (UI) dispatcher.
 * The generation fence is independent of cooperative cancellation: even a reader that catches
 * cancellation or completes out of order cannot publish rows, errors or loading for a newer read.
 * Exception details are deliberately not exposed in UI state (they may contain private history).
 */
internal class SearchRequestState<Q : Any, T>(
    private val scope: CoroutineScope,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow(SearchSnapshot<Q, T>())
    val state: StateFlow<SearchSnapshot<Q, T>> = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun start(request: Q, debounceMillis: Long = 0, read: suspend (Q) -> List<T>) {
        val token = ++generation
        job?.cancel()
        mutable.value = SearchSnapshot(token, request, SearchPhase.Loading)
        job = scope.launch {
            try {
                if (debounceMillis > 0) delay(debounceMillis)
                val rows = withContext(readDispatcher) { read(request) }.toList()
                publish(token, request, SearchPhase.Results, rows)
            } catch (_: CancellationException) {
                publish(token, request, SearchPhase.Cancelled)
            } catch (_: Exception) {
                publish(token, request, SearchPhase.ReadError)
            }
        }
    }

    fun cancel() {
        ++generation // Invalidate before cancel can deliver a completion callback.
        job?.cancel()
        job = null
        mutable.value = SearchSnapshot(generation = generation, request = mutable.value.request, phase = SearchPhase.Cancelled)
    }

    private fun publish(token: Long, request: Q, phase: SearchPhase, rows: List<T> = emptyList()) {
        if (token == generation) mutable.value = SearchSnapshot(token, request, phase, rows)
    }
}
