package app.daiyu.android.voice.network

import app.daiyu.android.voice.duplex.DuplexSendResult
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.java_websocket.WebSocketImpl
import org.java_websocket.client.WebSocketClient
import org.java_websocket.enums.ReadyState
import org.java_websocket.enums.Role
import org.java_websocket.exceptions.InvalidHandshakeException
import org.java_websocket.exceptions.LimitExceededException
import org.java_websocket.framing.BinaryFrame
import org.java_websocket.framing.ContinuousFrame
import org.java_websocket.framing.Framedata
import org.java_websocket.framing.PingFrame
import org.java_websocket.framing.PongFrame
import org.java_websocket.framing.TextFrame
import org.java_websocket.handshake.ServerHandshake
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real pinned codec offline, not an imitation of the production frame decoder. */
class WebSocketBoundsTest {
    @Test fun outboundPcmBurstHasItsOwnBoundedBudgetAndDrainedFramesReleaseCapacity() {
        val frames = ArrayList<ByteBuffer>()
        repeat(64) {
            assertTrue(outboundQueueHasRoom(frames, 1000))
            frames += ByteBuffer.allocate(1014)
        }
        assertFalse(outboundQueueHasRoom(frames, 1000))
        frames.removeAt(0)
        assertTrue(outboundQueueHasRoom(frames, 1000))
        assertEquals(8, NetworkLimits.QUEUED_MESSAGES) // Incoming callback cap is unchanged.
    }

    @Test fun outboundByteBudgetIncludesHeadersAndCannotBeBypassedWithLargeFrames() {
        val limit = NetworkLimits.OUTBOUND_BYTES
        val frames = listOf(ByteBuffer.allocate(limit - 100))
        assertTrue(outboundQueueHasRoom(frames, 86))
        assertFalse(outboundQueueHasRoom(frames, 87))
        assertTrue(outboundQueueHasRoom(emptyList(), NetworkLimits.FRAME_BYTES))
        assertFalse(outboundQueueHasRoom(emptyList(), Int.MAX_VALUE))
        assertFalse(outboundQueueHasRoom(emptyList(), -1))
        val consumed = ByteBuffer.allocate(limit + 100).apply { position(100); limit(limit - 1) }
        assertTrue(outboundQueueHasRoom(listOf(consumed), 87)) // remaining(), not capacity().
        assertFalse(outboundQueueHasRoom(List(64) { ByteBuffer.allocate(0) }, 0))
    }

    @Test fun realSendUsesTheOutboundCountAndByteBudgetsWithoutNetwork() {
        val peer = BoundedWebSocket(URI("wss://example.invalid"), emptyMap(), NetworkLimits.FRAME_BYTES, Events())
        val client = clientOf(peer)
        val engine = client.connection as WebSocketImpl
        openOffline(engine)
        try {
            repeat(64) { assertEquals(DuplexSendResult.ACCEPTED, peer.sendText("pcm")) }
            assertEquals(64, engine.outQueue.size)
            assertEquals(DuplexSendResult.BACKPRESSURE, peer.sendText("pcm"))
            assertEquals(64, engine.outQueue.size)
            engine.outQueue.poll()
            assertEquals(DuplexSendResult.ACCEPTED, peer.sendText("pcm"))
            engine.outQueue.clear()
            engine.outQueue.add(ByteBuffer.allocate(NetworkLimits.OUTBOUND_BYTES - 14))
            assertEquals(DuplexSendResult.BACKPRESSURE, peer.sendBinary(byteArrayOf(1)))
            assertEquals(1, engine.outQueue.size)
        } finally { peer.abort() }
    }

    @Test fun automaticPongSharesTheRealSendBudgetWithoutNetwork() {
        for (byteLimit in listOf(false, true)) {
            val events = Events()
            val peer = BoundedWebSocket(URI("wss://example.invalid"), emptyMap(), NetworkLimits.FRAME_BYTES, events)
            val client = clientOf(peer)
            val engine = client.connection as WebSocketImpl
            openOffline(engine)
            try {
                if (byteLimit) engine.outQueue.add(ByteBuffer.allocate(NetworkLimits.OUTBOUND_BYTES - 14))
                else {
                    repeat(63) { engine.outQueue.add(ByteBuffer.allocate(1)) }
                    client.onWebsocketPing(engine, PingFrame().apply { setPayload(ByteBuffer.wrap(byteArrayOf(1))) })
                    assertEquals(64, engine.outQueue.size)
                    assertEquals(0, events.failures.get())
                }
                client.onWebsocketPing(engine, PingFrame().apply { setPayload(ByteBuffer.wrap(byteArrayOf(1))) })
                assertEquals(1, events.failures.get())
                assertEquals(DuplexSendResult.CONNECTION_FAILED, peer.sendText("pcm"))
                assertTrue(engine.outQueue.isEmpty())
            } finally { peer.abort() }
        }
    }

