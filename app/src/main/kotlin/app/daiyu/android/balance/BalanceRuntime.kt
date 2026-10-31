package app.daiyu.android.balance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-retained balance runtime. Construction, collection, editing and saving are strictly local.
 * Start 30s polling explicitly with an owner scope via [start]; construction does not poll.
 * Manual and periodic requests share the same admission/cancellation gate.
 */
class BalanceRuntime(val store: ProviderBalanceStore, private val query: BalanceQuery = BalanceHttpQuery()) {
    val state = store.state
    private val mutation = Mutex()
    private var active: Job? = null
    private val poller = ProviderBalancePoller(
        refresh = { scope -> requestRefresh(scope) },
        configurationsFlow = { state.map { snapshot -> snapshot.revision.takeIf { snapshot.configured && snapshot.queryEnabled } }.distinctUntilChanged() },
        intervalMs = 30_000L,
    )

    /** Retained owner-scoped policy; safe to restart after owner cancellation, idempotent while active. */
    fun start(scope: CoroutineScope) = poller.start(scope)
    fun stop() = poller.stop()
    val isPolling: Boolean get() = poller.isRunning
    fun requestRefresh(scope: CoroutineScope): Job = scope.launch { manualRefresh() }

    suspend fun save(configuration: BalanceConfiguration) = mutation.withLock {
        active?.cancel(); active = null
        withContext(Dispatchers.IO) { store.save(configuration) }
    }

    suspend fun reload() = mutation.withLock {
        active?.cancel(); active = null
        withContext(Dispatchers.IO) { store.reload() }
    }

    suspend fun clear() = mutation.withLock {
        active?.cancel(); active = null
        withContext(Dispatchers.IO) { store.clear() }
    }

    suspend fun manualRefresh(): Boolean = coroutineScope {
        currentCoroutineContext().ensureActive()
        val operation = currentCoroutineContext()[Job]!!
        val ticket = mutation.withLock {
            store.beginManualRefresh(operation)?.also { active = operation }
        } ?: return@coroutineScope false
        try {
            if (!store.isCurrent(ticket)) return@coroutineScope false
            val result = query.query(ticket.configuration)
            currentCoroutineContext().ensureActive()
            mutation.withLock { withContext(Dispatchers.IO) { store.complete(ticket, result) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutation.withLock { withContext(Dispatchers.IO) {
                store.complete(ticket, BalanceResult.Failed(BalanceFailure.NETWORK))
            } }
        } finally {
            withContext(NonCancellable) {
                mutation.withLock {
                    store.cancel(ticket)
                    if (active === operation) active = null
                }
            }
        }
    }
}

/** Adapted from frozen ProviderBalancePoller: collectLatest configurations and a restartable owner. */
internal class ProviderBalancePoller(
    private val refresh: suspend (CoroutineScope) -> Unit,
    private val configurationsFlow: () -> Flow<String?>,
    private val intervalMs: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    @Synchronized fun start(scope: CoroutineScope) {
        if (!scope.isActive || job?.isActive == true) return
        job?.cancel()
        job = scope.launch(dispatcher) {
            configurationsFlow().collectLatest { revision ->
                if (revision != null) coroutineScope {
                    while (isActive) {
                        refresh(this)
                        delay(intervalMs)
                    }
                }
            }
        }
    }

    @Synchronized fun stop() { job?.cancel(); job = null }
}
