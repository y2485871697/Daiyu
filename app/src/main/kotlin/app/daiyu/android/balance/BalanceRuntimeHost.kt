package app.daiyu.android.balance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/** One application-owned initialization, never an Activity/Composable-owned store.
 * The lazy deferred survives a cancelled Activity awaiting it. Both the factory (including first
 * Keystore/disk access) and any retry-free initialization run on IO, not the main thread.
 * Construction/awaiting does not start HTTP; only an explicit foreground polling owner does.
 */
internal class BalanceRuntimeHost(
    factory: suspend () -> BalanceRuntime,
    private val processScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private val retained = processScope.async(start = CoroutineStart.LAZY) { factory() }
    suspend fun get(): BalanceRuntime = retained.await()
    override fun close() { processScope.cancel() }
}
