package app.daiyu.android.voice.network

import app.daiyu.android.voice.duplex.DuplexSendResult
import app.daiyu.android.voice.providers.VoiceSocket
import app.daiyu.android.voice.providers.VoiceSocketRequest
import app.daiyu.android.voice.providers.VoiceSocketTransport
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

internal class AsrNetworkTransport(
    private val sockets: NetworkSocketFactory = realSockets,
) : VoiceSocketTransport {
    override suspend fun connect(request: VoiceSocketRequest, maxFrameBytes: Int): VoiceSocket {
        checkNetwork(maxFrameBytes in 1..NetworkLimits.FRAME_BYTES)
        checkedUri(request.url, "wss")
        val headers = checkedHeaders(request.headers)
        return suspendCancellableCoroutine { continuation ->
            val socket = AsrSocket(maxFrameBytes, continuation)
            continuation.invokeOnCancellation { socket.close() }
            try {
                socket.attach(sockets.create(request.url, headers, maxFrameBytes, socket))
            } catch (_: Exception) { socket.fail() }
        }
    }
}

private class AsrSocket(
    private val limit: Int,
    private val handshake: CancellableContinuation<VoiceSocket>,
) : VoiceSocket, SocketEvents {
    private val lock = Any()
    private var peer: NetworkSocket? = null
    private var ended = false
    private var opened = false
    private val incoming = Channel<ByteArray>(NetworkLimits.QUEUED_MESSAGES)

    fun attach(socket: NetworkSocket) {
        synchronized(lock) {
            if (ended) { socket.abort(); return }
            peer = socket
            socket.connect()
        }
    }

    override fun opened() {
        synchronized(lock) {
            if (ended || opened) return
            opened = true
            // Prompt cancellation after successful handshake still releases the otherwise lost socket.
            handshake.resume(this, onCancellation = { _, socket, _ -> socket.close() })
        }
    }

    override fun binary(bytes: ByteArray) {
        val overflow = synchronized(lock) {
            if (ended || !opened) return
            bytes.size > limit || !incoming.trySend(bytes).isSuccess
        }
        if (overflow) fail()
    }
    override fun text(text: String) { fail() }
    override fun failed() { fail() }
    override fun closed() {
        synchronized(lock) {
            if (ended) return
            ended = true
            if (!opened && handshake.isActive) handshake.resumeWithException(networkFailure())
            incoming.close()
        }
    }

    fun fail() {
        val socket = synchronized(lock) {
            ended = true
            if (!opened && handshake.isActive) handshake.resumeWithException(networkFailure())
            incoming.cancel(CancellationException("voice_socket_failed"))
            peer
        }
        try { socket?.abort() } catch (_: Exception) { }
    }

    override suspend fun send(bytes: ByteArray) {
        try {
            currentCoroutineContext().ensureActive()
            val result = synchronized(lock) {
                if (ended || !opened || bytes.size > limit) DuplexSendResult.REJECTED
                else peer?.sendBinary(bytes) ?: DuplexSendResult.REJECTED
            }
            if (result != DuplexSendResult.ACCEPTED) throw networkFailure()
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (_: Exception) {
            close()
            throw networkFailure()
        }
    }

    override suspend fun receive(): ByteArray? = try {
        currentCoroutineContext().ensureActive()
        val result = incoming.receiveCatching()
        currentCoroutineContext().ensureActive()
        if (result.exceptionOrNull() != null) throw networkFailure()
        result.getOrNull()
    } catch (cancelled: CancellationException) {
        close()
        throw cancelled
    } catch (_: Exception) {
        close()
        throw networkFailure()
    }

    override fun close() {
        val socket = synchronized(lock) {
            ended = true
            if (!opened && handshake.isActive) handshake.resumeWithException(networkFailure())
            incoming.cancel(CancellationException("voice_socket_closed"))
            peer
        }
        // No close handshake: cancel pending work and drop unsent audio; always nonthrowing.
        try { socket?.abort() } catch (_: Exception) { }
    }
}
