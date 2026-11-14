package app.daiyu.android.model.generation

import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/**
 * 每个父运行使用一个不可重置的取消句柄，取消后取得的连接和流也会清理。
 * 在状态锁外先中止整批连接，再关闭流，避免阻塞的 close 拖住其他连接。
 * 主动断开 IO 后仍须等待 HTTP 执行退出；标志检查和线程中断仅作附加检查。
 * getter 可能在取消检查后才开始，晚注册需补做断开和关闭；保留超时并观测执行退出。
 * getter 必须在状态锁外调用，否则会阻塞取消操作。
 */
class GenerationCancellation {
    data class Snapshot(
        val cancellationRequested: Boolean,
        val activeExecutions: Int,
        val pendingCleanup: Int,
        val cleanupFailures: Int,
    ) {
        /** HTTP 执行及清理调用均已返回；继续检查清理结果、父线程和资产发布状态。 */
        val isDrained: Boolean get() = activeExecutions == 0 && pendingCleanup == 0
    }

    private val lock = Object()
    private var requested = false
    private var pendingCleanup = 0
    private var cleanupFailures = 0
    private val executions = mutableSetOf<Execution>()

    val isCancellationRequested: Boolean get() = synchronized(lock) { requested }

    fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

    /** 尝试中止后返回当前状态，排空状态按实际执行退出更新。 */
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

        class TransportAbort(private val abort: () -> Unit) : CleanupAction() {
            override fun run() = abort()
        }

        class StreamClose(private val stream: Closeable) : CleanupAction() {
            override fun run() = stream.close()
        }
    }

    private fun cleanup(resources: List<CleanupAction>): Exception? {
        // cancel() claims resources across ALL executions under lock. Order that entire batch,
        // not each execution separately: A's blocking close must not delay B's disconnect.
        // No cleanup runs under the state lock; pendingCleanup already counts both phases.
        val ordered = resources.filter { it !is CleanupAction.StreamClose } +
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
        // Retain abort identities after cancel claims resources, so late streams retry abort first.
        private val abortResources = linkedMapOf<Any, CleanupAction>()

        fun register(connection: HttpURLConnection) = register(connection, CleanupAction.ConnectionAbort(connection))
        fun register(stream: Closeable) = register(stream, CleanupAction.StreamClose(stream))
        /** Transport-neutral sticky abort; register BEFORE any connect/DNS/execute operation. */
        fun registerAbort(abort: () -> Unit) = register(Any(), CleanupAction.TransportAbort(abort))

        private fun register(resource: Any, action: CleanupAction) {
            val abort = synchronized(lock) {
                if (action !is CleanupAction.StreamClose) abortResources[resource] = action
                if (requested) {
                    // Retry all transport aborts before closing a late stream. This cannot
                    // guarantee that a noninterruptible platform getter/DNS has returned.
                    val actions = mutableListOf<CleanupAction>()
                    if (action is CleanupAction.StreamClose) actions.addAll(abortResources.values)
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
