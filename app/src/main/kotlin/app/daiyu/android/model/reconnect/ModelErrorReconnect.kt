package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.ModelFailure
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Port of the retained f001fb82 ModelErrorReconnect budget/segment algorithm (blob
 * 7a2460989b2081f51d750c96b95a74578857ab3d). Only host types/diagnostics are adapted.
 * This is NOT AgentModelRetry: it never opens, posts, retries or executes local tools. */
internal interface ReconnectTiming {
    fun nowMs(): Long
    fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable
}

internal object SystemReconnectTiming : ReconnectTiming {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "daiyu-model-reconnect").apply { isDaemon = true }
    }
    override fun nowMs(): Long = System.nanoTime() / 1_000_000
    override fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable {
        val task = scheduler.schedule(action, delayMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return AutoCloseable { task.cancel(false) }
    }
}

/** Host binding to the ORIGINAL run control, not a second control or attempt gate.
 * observePause must synchronously publish the current pause state on registration;
 * registerStop must also handle an already-stopped owner and run before resource cleanup. */
internal interface ReconnectControl {
    fun registerStop(wakeBeforeCleanup: Boolean, action: () -> Unit): AutoCloseable
    fun observePause(observer: (Boolean) -> Unit): AutoCloseable
}

/** Cancels ONLY the original attempt's transport AND expires its existing delivery gate.
 * Never bind this to run.stop(), tool cancellation, open(), or an unscoped next-request cancel. */
internal fun interface ReconnectTransportScope { fun cancelTransport() }

/** Safe, fixed diagnostic. No raw provider exception body or credentials cross this boundary. */
internal data class ReconnectChanged(val round: Int, val reconnectId: String, val status: String,
    val elapsedMs: Long, val reason: ModelFailure)

internal class ReconnectDeadlineException(val lastReason: ModelFailure, lateSuccess: Boolean = false) :
    app.daiyu.android.domain.TransportReconnectDeadlineException(lateSuccess)

/** One disconnect, including waits AND in-flight transport reads. Construct at FIRST failure,
 * not before the original request, and do not replace it for every retry. Closing its binding
 * never cancels the user run or local tool owners. emit must be a short identity-scoped enqueue. */
