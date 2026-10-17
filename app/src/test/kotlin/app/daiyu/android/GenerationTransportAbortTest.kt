package app.daiyu.android

import app.daiyu.android.model.generation.GenerationCancellation
import app.daiyu.android.model.generation.GenerationCancelledException
import okhttp3.Dns
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/** No real sockets, DNS nameservers, credentials or paid requests. */
class GenerationTransportAbortTest {
    private fun dns(resolve: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String) = resolve(hostname)
    }
    @Test fun everyGenericAbortPrecedesEveryStreamCloseAcrossExecutions() {
        val events = mutableListOf<String>()
        val cancellation = GenerationCancellation()
        val a = cancellation.begin(); val b = cancellation.begin()
        a.register(Closeable { events.add("stream-a") })
        a.registerAbort { events.add("abort-a") }
        b.register(Closeable { events.add("stream-b") })
        b.registerAbort { events.add("abort-b") }
        val cancelled = cancellation.cancel()
        assertEquals(listOf("abort-a", "abort-b", "stream-a", "stream-b"), events)
        assertEquals(2, cancelled.activeExecutions); assertFalse(cancelled.isDrained)
        a.finish(); b.finish(); assertTrue(cancellation.snapshot().isDrained)
    }

    @Test fun lateStreamReabortsBeforeClosingAndCleanupFailureRemainsObservable() {
        val events = mutableListOf<String>()
        val cancellation = GenerationCancellation()
        val execution = cancellation.begin()
        execution.registerAbort { events.add("abort") }
        cancellation.cancel()
        val failure = runCatching { execution.register(Closeable { events.add("late-close") }) }.exceptionOrNull()
        assertTrue(failure is GenerationCancelledException)
        assertEquals(listOf("abort", "abort", "late-close"), events)
        execution.finish(); assertTrue(cancellation.snapshot().isDrained)

        val failed = GenerationCancellation()
        val active = failed.begin()
        active.registerAbort { throw IOException("redacted abort failure") }
        failed.cancel(); assertEquals(1, failed.snapshot().cleanupFailures)
        assertFalse(failed.snapshot().isDrained)
        active.finish()
        assertTrue(failed.snapshot().isDrained); assertEquals(1, failed.snapshot().cleanupFailures)
    }

    @Test fun cancellationDuringNoninterruptibleDnsKeepsActiveUntilReturnAndCreatesNoSocket() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val sockets = AtomicInteger()
        val cancellation = GenerationCancellation()
        val client = GenerationPublicNetworkHttp.clientFor("cdn.example", dns {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
        }, { cancellation.isCancellationRequested }).newBuilder().socketFactory(object : SocketFactory() {
            override fun createSocket(): Socket { sockets.incrementAndGet(); throw IOException("offline forbidden socket") }
            override fun createSocket(h: String, p: Int): Socket = error("unexpected")
            override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("unexpected")
            override fun createSocket(h: InetAddress, p: Int): Socket = error("unexpected")
            override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("unexpected")
        }).build()
        val execution = cancellation.begin()
        val call = client.newCall(Request.Builder().url("https://cdn.example/file").build())
        execution.registerAbort(call::cancel)
        val worker = Thread {
            try { call.execute().use { error("no response expected") } }
            catch (_: IOException) { /* expected after DNS returns */ }
            finally { execution.finish() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cancellation.cancel()
            assertEquals(1, cancellation.snapshot().activeExecutions)
            assertFalse(cancellation.awaitDrained(0).isDrained)
            assertEquals(0, sockets.get())
        } finally { release.countDown(); worker.join(5_000); client.connectionPool.evictAll() }
        assertFalse(worker.isAlive); assertTrue(cancellation.snapshot().isDrained)
        assertEquals(0, sockets.get())
    }
}
