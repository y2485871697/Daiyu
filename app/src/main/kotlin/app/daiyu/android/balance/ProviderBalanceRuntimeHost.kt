package app.daiyu.android.balance

import android.content.Context
import app.daiyu.android.storage.ProviderRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

/** Stable provider slots own independent encrypted records; names and chat keys are not identities. */
internal class ProviderBalanceRuntimeHost(
    private val draftQuery: BalanceQuery = BalanceHttpQuery(),
    private val factory: suspend (String) -> BalanceRuntime,
) : AutoCloseable {
    constructor(context: Context) : this(factory = { slot ->
        BalanceRuntime(ProviderBalanceStore(EncryptedBalancePersistence(context, "provider-balance-$slot")))
    })
    private val lifecycleLock = Any()
    private var closed = false
    private val removing = mutableSetOf<String>()
    private var coordinator: ProviderBalanceCoordinator? = null
    private val hosts = ConcurrentHashMap<String, BalanceRuntimeHost>()
    private val ready = ConcurrentHashMap<String, BalanceRuntime>()

    suspend fun get(slotId: String): BalanceRuntime {
        require(ProviderRegistry.validSlotId(slotId))
        val host = synchronized(lifecycleLock) {
            check(!closed && slotId !in removing) { "余额宿主已关闭或正在删除" }
            hosts.computeIfAbsent(slotId) { slot -> BalanceRuntimeHost(factory = { factory(slot) }) }
        }
        val runtime = host.get()
        return synchronized(lifecycleLock) {
            check(!closed && slotId !in removing && hosts[slotId] === host) { "余额宿主已变化" }
            ready.putIfAbsent(slotId, runtime)
            runtime
        }
    }
    fun start(scope: CoroutineScope, providersFlow: () -> Flow<List<String>>, providers: () -> List<String>) =
        synchronized(lifecycleLock) {
            check(!closed) { "余额宿主已关闭" }
            val scheduler = coordinator ?: ProviderBalanceCoordinator(providersFlow, providers, ::get)
                .also { coordinator = it }
            scheduler.start(scope)
        }
    fun requestRefresh(scope: CoroutineScope): Job? = synchronized(lifecycleLock) { coordinator }?.requestRefresh(scope)
    fun requestRefresh(): Job? = synchronized(lifecycleLock) { coordinator }?.requestRefresh()
    fun onResume(scope: CoroutineScope, nowMillis: Long) =
        synchronized(lifecycleLock) { coordinator }?.onResume(scope, nowMillis)
    val isPolling: Boolean get() = synchronized(lifecycleLock) { coordinator?.isRunning == true }

    suspend fun queryDraft(configuration: BalanceConfiguration): BalanceResult {
        configuration.validate()
        check(configuration.queryEnabled)
        return draftQuery.query(configuration)
    }
    fun cached(slotId: String): BalanceRuntime? = ready[slotId]
    suspend fun remove(slotId: String) {
        val runtime = get(slotId)
        val removed = synchronized(lifecycleLock) {
            check(!closed && ready[slotId] === runtime && removing.add(slotId)) { "余额宿主已变化" }
            ready.remove(slotId)
            hosts.remove(slotId)
        }
        try {
            runtime.stop()
            runtime.clear()
        } finally {
            removed?.close()
            synchronized(lifecycleLock) { removing.remove(slotId) }
        }
    }
    override fun close() = synchronized(lifecycleLock) {
        closed = true
        coordinator?.close(); coordinator = null
        ready.values.forEach { it.stop() }
        hosts.values.forEach { it.close() }
        ready.clear(); hosts.clear()
    }
}