internal class ModelErrorReconnect(
    private val round: Int,
    private val windowMs: Long?,
    private val timing: ReconnectTiming,
    private val emit: (ReconnectChanged) -> Unit,
    reason: ModelFailure,
) {
    private val lock = Any()
    private val startedMs = timing.nowMs()
    private var id = UUID.randomUUID().toString()
    private var pausedAtMs: Long? = null
    private var pausedDurationMs = 0L
    private var started = false
    private var wakeupGeneration = 0L
    private var elapsedMs = 0L
    private var terminal = false
    private var wakeup: AutoCloseable? = null
    private var transport: ReconnectTransportScope? = null
    private var lastReason = reason
    @Volatile var expired = false
        private set
    @Volatile private var observerFailure: Throwable? = null

    init { require(windowMs == null || windowMs >= 0) }

    fun start() = synchronized(lock) {
        if (terminal) return@synchronized
        started = true
        if (pausedAtMs != null) return@synchronized
        if (windowMs == 0L) finishLocked("failed")
        else { sendLocked("running"); scheduleLocked() }
    }

    fun bind(controller: ReconnectControl): AutoCloseable {
        val stopBinding = controller.registerStop(wakeBeforeCleanup = true) { finish("stopped") }
        val pauseBinding = try {
            controller.observePause { paused ->
                try { setPaused(paused) }
                catch (failure: Throwable) {
                    observerFailure = failure
                    synchronized(lock) { transport }?.cancelTransport()
                }
            }
        } catch (failure: Throwable) { stopBinding.close(); throw failure }
        return AutoCloseable { try { pauseBinding.close() } finally { stopBinding.close() } }
    }

    /** Pause closes the visible segment and freezes its budget, not task/tool owners. */
    fun setPaused(paused: Boolean) {
        val interruptedScope = synchronized(lock) {
            if (terminal) return
            if (paused) {
                if (pausedAtMs != null) return
                elapsedLocked()
                pausedAtMs = timing.nowMs()
                wakeupGeneration++
                wakeup?.close()
                wakeup = null
                sendLocked("stopped")
                transport
            } else {
                val pauseStart = pausedAtMs ?: return
                pausedDurationMs += (timing.nowMs() - pauseStart).coerceAtLeast(0)
                pausedAtMs = null
                // Stopped IDs are terminal in projection. Resume uses a new visible segment.
                id = UUID.randomUUID().toString()
                if (started) { sendLocked("running"); scheduleLocked() }
                null
            }
        }
        // Expire the ORIGINAL attempt gate as well as its transport. Fast resume cannot admit
        // callbacks from the paused attempt. No task/tool owner is stopped by this operation.
        interruptedScope?.cancelTransport()
    }

    fun updateReason(reason: ModelFailure) = synchronized(lock) { lastReason = reason }

    fun attach(scope: ReconnectTransportScope) {
        val cancel = synchronized(lock) {
            transport = scope
            terminal || expired || pausedAtMs != null || observerFailure != null
        }
        // A late scope must also be cancelled after stop; do not call external cleanup under lock.
        if (cancel) scope.cancelTransport()
    }
    fun detach(scope: ReconnectTransportScope) = synchronized(lock) {
        if (transport === scope) transport = null
    }

    /** Check on the request thread, including AFTER return: late success cannot beat deadline. */
    fun check() {
        observerFailure?.let { throw it }
        val cancel = synchronized(lock) {
            if (!terminal && windowMs != null && elapsedLocked() >= windowMs) expired = true
            if (expired) transport else null
        }
        cancel?.cancelTransport()
        if (expired) throw ReconnectDeadlineException(lastReason)
    }

    fun remainingMs(): Long? = synchronized(lock) {
        windowMs?.let { (it - elapsedLocked()).coerceAtLeast(0) }
    }

    fun finish(status: String) = synchronized(lock) {
        if (terminal) return@synchronized
        val finalStatus = if (pausedAtMs != null) "stopped" else status
        if (finalStatus == "succeeded" && (expired || (windowMs != null && elapsedLocked() >= windowMs))) {
            expired = true
            throw ReconnectDeadlineException(lastReason, lateSuccess = true)
        }
        finishLocked(finalStatus)
    }

    private fun elapsedLocked(): Long {
        elapsedMs = maxOf(elapsedMs, ((pausedAtMs ?: timing.nowMs()) - startedMs - pausedDurationMs).coerceAtLeast(0))
        return elapsedMs
    }
    private fun sendLocked(status: String) {
        emit(ReconnectChanged(round, id, status, elapsedLocked(), lastReason))
    }
    private fun finishLocked(status: String) {
        terminal = true
        wakeupGeneration++
        wakeup?.close()
        wakeup = null
        sendLocked(status)
    }
    private fun scheduleLocked() {
        if (terminal || pausedAtMs != null) return
        val generation = ++wakeupGeneration
        val delay = windowMs?.let { minOf(1_000L, (it - elapsedLocked()).coerceAtLeast(1)) } ?: 1_000L
        wakeup = timing.schedule(delay) { tick(generation) }
    }
    private fun tick(generation: Long) {
        var cancel: ReconnectTransportScope? = null
        synchronized(lock) {
            if (terminal || pausedAtMs != null || generation != wakeupGeneration) return
            try {
                if (windowMs != null && elapsedLocked() >= windowMs) {
                    expired = true
                    cancel = transport
                    // Request thread emits terminal after transport has actually unwound.
                } else { sendLocked("running"); scheduleLocked() }
            } catch (failure: Throwable) {
                observerFailure = failure
                cancel = transport
            }
        }
        cancel?.cancelTransport()
    }
}
