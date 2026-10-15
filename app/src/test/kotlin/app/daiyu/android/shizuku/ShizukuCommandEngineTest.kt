package app.daiyu.android.shizuku

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ShizukuCommandEngineTest {
    private open class Child(output: ByteArray = "fixture".toByteArray(), private val exit: Int = 0, done: Boolean = true) : Process() {
        private val end = CountDownLatch(if (done) 0 else 1)
        val destroyed = CountDownLatch(1)
        private val input = ByteArrayInputStream(output)
        val sent = ByteArrayOutputStream()
        override fun getInputStream(): java.io.InputStream = input
        override fun getOutputStream() = sent
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { check(end.await(5, TimeUnit.SECONDS)); return exit }
        override fun waitFor(timeout: Long, unit: TimeUnit) = end.await(timeout, unit)
        override fun exitValue(): Int { if (isAlive) throw IllegalThreadStateException(); return exit }
        override fun isAlive() = end.count != 0L
        override fun destroy() { end.countDown(); destroyed.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
    private fun waitPage(engine: ShizukuCommandEngine, id: String, predicate: (ShizukuCommandPage) -> Boolean): ShizukuCommandPage {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val page = engine.page(id, 0, 65536)
            if (predicate(page)) return page
            check(System.nanoTime() < deadline) { "job did not settle" }; Thread.sleep(5)
        }
    }
    @Test fun completedAndNonzeroExitAreActualExecution() {
        ShizukuCommandEngine { Child(exit = 7) }.use { engine ->
            assertEquals(ShizukuCommandEngine.Admission.Accepted, engine.submit("one", "exit 7".toByteArray(), 5000))
            val page = waitPage(engine, "one") { it.outcome is ShizukuOutcome.Completed }
            assertEquals(ShizukuOutcome.Completed(7), page.outcome); assertEquals("fixture", String(page.bytes)); assertTrue(page.outputComplete)
        }
    }
    @Test fun sameIdDoesNotLaunchTwiceAndDifferentPayloadConflicts() {
        val starts = AtomicInteger()
        ShizukuCommandEngine { starts.incrementAndGet(); Child() }.use { engine ->
            val script = "printf fixture".toByteArray()
            engine.submit("one", script, 5000); waitPage(engine, "one") { it.outcome is ShizukuOutcome.Completed }
            assertEquals(ShizukuCommandEngine.Admission.Accepted, engine.submit("one", script, 5000))
            assertEquals(ShizukuCommandEngine.Admission.Rejected("request_id_conflict"), engine.submit("one", "different".toByteArray(), 5000))
            assertEquals(1, starts.get())
        }
    }
    @Test fun outputQuotaStillDrainsAndPagesExactly() {
        ShizukuCommandEngine { Child(ByteArray(300_000) { (it % 127).toByte() }) }.use { engine ->
            engine.submit("large", "x".toByteArray(), 5000)
            val page = waitPage(engine, "large") { it.outcome is ShizukuOutcome.Completed }
            assertEquals(262144, page.retainedBytes); assertTrue(page.truncated); assertTrue(page.outputComplete)
            val last = engine.page("large", 262140, 65536); assertEquals(4, last.bytes.size); assertEquals(262144, last.nextOffset)
        }
    }
    @Test fun timeoutIsUnknownAndNeverReruns() {
        val child = Child(done = false); val starts = AtomicInteger()
        ShizukuCommandEngine { starts.incrementAndGet(); child }.use { engine ->
            val script = "wait".toByteArray(); engine.submit("timed", script, 1000)
            assertTrue(child.destroyed.await(5, TimeUnit.SECONDS))
            assertTrue(waitPage(engine, "timed") { it.outcome is ShizukuOutcome.Unknown }.outcome is ShizukuOutcome.Unknown)
            engine.submit("timed", script, 1000); assertEquals(1, starts.get())
        }
    }
    @Test fun cancelBeforeLateProcessArrivalStillDestroysOwnedProcess() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val child = Child(done = false)
        ShizukuCommandEngine { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); child }.use { engine ->
            engine.submit("late", "wait".toByteArray(), 5000); assertTrue(entered.await(5, TimeUnit.SECONDS))
            try { assertTrue(engine.cancel("late")) } finally { release.countDown() }
            assertTrue(child.destroyed.await(5, TimeUnit.SECONDS)); assertEquals(0, child.sent.size()); assertTrue(engine.page("late", 0, 10).outcome is ShizukuOutcome.Unknown)
        }
    }
    @Test fun closeFencesLateLauncherAndNewAdmission() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val child = Child(done = false)
        val engine = ShizukuCommandEngine { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); child }
        try {
            engine.submit("late", "wait".toByteArray(), 5000); assertTrue(entered.await(5, TimeUnit.SECONDS))
            engine.close(); release.countDown(); assertTrue(child.destroyed.await(5, TimeUnit.SECONDS)); assertEquals(0, child.sent.size())
            assertEquals(ShizukuCommandEngine.Admission.Rejected("backend_closed"), engine.submit("new", "x".toByteArray(), 5000))
        } finally { release.countDown(); engine.close() }
    }
    @Test fun launcherFailureStaysUnknownInsteadOfRetryableNotExecuted() {
        val starts = AtomicInteger()
        ShizukuCommandEngine { starts.incrementAndGet(); error("launcher failed") }.use { engine ->
            engine.submit("bad", "x".toByteArray(), 5000); waitPage(engine, "bad") { it.outcome is ShizukuOutcome.Unknown }
            engine.submit("bad", "x".toByteArray(), 5000); assertEquals(1, starts.get())
        }
    }
    @Test fun liveQuotaAndUnknownJobDoNotCreateExtraLaunches() {
        val started = CountDownLatch(4)
        ShizukuCommandEngine { started.countDown(); Child(done = false) }.use { engine ->
            repeat(4) { assertEquals(ShizukuCommandEngine.Admission.Accepted, engine.submit("live$it", "$it".toByteArray(), 5000)) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(ShizukuCommandEngine.Admission.Rejected("live_job_limit"), engine.submit("fifth", "x".toByteArray(), 5000))
            assertFalse(engine.cancel("not_present"))
        }
    }
    @Test fun retainedIdsNeverEvictedToMakeRoomForRerun() {
        ShizukuCommandEngine { Child() }.use { engine ->
            repeat(64) { id ->
                val request = "job$id"; engine.submit(request, "$id".toByteArray(), 5000)
                waitPage(engine, request) { it.outcome is ShizukuOutcome.Completed }
            }
            assertEquals(ShizukuCommandEngine.Admission.Rejected("job_retention_limit"), engine.submit("overflow", "x".toByteArray(), 5000))
            assertEquals(ShizukuCommandEngine.Admission.Accepted, engine.submit("job0", "0".toByteArray(), 5000))
        }
    }
    @Test fun invalidCommandsRejectedBeforeAnyLauncher() {
        val starts = AtomicInteger()
        ShizukuCommandEngine { starts.incrementAndGet(); Child() }.use { engine ->
            listOf<() -> Unit>(
                { engine.submit("bad/id", "x".toByteArray(), 10) },
                { engine.submit("zero", byteArrayOf(0), 10) },
                { engine.submit("empty", byteArrayOf(), 10) },
                { engine.submit("huge", ByteArray(131073) { 1 }, 10) },
                { engine.submit("timeout", "x".toByteArray(), 120001) }
            ).forEach { action -> try { action(); fail("invalid command accepted") } catch (_: IllegalArgumentException) {} }
            assertEquals(0, starts.get())
        }
    }

    @Test fun delayedIoCompletionReleasesActualLiveQuota() {
        val release = CountDownLatch(1); val starts = AtomicInteger()
        ShizukuCommandEngine {
            if (starts.incrementAndGet() <= 4) object : Child() {
                override fun getInputStream() = object : java.io.InputStream() {
                    override fun read(): Int { check(release.await(5, TimeUnit.SECONDS)); return -1 }
                }
            } else Child(done = false)
        }.use { engine ->
            try {
                repeat(4) { engine.submit("slow$it", "$it".toByteArray(), 5000) }
                repeat(4) { waitPage(engine, "slow$it") { it.outcome is ShizukuOutcome.Unknown } }
            } finally { release.countDown() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            repeat(4) { index ->
                while (engine.submit("new$index", "new$index".toByteArray(), 5000) != ShizukuCommandEngine.Admission.Accepted) {
                    check(System.nanoTime() < deadline); Thread.sleep(5)
                }
            }
        }
    }

}
