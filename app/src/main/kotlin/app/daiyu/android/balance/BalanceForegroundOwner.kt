package app.daiyu.android.balance

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive

/** Main-thread lifecycle port. Every foreground visit has a NEW cancellable child scope.
 * A page's manual requests belong here too: stopping only the runtime poller does not cancel them.
 * The Activity is the sole polling owner; settings owns manual queries only, never starts polling.
 */
internal class BalanceForegroundOwner(
    private val runtime: BalanceRuntime,
    private val poll: Boolean,
) : AutoCloseable {
    private var foreground: CoroutineScope? = null
    val isActive: Boolean get() = foreground?.isActive == true

    fun start(parent: CoroutineScope) {
        if (isActive || !parent.isActive) return
        val child = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        foreground = child
        if (poll) runtime.start(child)
    }

    fun requestRefresh(): Job? = foreground?.takeIf { it.isActive }?.let(runtime::requestRefresh)

    fun stop() {
        if (foreground == null) return
        if (poll) runtime.stop()
        foreground?.cancel()
        foreground = null
    }

    override fun close() = stop()
}

/** Lifecycle registration synchronizes an already STARTED owner as well as future start/stop.
 * close() also cancels the page request when navigation disposes without an Activity stop.
 */
internal class BalanceLifecycleBinding(
    private val lifecycle: Lifecycle,
    private val parent: CoroutineScope,
    runtime: BalanceRuntime,
    poll: Boolean,
) : DefaultLifecycleObserver, AutoCloseable {
    private val foreground = BalanceForegroundOwner(runtime, poll)
    init { lifecycle.addObserver(this) }
    fun requestRefresh(): Job? = foreground.requestRefresh()
    override fun onStart(owner: LifecycleOwner) = foreground.start(parent)
    override fun onStop(owner: LifecycleOwner) = foreground.stop()
    override fun onDestroy(owner: LifecycleOwner) = close()
    override fun close() {
        lifecycle.removeObserver(this)
        foreground.close()
    }
}
