package app.daiyu.android.balance

import android.content.Context
import app.daiyu.android.storage.ProviderRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    private val synchronizationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val synchronizationJobs = ConcurrentHashMap<String, Job>()
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
            if (ready.putIfAbsent(slotId, runtime) == null) {
                synchronizationJobs[slotId] = synchronizationScope.launch {
                    runtime.state.map { it.revision to (it.result as? BalanceResult.Available) }
                        .distinctUntilChanged().collect { (revision, available) ->
                            if (available == null) return@collect
                            val snapshot = runtime.state.value
                            if (snapshot.revision != revision || snapshot.result != available) return@collect
                            val (configuration, result) = runtime.store.successForSharing(snapshot) ?: return@collect
                            val recipients = ready.values.filter { it !== runtime }.map { it to it.state.value.revision }
                            recipients.forEach { (target, revision) ->
                                target.acceptSharedSuccess(revision, configuration, result)
                            }
                        }
                }
            }
            runtime
        }
    }
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
            hosts.remove(slotId) to synchronizationJobs.remove(slotId)
        }
        try {
            removed.second?.cancelAndJoin()
            runtime.stop()
            runtime.clear()
        } finally {
            removed.first?.close()
            synchronized(lifecycleLock) { removing.remove(slotId) }
        }
    }
    override fun close() = synchronized(lifecycleLock) {
        closed = true
        synchronizationScope.cancel()
        synchronizationJobs.clear()
        ready.values.forEach { it.stop() }
        hosts.values.forEach { it.close() }
        ready.clear(); hosts.clear()
    }
}