    @Test fun realSocketPreservesTerminalCauseBeforeNotifyingTransport() {
        for (code in listOf(1000, 1006)) {
            val events = Events()
            val peer = BoundedWebSocket(URI("wss://example.invalid"), emptyMap(), 16, events)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val raw = object : Socket() {
                override fun close() { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            }
            BoundedWebSocket::class.java.getDeclaredField("rawSocket").apply { isAccessible = true }.set(peer, raw)
            val closer = Thread { clientOf(peer).onClose(code, "", true) }.apply { start() }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertEquals(0, events.failures.get() + events.closes.get())
                assertEquals(if (code == 1000) DuplexSendResult.CONNECTION_CLOSED else DuplexSendResult.CONNECTION_FAILED,
                    peer.sendText("{}"))
            } finally { release.countDown(); closer.join(3000); peer.abort() }
            assertFalse(closer.isAlive)
            assertEquals(1, events.failures.get() + events.closes.get())
        }
    }

    private class Events : SocketEvents {
        val failures = AtomicInteger()
        val closes = AtomicInteger()
        override fun opened() { }
        override fun binary(bytes: ByteArray) { }
        override fun text(text: String) { }
        override fun failed() { failures.incrementAndGet() }
        override fun closed() { closes.incrementAndGet() }
    }

    private fun clientOf(peer: BoundedWebSocket) =
        BoundedWebSocket::class.java.getDeclaredField("client").apply { isAccessible = true }.get(peer) as WebSocketClient

    private fun openOffline(engine: WebSocketImpl) {
        // The pinned codec queues real frames; no connect(), DNS, socket or writer is started.
        WebSocketImpl::class.java.getDeclaredField("readyState").apply { isAccessible = true }.set(engine, ReadyState.OPEN)
    }

    @Test fun realClientCanBeCancelledBeforeConnectWithoutCallbacksOrNetwork() {
        val peer = BoundedWebSocket(URI("wss://example.invalid"), emptyMap(), 16, object : SocketEvents {
            override fun opened() = fail("not connected")
            override fun binary(bytes: ByteArray) = fail("not connected")
            override fun text(text: String) = fail("not connected")
            override fun failed() = fail("detached")
            override fun closed() = fail("detached")
        })
        peer.abort(); peer.abort()
        peer.connect() // stopped instance must never start DNS / actual I/O
        assertEquals(app.daiyu.android.voice.duplex.DuplexSendResult.REJECTED, peer.sendText("{}"))
    }

    @Test fun oversizeLengthHeaderIsRejectedBeforeAnyPayloadArrives() {
        val draft = BoundedDraft(16)
        draft.setParseMode(Role.CLIENT)
        // Server binary frame advertising 1 MiB; payload intentionally absent.
        val header = ByteBuffer.allocate(10).put(0x82.toByte()).put(127.toByte())
            .putLong(1_048_576).apply { flip() }
        try {
            draft.translateFrame(header)
            fail("must reject advertised oversize before allocating/reading payload")
        } catch (_: LimitExceededException) { }
        assertEquals(16, (draft.copyInstance() as BoundedDraft).maxFrameSize)
    }

    @Test fun fragmentedMessageCumulativeSizeIsAlsoBoundedBeforeNonFinRetention() {
        for (text in listOf(false, true)) {
            val draft = BoundedDraft(16)
            val client = RecordingClient(draft)
            draft.processFrame(client.engine, first(9, text))
            expectLimit { draft.processFrame(client.engine, continuation(9)) } // still NOT FIN
            assertTrue(client.sizes.isEmpty())
            // Rejected bytes must not have reached upstream's buffer list.
            draft.processFrame(client.engine, continuation(7, fin = true))
            assertEquals(listOf(16), client.sizes)
        }
    }

    @Test fun finPayloadIsIncludedAndSuccessfulFinRestartsBothBudgets() {
        val draft = BoundedDraft(16, 2)
        val client = RecordingClient(draft)
        repeat(2) {
            draft.processFrame(client.engine, first(9))
            expectLimit { draft.processFrame(client.engine, continuation(8, fin = true)) }
            draft.processFrame(client.engine, continuation(7, fin = true))
        }
        draft.processFrame(client.engine, first(16, fin = true))
        assertEquals(listOf(16, 16, 16), client.sizes)
    }

    @Test fun emptyFragmentsIncludingFinAreCountedAndControlDoesNotResetBudgets() {
        val draft = BoundedDraft(16) // actual production default: at most 1024 fragments
        val client = RecordingClient(draft)
        draft.processFrame(client.engine, first(0))
        repeat(1022) { draft.processFrame(client.engine, continuation(0)) }
        draft.processFrame(client.engine, PongFrame())
        draft.processFrame(client.engine, continuation(0))
        expectLimit { draft.processFrame(client.engine, continuation(0)) }
        expectLimit { draft.processFrame(client.engine, continuation(0, fin = true)) }
        assertTrue(client.sizes.isEmpty())
        draft.reset()
        repeat(2) {
            draft.processFrame(client.engine, first(0))
            draft.processFrame(client.engine, continuation(0))
            draft.processFrame(client.engine, continuation(0, fin = true))
        }
        assertEquals(listOf(0, 0), client.sizes)
    }

