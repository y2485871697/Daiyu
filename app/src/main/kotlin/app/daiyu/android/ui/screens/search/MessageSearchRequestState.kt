package app.daiyu.android.ui.screens.search

import android.os.CancellationSignal
import android.os.OperationCanceledException
import app.daiyu.android.ui.model.MessageSearchCursor
import app.daiyu.android.ui.model.MessageSearchHit
import app.daiyu.android.ui.model.MessageSearchPage
import app.daiyu.android.ui.model.SearchCursorInvalidatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal typealias MessageSearchPageReader = (MessageSearchRequest, MessageSearchCursor?, CancellationSignal) -> MessageSearchPage
internal enum class SearchAppendPhase { Idle, Loading, ReadError, Cancelled, Invalidated }

internal data class MessageSearchSnapshot(
    val generation: Long = 0,
    val request: MessageSearchRequest? = null,
    val phase: SearchPhase = SearchPhase.Idle,
    val results: List<MessageSearchHit> = emptyList(),
    val nextCursor: MessageSearchCursor? = null,
    val appendPhase: SearchAppendPhase = SearchAppendPhase.Idle,
)

/** UI-dispatcher owned. Every page has its own signal, including retry and continuation reads. */
internal class MessageSearchRequestState(
    private val scope: CoroutineScope,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow(MessageSearchSnapshot())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var activeSignal: CancellationSignal? = null
    private var reader: MessageSearchPageReader? = null

    fun start(request: MessageSearchRequest, debounceMillis: Long = 0, read: MessageSearchPageReader) {
        invalidate()
        reader = read
        mutable.value = MessageSearchSnapshot(generation, request, SearchPhase.Loading)
        launchRead(request, null, debounceMillis, read)
    }

    fun loadMore() {
        val before = mutable.value
        val request = before.request ?: return
        val cursor = before.nextCursor ?: return
        if (before.phase != SearchPhase.Results || before.appendPhase == SearchAppendPhase.Loading) return
        val read = reader ?: return
        invalidate()
        mutable.value = before.copy(generation = generation, appendPhase = SearchAppendPhase.Loading)
        launchRead(request, cursor, 0, read)
    }

    fun cancel() {
        invalidate() // Fence before cancellation callbacks can publish.
        val before = mutable.value
        mutable.value = if (before.phase == SearchPhase.Results) before.copy(
            generation = generation, appendPhase = SearchAppendPhase.Cancelled,
        ) else MessageSearchSnapshot(generation, before.request, SearchPhase.Cancelled)
    }

    private fun invalidate() {
        ++generation
        activeSignal?.cancel()
        activeSignal = null
        job?.cancel()
        job = null
    }

    private fun launchRead(request: MessageSearchRequest, cursor: MessageSearchCursor?,
        debounceMillis: Long, read: MessageSearchPageReader) {
        val token = generation
        val signal = CancellationSignal()
        activeSignal = signal
        val before = mutable.value
        job = scope.launch {
            try {
                if (debounceMillis > 0) delay(debounceMillis)
                val page = withContext(readDispatcher) {
                    // Register BEFORE entering synchronous SQLite. A normal Job completion
                    // listener alone runs too late when rawQuery is blocking this coroutine.
                    suspendCancellableCoroutine<MessageSearchPage> { continuation ->
                        continuation.invokeOnCancellation { signal.cancel() }
                        try {
                            signal.throwIfCanceled()
                            val result = read(request, cursor, signal)
                            signal.throwIfCanceled()
                            continuation.resume(result)
                        } catch (failure: Exception) {
                            continuation.resumeWithException(failure)
                        }
                    }
                }
                if (token == generation) mutable.value = MessageSearchSnapshot(
                    token, request, SearchPhase.Results, before.results + page.results, page.nextCursor,
                )
            } catch (_: SearchCursorInvalidatedException) {
                if (token == generation) mutable.value = if (cursor == null) MessageSearchSnapshot(
                    token, request, SearchPhase.ReadError,
                ) else before.copy(nextCursor = null, appendPhase = SearchAppendPhase.Invalidated)
            } catch (_: CancellationException) {
                failed(token, before, cursor, cancelled = true)
            } catch (_: OperationCanceledException) {
                failed(token, before, cursor, cancelled = true)
            } catch (_: Exception) {
                failed(token, before, cursor, cancelled = false)
            } finally {
                if (activeSignal === signal) activeSignal = null
            }
        }.also { launched ->
            // Also covers cancellation before the IO block (or even the launch body) starts.
            launched.invokeOnCompletion { cause -> if (cause is CancellationException) signal.cancel() }
        }
    }

    private fun failed(token: Long, before: MessageSearchSnapshot, cursor: MessageSearchCursor?, cancelled: Boolean) {
        if (token != generation) return
        mutable.value = if (cursor != null) before.copy(
            appendPhase = if (cancelled) SearchAppendPhase.Cancelled else SearchAppendPhase.ReadError,
        ) else MessageSearchSnapshot(token, before.request,
            if (cancelled) SearchPhase.Cancelled else SearchPhase.ReadError)
    }
}
