package app.daiyu.android.domain.delegation

import java.util.concurrent.Semaphore

/** One capacity authority; leases remain attached while their original transport is parked. */
class ChildModelPool(limit: Int, private val maximum: Int = Int.MAX_VALUE) {
    private class Permits(count: Int) : Semaphore(count, true) {
        fun shrink(count: Int) = reducePermits(count)
    }
    private val gate = Any()
    private var capacity = normalize(limit)
    private val permits = Permits(capacity)
    internal val semaphore: Semaphore get() = permits
    private val leases = linkedSetOf<ChildPoolLease>()
    private fun normalize(limit: Int): Int {
        require(limit >= 0)
        return if (limit == 0) maximum else minOf(limit, maximum)
    }
    fun resize(limit: Int) {
        val observers = synchronized(gate) {
            val next = normalize(limit)
            val delta = next - capacity
            if (delta < 0) permits.shrink(-delta) else if (delta > 0) permits.release(delta)
            capacity = next
            rebalance()
            leases.toList()
        }
        observers.forEach { it.refresh() }
    }
    fun lease(onWaiting: (Boolean) -> Unit = {}): ChildPoolLease = synchronized(gate) {
        ChildPoolLease(this, onWaiting).also { leases.add(it) }
    }
    private fun rebalance() {
        // Parked leases retain their place; new work cannot jump ahead after a shrink.
        val owners = leases.filter { it.entered && !it.closed }
        owners.forEachIndexed { index, lease ->
            lease.excess = index >= capacity
            if (lease.excess && lease.boundary && lease.held) releasePermit(lease)
        }
    }
    private fun releasePermit(lease: ChildPoolLease) {
        if (lease.held) { lease.held = false; permits.release() }
    }
    internal fun boundary(lease: ChildPoolLease) = synchronized(gate) {
        if (!lease.closed) { lease.boundary = true; rebalance() }
    }
    internal fun acquire(lease: ChildPoolLease): Boolean = synchronized(gate) {
        if (lease.closed) return@synchronized false
        lease.boundary = true
        if (!lease.entered) {
            // Preserve FIFO among already parked continuations, without reserving idle slots.
            if (leases.any { it !== lease && it.entered && !it.held && !it.closed && !it.excess }) return@synchronized false
            if (!permits.tryAcquire()) return@synchronized false
            lease.held = true; lease.entered = true
            rebalance()
        } else if (!lease.held && !lease.excess && permits.tryAcquire()) lease.held = true
        lease.held && !lease.excess
    }
    internal fun depart(lease: ChildPoolLease): Boolean = synchronized(gate) {
        if (lease.closed || !lease.held || lease.excess) false else { lease.boundary = false; true }
    }
    internal fun waiting(lease: ChildPoolLease): Boolean = synchronized(gate) { !lease.closed && lease.excess }
    internal fun close(lease: ChildPoolLease) = synchronized(gate) {
        if (!lease.closed) {
            lease.closed = true; releasePermit(lease); leases.remove(lease); rebalance()
        }
    }
}

/** No automatic cancel, reopen or replay; close is called only at the existing double-drain gate. */
class ChildPoolLease internal constructor(private val pool: ChildModelPool, private val onWaiting: (Boolean) -> Unit) : AutoCloseable {
    internal var entered = false
    internal var held = false
    internal var boundary = true
    internal var excess = false
    internal var closed = false
    private val observed = java.util.concurrent.atomic.AtomicBoolean(false)
    val yieldRequested: Boolean get() = pool.waiting(this).also { publish(it) }
    internal fun refresh() { publish(pool.waiting(this)) }
    fun enterBoundary() { pool.boundary(this); refresh() }
    fun tryAcquire(): Boolean = pool.acquire(this).also { publish(!it) }
    fun leaveBoundary(): Boolean = pool.depart(this).also { if (it) publish(false) }
    private fun publish(waiting: Boolean) {
        if (observed.getAndSet(waiting) != waiting) onWaiting(waiting)
    }
    override fun close() { pool.close(this); publish(false) }
}