    @Test fun controlFramesDoNotResetByteBudgetAndResetDiscardsPendingUpstreamState() {
        val draft = BoundedDraft(16, 2)
        val client = RecordingClient(draft)
        draft.processFrame(client.engine, first(9))
        draft.processFrame(client.engine, PongFrame())
        expectLimit { draft.processFrame(client.engine, continuation(9)) }
        draft.reset()
        draft.processFrame(client.engine, first(8))
        draft.processFrame(client.engine, continuation(8, fin = true))
        assertEquals(listOf(16), client.sizes)
    }

    @Test fun copyPreservesBothCapsWithoutCopyingOrClearingPendingState() {
        val original = BoundedDraft(16, 2)
        val originalClient = RecordingClient(original)
        original.processFrame(originalClient.engine, first(9))
        val copy = original.copyInstance() as BoundedDraft
        assertEquals(16, copy.maxFrameSize)
        val client = RecordingClient(copy)
        copy.processFrame(client.engine, first(9)) // fresh byte budget and upstream state
        expectLimit { copy.processFrame(client.engine, continuation(9)) }
        copy.processFrame(client.engine, continuation(7, fin = true))
        copy.processFrame(client.engine, first(0))
        copy.processFrame(client.engine, continuation(0))
        expectLimit { copy.processFrame(client.engine, continuation(0)) }
        expectLimit { original.processFrame(originalClient.engine, continuation(9)) }
        original.processFrame(originalClient.engine, continuation(7, fin = true))
        assertEquals(listOf(16), client.sizes)
        assertEquals(listOf(16), originalClient.sizes)
    }

    // Never connect: exercise production BoundedDraft and the pinned library's real state machine.
    private class RecordingClient(draft: BoundedDraft) :
        WebSocketClient(URI("wss://example.invalid"), draft) {
        val engine get() = connection as WebSocketImpl
        val sizes = mutableListOf<Int>()
        override fun onOpen(handshake: ServerHandshake) { }
        override fun onMessage(message: String) { sizes.add(message.toByteArray().size) }
        override fun onMessage(bytes: ByteBuffer) { sizes.add(bytes.remaining()) }
        override fun onClose(code: Int, reason: String, remote: Boolean) { }
        override fun onError(error: Exception) { throw AssertionError(error) }
    }

    private fun first(size: Int, text: Boolean = false, fin: Boolean = false): Framedata =
        (if (text) TextFrame() else BinaryFrame()).apply {
            isFin = fin
            setPayload(ByteBuffer.wrap(ByteArray(size)))
        }

    private fun continuation(size: Int, fin: Boolean = false) = ContinuousFrame().apply {
        isFin = fin
        setPayload(ByteBuffer.wrap(ByteArray(size)))
    }

    private fun expectLimit(action: () -> Unit) {
        try {
            action()
            fail("message must be rejected before upstream retains another fragment")
        } catch (error: LimitExceededException) {
            assertEquals(1009, error.closeCode)
        }
    }

    @Test fun upgradeHeaderAccumulationIsBoundedAndCopyPreservesCaps() {
        val draft = BoundedDraft(16).copyInstance() as BoundedDraft
        assertEquals(16, draft.maxFrameSize)
        try {
            draft.translateHandshake(ByteBuffer.wrap(ByteArray(32 * 1024 + 1) { 65 }))
            fail("oversize unfinished upgrade must fail before upstream allocation")
        } catch (_: InvalidHandshakeException) { }
    }

    @Test fun strictUtf8RejectsReplacementDecodingAndUrisRejectCredentialRouting() {
        assertEquals("二", strictUtf8("二".toByteArray()))
        assertTrue(runCatching { strictUtf8(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
        for (url in listOf("ws://example.invalid", "wss://user:private@example.invalid", "wss://example.invalid/#private")) {
            val failure = runCatching { checkedUri(url, "wss") }.exceptionOrNull()!!
            assertEquals("语音网络传输失败", failure.message)
            assertNull(failure.cause)
        }
        assertEquals("example.invalid", checkedUri("wss://example.invalid/?token=private", "wss").host)
    }

    @Test fun requestAndHeaderCapsAreFiniteAndErrorsAreSanitized() {
        assertTrue(runCatching { boundedUtf8("二二", 5) }.isFailure)
        assertTrue(runCatching { checkedHeaders(mapOf("Authorization" to "private\r\nX: injected")) }.isFailure)
        assertTrue(runCatching { checkedHeaders(mapOf("Sec-WebSocket-Key" to "private")) }.isFailure)
        assertTrue(runCatching { checkedHeaders(mapOf("Proxy-Authorization" to "private")) }.isFailure)
        assertEquals("private", checkedHeaders(mapOf("X-Api-Key" to "private"))["X-Api-Key"])
    }
}
