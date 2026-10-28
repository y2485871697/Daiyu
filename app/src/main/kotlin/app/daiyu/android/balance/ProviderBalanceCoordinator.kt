package app.daiyu.android.balance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One app-wide schedule, as in the original: provider IDs, not accounts, own requests. */
internal class ProviderBalanceCoordinator(
    private val providersFlow: () -> Flow<List<String>>,
    private val providers: () -> List<String>,
    private val resolve: suspend (String) -> BalanceRuntime,
    private val intervalMs: Long = 30_000L,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private data class Request(val revision: String?, val job: Job)
    private val lock = Any()
    private val requests = mutableMapOf<String, Request>()
    private var poller: Job? = null
    private var owner: CoroutineScope? = null
    private var lastResumeRefreshAt = 0L
    val isRunning: Boolean get() = synchronized(lock) { poller?.isActive == true }

    fun start(scope: CoroutineScope) = synchronized(lock) {
        if (!scope.isActive || poller?.isActive == true) return@synchronized
        owner = scope
        poller = scope.launch(dispatcher) {
            val pollScope = this
            providersFlow().collectLatest { ids ->
                val runtimes = resolveAll(ids)
                val configurations = if (runtimes.isEmpty()) flowOf(runtimes) else combine(
                    runtimes.map { (_, runtime) -> runtime.state.map { it.revision }.distinctUntilChanged() }
                ) { runtimes }
                configurations.collectLatest {
                    while (isActive) {
                        refresh(pollScope, runtimes)
                        delay(intervalMs)
                    }
                }
            }
        }
    }

    fun requestRefresh(scope: CoroutineScope): Job = scope.launch(dispatcher) {
        refresh(scope, resolveAll(providers()))
    }

    fun requestRefresh(): Job? = synchronized(lock) { owner?.takeIf { it.isActive } }?.let(::requestRefresh)

    fun onResume(scope: CoroutineScope, nowMillis: Long) {
        val refresh = synchronized(lock) {
            if (nowMillis - lastResumeRefreshAt >= 15_000L) {
                lastResumeRefreshAt = nowMillis
                true
            } else false
        }
        if (refresh) { start(scope); requestRefresh(scope) }
    }

    private suspend fun resolveAll(ids: List<String>): List<Pair<String, BalanceRuntime>> =
        ids.distinct().mapNotNull { id ->
            try { id to resolve(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }

    private fun refresh(scope: CoroutineScope, runtimes: List<Pair<String, BalanceRuntime>>) {
        if (!scope.isActive) return
        val jobs = synchronized(lock) {
            val eligible = runtimes.filter { (_, runtime) -> runtime.state.value.let { it.configured && it.queryEnabled } }
            val ids = eligible.map { it.first }.toSet()
            (requests.keys - ids).forEach { requests.remove(it)?.job?.cancel() }
            eligible.mapNotNull { (id, runtime) ->
                val revision = runtime.state.value.revision
                val current = requests[id]
                if (current != null && current.revision == revision && !current.job.isCompleted && !current.job.isCancelled) return@mapNotNull null
                current?.job?.cancel()
                val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) { runtime.manualRefresh() }
                requests[id] = Request(revision, job)
                job.invokeOnCompletion {
                    synchronized(lock) { if (requests[id]?.job === job) requests.remove(id) }
                }
                job
            }
        }
        jobs.forEach { it.start() }
    }

    override fun close() = synchronized(lock) {
        poller?.cancel(); poller = null
        requests.values.toList().forEach { it.job.cancel() }; requests.clear()
        owner = null
    }
}
