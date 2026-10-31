package app.daiyu.android.execution

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A released service owner cannot stop a replacement task or replay a finished one. */
internal class UserExecutionLeases {
    class Owner internal constructor()
    class Lease internal constructor(private val registry: UserExecutionLeases, internal val stop: () -> Unit) : AutoCloseable {
        private val readiness = CountDownLatch(1)
        @Volatile private var started = false
        internal var owner: Owner? = null
        internal var stopRequested = false
        internal fun ready(value: Boolean) { started = value; readiness.countDown() }
        fun awaitStarted(timeoutMillis: Long): Boolean = readiness.await(timeoutMillis, TimeUnit.MILLISECONDS) && started
        override fun close() = registry.release(this)
    }
    private val leases = linkedSetOf<Lease>()
    private var owner: Owner? = null
    private var started = false

    @Synchronized fun acquire(stop: () -> Unit): Lease = Lease(this, stop).also {
        it.owner = owner
        leases += it
        if (started) it.ready(true)
    }
    @Synchronized fun attach(): Owner = Owner().also { next ->
        check(owner == null)
        owner = next
        started = false
        leases.filter { it.owner == null }.forEach { it.owner = next }
    }
    @Synchronized fun started(expected: Owner) {
        if (owner !== expected) return
        started = true
        leases.filter { it.owner === expected }.forEach { it.ready(true) }
    }
    @Synchronized private fun release(lease: Lease) {
        if (leases.remove(lease)) lease.ready(false)
    }
    @Synchronized fun count(): Int = leases.size
    @Synchronized fun retireIfIdle(expected: Owner): Boolean {
        if (owner !== expected || leases.isNotEmpty()) return false
        owner = null
        started = false
        return true
    }
    @Synchronized fun isCurrent(expected: Owner): Boolean = owner === expected
    @Synchronized fun requestStop(expected: Owner): List<() -> Unit> = leases
        .filter { it.owner === expected && !it.stopRequested }
        .onEach { it.stopRequested = true }
        .map { it.stop }

    @Synchronized fun revoke(expected: Owner): List<() -> Unit> {
        if (owner === expected) { owner = null; started = false }
        val removed = leases.filter { it.owner === expected }
        leases.removeAll(removed.toSet())
        removed.forEach { it.ready(false) }
        return removed.filterNot { it.stopRequested }.map { it.stop }
    }
}
