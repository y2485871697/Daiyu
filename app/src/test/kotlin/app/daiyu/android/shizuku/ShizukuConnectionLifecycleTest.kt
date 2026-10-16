package app.daiyu.android.shizuku

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ShizukuConnectionLifecycleTest {
    private class Backend(private val onClose: () -> Unit = {}) : ShizukuBackend {
        val closes = AtomicInteger()
        override val identity = ShizukuIdentity(2000, "service", "permission")
        override fun execute(command: ShizukuCommand): ShizukuDispatch = error("unused")
        override fun poll(handle: ShizukuCommandHandle, offset: Int, limit: Int): ShizukuCommandPage = error("unused")
        override fun cancel(handle: ShizukuCommandHandle): ShizukuOutcome.Unknown = error("unused")
        override fun closeWithResult(onResult: (ShizukuCloseResult) -> Unit) {
            close(); onResult(ShizukuCloseResult.Drained)
        }
        override fun close() { closes.incrementAndGet(); onClose() }
    }
    private fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) { "fixture_timeout" } }
    private fun rejected(code: String, action: () -> Unit) {
        try { action(); fail("expected $code") }
        catch (failure: IllegalStateException) { assertEquals(code, failure.message) }
    }
    private fun rejectedFuture(code: String, future: Future<*>) {
        try { future.get(5, TimeUnit.SECONDS); fail("expected $code") }
        catch (failure: ExecutionException) {
            assertTrue(failure.cause is IllegalStateException); assertEquals(code, failure.cause?.message)
        }
    }
    @Test fun closeDuringConstructionFencesWaitersAndOwnsLateCandidate() {
        val releases = AtomicInteger(); val builds = AtomicInteger()
        val lifecycle = ShizukuConnectionLifecycle { releases.incrementAndGet() }
        val candidate = Backend(); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val waiterStarted = CountDownLatch(1); val waiterThread = AtomicReference<Thread>()
        val pool = Executors.newFixedThreadPool(3)
        try {
            val creator = pool.submit<ShizukuBackend> { lifecycle.open("scope") {
                builds.incrementAndGet(); entered.countDown(); await(finish); candidate
            } }
            await(entered)
            val waiter = pool.submit<ShizukuBackend> {
                waiterThread.set(Thread.currentThread()); waiterStarted.countDown(); lifecycle.open("scope") { builds.incrementAndGet(); Backend() }
            }
            await(waiterStarted)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (waiterThread.get().state != Thread.State.WAITING && !waiter.isDone && System.nanoTime() < deadline) Thread.sleep(1)
            assertFalse(waiter.isDone); assertEquals(Thread.State.WAITING, waiterThread.get().state)
            pool.submit { lifecycle.close() }.get(5, TimeUnit.SECONDS)
            assertFalse(lifecycle.isOpen); assertEquals(1L, finish.count)
            assertEquals(1, releases.get()); assertEquals(0, candidate.closes.get())
            rejectedFuture("shizuku_scope_revoked", waiter)
            finish.countDown(); rejectedFuture("shizuku_scope_revoked", creator)
            lifecycle.close(); assertEquals(1, builds.get())
            assertEquals(1, candidate.closes.get()); assertEquals(1, releases.get())
        } finally { finish.countDown(); lifecycle.close(); pool.shutdownNow() }
    }
    @Test fun concurrentSameScopeBuildsOnlyOnceAndOtherScopeCannotBind() {
        val releases = AtomicInteger(); val builds = AtomicInteger()
        val lifecycle = ShizukuConnectionLifecycle { releases.incrementAndGet() }
        val candidate = Backend(); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<ShizukuBackend> { lifecycle.open("scope") {
                builds.incrementAndGet(); entered.countDown(); await(finish); candidate
            } }
            await(entered)
            val second = pool.submit<ShizukuBackend> { lifecycle.open("scope") { builds.incrementAndGet(); Backend() } }
            rejected("shizuku_scope_already_bound") { lifecycle.open("other") { builds.incrementAndGet(); Backend() } }
            finish.countDown()
            assertSame(candidate, first.get(5, TimeUnit.SECONDS)); assertSame(candidate, second.get(5, TimeUnit.SECONDS))
            assertEquals(1, builds.get()); lifecycle.close()
            assertEquals(1, candidate.closes.get()); assertEquals(1, releases.get())
        } finally { finish.countDown(); lifecycle.close(); pool.shutdownNow() }
    }
    @Test fun publishedCloseIsReentrantAndNeverReopens() {
        val releases = AtomicInteger()
        lateinit var lifecycle: ShizukuConnectionLifecycle
        lifecycle = ShizukuConnectionLifecycle { releases.incrementAndGet() }
        val candidate = Backend { lifecycle.close() }
        assertSame(candidate, lifecycle.open("scope") { candidate })
        assertSame(candidate, lifecycle.open("scope") { error("duplicate construction") })
        lifecycle.close(); lifecycle.close(); assertFalse(lifecycle.isOpen)
        rejected("shizuku_scope_revoked") { lifecycle.open("scope") { error("reopened") } }
        assertEquals(1, candidate.closes.get()); assertEquals(1, releases.get())
    }
    @Test fun constructionFailureCanRetryOnlyBeforeClose() {
        val releases = AtomicInteger(); val lifecycle = ShizukuConnectionLifecycle { releases.incrementAndGet() }
        rejected("build_failed") { lifecycle.open("scope") { error("build_failed") } }
        assertTrue(lifecycle.isOpen); assertEquals(0, releases.get())
        val candidate = Backend(); assertSame(candidate, lifecycle.open("scope") { candidate })
        lifecycle.close(); rejected("shizuku_scope_revoked") { lifecycle.open("scope") { error("reopened") } }
        assertEquals(1, candidate.closes.get()); assertEquals(1, releases.get())
    }
    @Test fun constructionFailureAfterCloseCannotReviveConnection() {
        val releases = AtomicInteger(); val lifecycle = ShizukuConnectionLifecycle { releases.incrementAndGet() }
        val entered = CountDownLatch(1); val fail = CountDownLatch(1); val pool = Executors.newSingleThreadExecutor()
        try {
            val creator = pool.submit<ShizukuBackend> { lifecycle.open("scope") {
                entered.countDown(); await(fail); error("build_failed")
            } }
            await(entered); lifecycle.close(); fail.countDown(); rejectedFuture("build_failed", creator)
            assertFalse(lifecycle.isOpen)
            rejected("shizuku_scope_revoked") { lifecycle.open("scope") { error("reopened") } }
            assertEquals(1, releases.get())
        } finally { fail.countDown(); lifecycle.close(); pool.shutdownNow() }
    }
    @Test fun releaseStillRunsWhenPublishedBackendCloseThrows() {
        val releases = AtomicInteger(); val lifecycle = ShizukuConnectionLifecycle { releases.incrementAndGet() }
        val candidate = Backend { error("close_failed") }; lifecycle.open("scope") { candidate }
        rejected("close_failed") { lifecycle.close() }; lifecycle.close()
        assertFalse(lifecycle.isOpen); assertEquals(1, releases.get()); assertEquals(1, candidate.closes.get())
    }
    @Test fun connectionReleaseWaitsForBackendCleanupObservation() {
        val releases = AtomicInteger()
        val lifecycle = ShizukuConnectionLifecycle { releases.incrementAndGet() }
        var complete: ((ShizukuCloseResult) -> Unit)? = null
        val backend = object : ShizukuBackend {
            override val identity = ShizukuIdentity(2000, "service", "permission")
            override fun execute(command: ShizukuCommand): ShizukuDispatch = error("unused")
            override fun poll(handle: ShizukuCommandHandle, offset: Int, limit: Int): ShizukuCommandPage = error("unused")
            override fun cancel(handle: ShizukuCommandHandle): ShizukuOutcome.Unknown = error("unused")
            override fun close() {}
            override fun closeWithResult(onResult: (ShizukuCloseResult) -> Unit) { complete = onResult }
        }
        lifecycle.open("scope") { backend }
        lifecycle.close()
        lifecycle.close()
        assertFalse(lifecycle.isOpen)
        assertEquals(0, releases.get())
        checkNotNull(complete)(ShizukuCloseResult.Unknown("revoked"))
        assertEquals(1, releases.get())
    }

}
