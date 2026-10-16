package app.daiyu.android.model.generation

import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/**
 * One handle per parent-owned run; never reset or share it with a subsequent session.
 * Cancellation is sticky, including connections/streams acquired after cancel(). Cleanup runs
 * outside the state lock, aborting every connection in a claimed batch before closing any stream.
 * A cancel request is NOT an acknowledgement that HTTP has exited.
 * Legacy cancellation predicates/interrupts remain checks only; use this handle to abort live I/O.
 *
 * Platform I/O cancellation is best-effort: a getter can start after throwIfCancelled() returns
 * and after cancel() has already disconnected its connection. Late registration retries disconnect
 * and closes a returned stream, but cannot guarantee abort of a getter still in flight in that gap.
 * Extra checks cannot remove that race, and holding the state lock across a getter would block
 * cancellation itself. Transport timeouts and observation of actual execution exit remain required.
 */
class GenerationCancellation {
    data class Snapshot(
        val cancellationRequested: Boolean,
        val activeExecutions: Int,
        val pendingCleanup: Int,
        val cleanupFailures: Int,
    ) {
        /** HTTP executions have returned through finally and all attempted cleanup has returned.
         * This does not assert parent-worker exit, successful cleanup, or asset publication safety.
         */
        val isDrained: Boolean get() = activeExecutions == 0 && pendingCleanup == 0
    }

    private val lock = Object()
    private var requested = false
    private var pendingCleanup = 0
    private var cleanupFailures = 0
    private val executions = mutableSetOf<Execution>()

    val isCancellationRequested: Boolean get() = synchronized(lock) { requested }

    fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

    /** Returns observed state after attempting aborts, not a synthetic "cancelled = drained". */
    fun cancel(): Snapshot {
        val actions = synchronized(lock) {
            requested = true
            executions.flatMap { it.takeResourcesLocked() }
        }
        cleanup(actions)
        return snapshot()
    }

    /** Bounded observation of actual HTTP exit; a timeout leaves isDrained false. */
    @Throws(InterruptedException::class)
    fun awaitDrained(timeoutMillis: Long): Snapshot {
        require(timeoutMillis >= 0)
        val timeout = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val start = System.nanoTime()
        synchronized(lock) {
            while (executions.isNotEmpty() || pendingCleanup != 0) {
                val remaining = timeout - (System.nanoTime() - start)
                if (remaining <= 0) break
                TimeUnit.NANOSECONDS.timedWait(lock, remaining)
            }
            return snapshotLocked()
        }
    }

    internal fun begin(): Execution = synchronized(lock) {
        Execution().also { executions.add(it) }
    }

    internal fun throwIfCancelled() {
        if (isCancellationRequested) throw GenerationCancelledException()
    }

    private fun snapshotLocked() = Snapshot(requested, executions.size, pendingCleanup, cleanupFailures)

    internal sealed class CleanupAction {
        abstract fun run()

        class ConnectionAbort(val connection: HttpURLConnection) : CleanupAction() {
            override fun run() = connection.disconnect()
        }

        class StreamClose(private val stream: Closeable) : CleanupAction() {
            override fun run() = stream.close()
        }
    }

    private fun cleanup(resources: List<CleanupAction>): Exception? {
        // cancel() claims resources across ALL executions under lock. Order that entire batch,
        // not each execution separately: A's blocking close must not delay B's disconnect.
        // No cleanup runs under the state lock; pendingCleanup already counts both phases.
        val ordered = resources.filterIsInstance<CleanupAction.ConnectionAbort>() +
            resources.filterIsInstance<CleanupAction.StreamClose>()
        var firstFailure: Exception? = null
        ordered.forEach { action ->
            try { action.run() } catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure
                synchronized(lock) { cleanupFailures++ }
            } finally {
                synchronized(lock) { pendingCleanup--; lock.notifyAll() }
            }
        }
        return firstFailure
    }

    internal inner class Execution internal constructor() {
        private val resources = linkedMapOf<Any, CleanupAction>()
        private var connection: HttpURLConnection? = null

        fun register(connection: HttpURLConnection) = register(connection, CleanupAction.ConnectionAbort(connection))
        fun register(stream: Closeable) = register(stream, CleanupAction.StreamClose(stream))

        private fun register(resource: Any, action: CleanupAction) {
            val abort = synchronized(lock) {
                if (action is CleanupAction.ConnectionAbort) connection = action.connection
                if (requested) {
                    // Retry disconnect when a racing getter returns, then close its late stream.
                    // This cannot guarantee that a getter still in flight has been aborted.
                    val actions = mutableListOf<CleanupAction>()
                    if (resource !== connection) connection?.let { actions.add(CleanupAction.ConnectionAbort(it)) }
                    actions.add(action)
                    pendingCleanup += actions.size
                    actions
                } else { resources[resource] = action; emptyList() }
            }
            cleanup(abort)
            throwIfCancelled()
        }

        fun release(resource: Any) {
            val action = synchronized(lock) {
                resources.remove(resource)?.also { pendingCleanup++ }
            }
            // Preserve normal stream-close failures just as use{} did; abort/finally cleanup
            // still records failures without replacing the already-known request outcome.
            if (action != null) cleanup(listOf(action))?.let { throw it }
        }

        internal fun takeResourcesLocked(): List<CleanupAction> = resources.values.toList().also {
            pendingCleanup += it.size
            resources.clear()
        }

        fun finish() {
            val remaining = synchronized(lock) { takeResourcesLocked() }
            try { cleanup(remaining) } finally {
                synchronized(lock) { executions.remove(this); lock.notifyAll() }
            }
        }
    }
}

/** Redacted signal shared by the default transport and cancellation-aware injected transports. */
class GenerationCancelledException : IOException("Generation HTTP cancelled")
