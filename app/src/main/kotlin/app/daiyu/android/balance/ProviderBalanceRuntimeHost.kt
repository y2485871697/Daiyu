package app.daiyu.android.balance

import android.content.Context
import app.daiyu.android.storage.ProviderRegistry
import java.util.concurrent.ConcurrentHashMap

/** Stable provider slots own independent encrypted records; names and chat keys are not identities. */
internal class ProviderBalanceRuntimeHost(
    private val draftQuery: BalanceQuery = BalanceHttpQuery(),
    private val factory: suspend (String) -> BalanceRuntime,
) : AutoCloseable {
    constructor(context: Context) : this(factory = { slot ->
        BalanceRuntime(ProviderBalanceStore(EncryptedBalancePersistence(context, "provider-balance-$slot")))
    })
    private val hosts = ConcurrentHashMap<String, BalanceRuntimeHost>()
    private val ready = ConcurrentHashMap<String, BalanceRuntime>()

    suspend fun get(slotId: String): BalanceRuntime {
        require(ProviderRegistry.validSlotId(slotId))
        val host = hosts.computeIfAbsent(slotId) { slot -> BalanceRuntimeHost(factory = { factory(slot) }) }
        return host.get().also { ready[slotId] = it }
    }
    suspend fun queryDraft(configuration: BalanceConfiguration): BalanceResult {
        configuration.validate()
        check(configuration.queryEnabled)
        return draftQuery.query(configuration)
    }
    fun cached(slotId: String): BalanceRuntime? = ready[slotId]
    suspend fun remove(slotId: String) {
        get(slotId).clear()
        ready.remove(slotId)?.stop()
        hosts.remove(slotId)?.close()
    }
    override fun close() {
        ready.values.forEach { it.stop() }
        hosts.values.forEach { it.close() }
        ready.clear(); hosts.clear()
    }
}
