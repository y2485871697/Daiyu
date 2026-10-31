package app.daiyu.android.voice.network

import app.daiyu.android.voice.duplex.DuplexRequest
import app.daiyu.android.voice.duplex.DuplexSendResult
import app.daiyu.android.voice.duplex.DuplexTransport
import app.daiyu.android.voice.providers.VoiceSocketRequest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SocketVoiceNetworkTest {
    private val asrRequest = VoiceSocketRequest("wss://example.invalid/asr?key=private-query",
        mapOf("Authorization" to "private-key"))
    private val duplexRequest = DuplexRequest("wss://example.invalid/duplex", mapOf("X-Api-Key" to "private-key"))

    @Test fun asrWaitsForHandshakeAndCancellationAbortsSocket() = runBlocking {
        val factory = FakeSockets(autoOpen = false)
        val connecting = async(start = CoroutineStart.UNDISPATCHED) { AsrNetworkTransport(factory).connect(asrRequest, 16) }
        assertFalse(connecting.isCompleted)
        assertEquals(16, factory.limit)
        connecting.cancelAndJoin()
        assertTrue(factory.peer.aborted)
        factory.events.opened() // late success must not resurrect a cancelled connection
        assertEquals(1, factory.creations)
    }

    @Test fun asrBinaryEofAndIdempotentClose() = runBlocking {
        val factory = FakeSockets()
        val socket = AsrNetworkTransport(factory).connect(asrRequest, 16)
        socket.send(byteArrayOf(1, 2))
        assertEquals(1, factory.peer.sends)
        factory.events.binary(byteArrayOf(3, 4))
        factory.events.closed()
        assertArrayEquals(byteArrayOf(3, 4), socket.receive())
        assertNull(socket.receive())
        socket.close(); socket.close()
        assertTrue(factory.peer.aborted)
    }

    @Test fun receiveCancellationAbortsActualSocket() = runBlocking {
        val factory = FakeSockets()
        val socket = AsrNetworkTransport(factory).connect(asrRequest, 16)
        val receiver = launch(start = CoroutineStart.UNDISPATCHED) { socket.receive() }
        receiver.cancelAndJoin()
        assertTrue(factory.peer.aborted)
    }

    @Test fun textOversizeAndOverflowFailClosedWithoutPayloadLeak() = runBlocking {
        for (mode in 0..2) {
            val factory = FakeSockets()
            val socket = AsrNetworkTransport(factory).connect(asrRequest, 16)
            when (mode) {
                0 -> factory.events.text("private-server-error")
                1 -> factory.events.binary(ByteArray(17))
                else -> repeat(NetworkLimits.QUEUED_MESSAGES + 1) { factory.events.binary(byteArrayOf(1)) }
            }
            assertTrue(factory.peer.aborted)
            val error = runCatching { socket.receive() }.exceptionOrNull()!!
            assertEquals("语音网络传输失败", error.message)
            assertNull(error.cause)
            assertFalse(error.toString().contains("private"))
        }
    }

    @Test fun cancellationRacingAnAcceptedAsrSendStillAbortsSocket() = runBlocking {
        val factory = FakeSockets()
        val socket = AsrNetworkTransport(factory).connect(asrRequest, 16)
        val sender = launch(start = CoroutineStart.UNDISPATCHED) {
            val ownJob = currentCoroutineContext().job
            factory.peer.onSend = { ownJob.cancel() }
            socket.send(byteArrayOf(1))
        }
        sender.join()
        assertTrue(sender.isCancelled)
        assertTrue(factory.peer.aborted)
        assertEquals(1, factory.peer.sends)
    }

    @Test fun uncertainAsrSendIsNeverReplayedAndCloses() = runBlocking {
        val factory = FakeSockets()
        val socket = AsrNetworkTransport(factory).connect(asrRequest, 16)
        factory.peer.result = DuplexSendResult.UNKNOWN
        assertTrue(runCatching { socket.send(byteArrayOf(1)) }.isFailure)
        assertEquals(1, factory.peer.sends)
        assertTrue(factory.peer.aborted)
    }

    @Test fun duplexCallbacksAreAsyncCompleteMessagesAndLateCallbacksAreDetached() {
        val factory = FakeSockets()
        val transport = DuplexNetworkTransport(factory)
        val callingThread = Thread.currentThread()
        val received = CountDownLatch(2)
        val opened = CountDownLatch(1)
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val values = Collections.synchronizedList(mutableListOf<String>())
        transport.connect(duplexRequest, object : Listener() {
            override fun opened() { threads += Thread.currentThread(); opened.countDown() }
            override fun message(json: String) {
                threads += Thread.currentThread(); values += json; received.countDown()
            }
        })
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        factory.events.text("{\"text\":\"one\"}")
        factory.events.binary("{\"text\":\"二\"}".toByteArray())
        assertTrue(received.await(2, TimeUnit.SECONDS))
        assertTrue(threads.all { it !== callingThread })
        assertEquals(listOf("{\"text\":\"one\"}", "{\"text\":\"二\"}"), values)
        assertEquals(DuplexSendResult.ACCEPTED, transport.send("{}"))
        transport.closeAndDrain(); transport.closeAndDrain()
        factory.events.text("{\"text\":\"private-late\"}")
        factory.events.opened(); factory.events.failed(); factory.events.closed()
        assertEquals(2, values.size)
        assertTrue(factory.peer.aborted)
        assertEquals(DuplexSendResult.REJECTED, transport.send("{}"))
        transport.connect(duplexRequest, Listener())
        assertEquals(1, factory.creations)
    }

    @Test fun duplexInvalidUtf8FailsAsynchronouslyAndNeverForwardsErrorText() {
        val factory = FakeSockets()
        val transport = DuplexNetworkTransport(factory)
        val failed = CountDownLatch(1)
        val count = AtomicInteger()
        val caller = Thread.currentThread()
        transport.connect(duplexRequest, object : Listener() {
            override fun message(json: String) { count.incrementAndGet() }
            override fun failed() {
                assertNotSame(caller, Thread.currentThread())
                failed.countDown()
            }
        })
        factory.events.binary(byteArrayOf(0xc3.toByte(), 0x28))
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        transport.closeAndDrain()
        assertEquals(0, count.get())
        assertTrue(factory.peer.aborted)
    }

    @Test fun closeAndDrainWaitsForExecutingCallbackAndDropsQueuedMessages() {
        val factory = FakeSockets()
        val transport = DuplexNetworkTransport(factory)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val messages = AtomicInteger()
        transport.connect(duplexRequest, object : Listener() {
            override fun opened() { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
            override fun message(json: String) { messages.incrementAndGet() }
        })
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        factory.events.text("{}")
        val closer = Thread {
            closing.countDown(); transport.closeAndDrain(); closed.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(closing.await(2, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (transport.send("{}") == DuplexSendResult.ACCEPTED && System.nanoTime() < deadline) Thread.yield()
        assertEquals(DuplexSendResult.REJECTED, transport.send("{}"))
        assertFalse(closed.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        closer.join(2000)
        factory.events.text("{}")
        assertEquals(0, messages.get())
        assertTrue(factory.peer.aborted)
    }

    @Test fun callbackQueueOverflowPurgesAudioAndReportsOnlyOneFailure() {
        val factory = FakeSockets()
        val transport = DuplexNetworkTransport(factory)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val failures = AtomicInteger()
        val messages = AtomicInteger()
        transport.connect(duplexRequest, object : Listener() {
            override fun opened() { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
            override fun message(json: String) { messages.incrementAndGet() }
            override fun failed() {
                failures.incrementAndGet()
                transport.closeAndDrain() // reentrant close must not deadlock/interrupt the callback
                assertFalse(Thread.currentThread().isInterrupted)
                failed.countDown()
            }
        })
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        repeat(NetworkLimits.QUEUED_MESSAGES + 1) { factory.events.text("{}") }
        assertTrue(factory.peer.aborted)
        release.countDown()
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        transport.closeAndDrain()
        assertEquals(0, messages.get())
        assertEquals(1, failures.get())
    }

    @Test fun uncertainDuplexSendReturnsUnknownWithoutReplay() {
        val factory = FakeSockets()
        val transport = DuplexNetworkTransport(factory)
        val opened = CountDownLatch(1)
        val failed = CountDownLatch(1)
        transport.connect(duplexRequest, object : Listener() {
            override fun opened() { opened.countDown() }
            override fun failed() { failed.countDown() }
        })
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        factory.peer.result = DuplexSendResult.UNKNOWN
        assertEquals(DuplexSendResult.UNKNOWN, transport.send("{}"))
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        transport.closeAndDrain()
        assertEquals(1, factory.peer.sends)
        assertTrue(factory.peer.aborted)
    }

    @Test fun duplexAbortDoesNotWaitForAnExecutingCallback() {
        val factory = FakeSockets()
        val transport = DuplexNetworkTransport(factory)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val messages = AtomicInteger()
        transport.connect(duplexRequest, object : Listener() {
            override fun opened() { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            override fun message(json: String) { messages.incrementAndGet() }
        })
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        factory.events.text("{}")
        val stopper = Thread {
            try { transport.abort() } finally { aborted.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue("Abort must precede callback drain", aborted.await(1, TimeUnit.SECONDS))
            assertTrue(factory.peer.aborted)
            assertEquals(DuplexSendResult.REJECTED, transport.send("{}"))
        } finally {
            release.countDown(); stopper.join(2000); transport.closeAndDrain()
        }
        factory.events.text("{}")
        assertEquals(0, messages.get())
    }

    private open class Listener : DuplexTransport.Listener {
        override fun opened() { }
        override fun message(json: String) { }
        override fun failed() { }
        override fun closed() { }
    }
    private class FakeSockets(private val autoOpen: Boolean = true) : NetworkSocketFactory {
        lateinit var events: SocketEvents
        lateinit var peer: FakeSocket
        var creations = 0
        var limit = 0
        override fun create(url: String, headers: Map<String, String>, limit: Int, events: SocketEvents): NetworkSocket {
            this.events = events; this.limit = limit; creations++
            return FakeSocket { if (autoOpen) events.opened() }.also { peer = it }
        }
    }
    private class FakeSocket(private val onConnect: () -> Unit) : NetworkSocket {
        @Volatile var aborted = false
        var sends = 0
        var result = DuplexSendResult.ACCEPTED
        var onSend: () -> Unit = { }
        override fun connect() { onConnect() }
        override fun sendBinary(bytes: ByteArray): DuplexSendResult { sends++; onSend(); return result }
        override fun sendText(text: String): DuplexSendResult { sends++; return result }
        override fun abort() { aborted = true }
    }
}
