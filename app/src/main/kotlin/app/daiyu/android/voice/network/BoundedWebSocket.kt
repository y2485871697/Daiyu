package app.daiyu.android.voice.network

import app.daiyu.android.voice.duplex.DuplexSendResult
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.java_websocket.WebSocket
import org.java_websocket.WebSocketImpl
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.Opcode
import org.java_websocket.exceptions.InvalidHandshakeException
import org.java_websocket.exceptions.LimitExceededException
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.Handshakedata
import org.java_websocket.handshake.ServerHandshake
import org.slf4j.LoggerFactory
import org.slf4j.helpers.NOPLoggerFactory

internal interface SocketEvents {
    fun opened()
    fun binary(bytes: ByteArray)
    fun text(text: String)
    fun failed()
    fun closed()
}

/** Internal seam for deterministic tests; production only installs Java-WebSocket. */
internal interface NetworkSocket {
    fun connect()
    fun sendBinary(bytes: ByteArray): DuplexSendResult
    fun sendText(text: String): DuplexSendResult
    fun abort()
}

internal fun interface NetworkSocketFactory {
    fun create(url: String, headers: Map<String, String>, limit: Int, events: SocketEvents): NetworkSocket
}

internal val realSockets = NetworkSocketFactory { url, headers, limit, events ->
    BoundedWebSocket(checkedUri(url, "wss"), checkedHeaders(headers), limit, events)
}

internal class BoundedDraft(
    private val frameLimit: Int,
    private val fragmentLimit: Int = 1024,
) : Draft_6455(emptyList(), frameLimit) {
    // v1.6.0 buffers non-FIN continuations without checking its cumulative byte limit.
    // Keep the pre-retention budget here; empty fragments also retain a list entry upstream.
    private var messageBytes = 0
    private var messageFragments = 0
    // Upstream reset() does not clear its private continuation/list state. Replace only the
    // frame processor on reset so its retained fragments and our budgets are reset together.
    private var frameProcessor = Draft_6455(emptyList(), frameLimit)

    override fun processFrame(webSocketImpl: WebSocketImpl, frame: Framedata) {
        val data = frame.opcode == Opcode.BINARY || frame.opcode == Opcode.TEXT ||
            frame.opcode == Opcode.CONTINUOUS
        if (data) {
            val bytes = frame.payloadData.remaining()
            if (bytes > frameLimit - messageBytes || messageFragments >= fragmentLimit) {
                throw LimitExceededException("voice_message_limit", frameLimit)
            }
            messageBytes += bytes
            messageFragments++
        }
        frameProcessor.processFrame(webSocketImpl, frame)
        if (data && frame.isFin) {
            messageBytes = 0
            messageFragments = 0
        }
    }

    override fun reset() {
        super.reset()
        frameProcessor = Draft_6455(emptyList(), frameLimit)
        messageBytes = 0
        messageFragments = 0
    }

    // Upstream otherwise accumulates an unfinished HTTP upgrade without a byte cap.
    // This wraps the existing parser; it does not parse HTTP/WebSocket/TLS itself.
    override fun translateHandshake(buffer: ByteBuffer): Handshakedata {
        if (buffer.remaining() > 32 * 1024) throw InvalidHandshakeException("voice_handshake_limit")
        return super.translateHandshake(buffer)
    }
    override fun copyInstance(): Draft = BoundedDraft(frameLimit, fragmentLimit)
}

/** No TLS implementation or RFC6455 codec here: both belong to the platform / mature client. */
internal class BoundedWebSocket(
    uri: URI,
    headers: Map<String, String>,
    private val limit: Int,
    private val events: SocketEvents,
) : NetworkSocket {
    private val lock = Any()
    private var stopped = false
    private var terminal = false
    private var started = false
    private var rawSocket: Socket? = null
    private var tlsSocket: SSLSocket? = null

    init {
        checkNetwork(limit in 1..NetworkLimits.FRAME_BYTES)
        // Upstream TRACE prints frame payloads; fail closed if a host adds another SLF4J binding.
        // This check runs BEFORE constructing the upstream client (and its logger).
        checkNetwork(LoggerFactory.getILoggerFactory() is NOPLoggerFactory)
    }

    private val client = object : WebSocketClient(
        uri, BoundedDraft(limit), headers, NetworkLimits.CONNECT_TIMEOUT_MS,
    ) {
        override fun onOpen(handshake: ServerHandshake) {
            if (synchronized(lock) { !stopped && !terminal }) events.opened()
        }
        override fun onMessage(message: String) {
            if (synchronized(lock) { !stopped && !terminal }) events.text(message)
        }
        override fun onMessage(bytes: ByteBuffer) {
            if (!synchronized(lock) { !stopped && !terminal }) return
            if (bytes.remaining() > limit) { fail(); return }
            val copy = ByteArray(bytes.remaining())
            bytes.get(copy)
            events.binary(copy)
        }
        override fun onError(error: Exception) { fail() }
        override fun onClosing(code: Int, reason: String, remote: Boolean) {
            // A protocol/limit error must abort immediately, not let upstream flush queued audio.
            if (code != 1000 && code != 1001) fail()
        }
        override fun onClose(code: Int, reason: String, remote: Boolean) {
            val deliver = synchronized(lock) {
                if (stopped || terminal) false else { terminal = true; true }
            }
            // Upstream invokes onClose before marking its engine CLOSED. Defer no user work here;
            // internal event sinks only enqueue/detach, and abort must not recursively close engine.
            synchronized(lock) { stopped = true }
            try { rawSocket?.close() } catch (_: Exception) { }
            try { tlsSocket?.close() } catch (_: Exception) { }
            engine().outQueue.clear()
            engine().inQueue.clear()
            if (deliver) {
                if (code == 1000 || code == 1001) events.closed() else events.failed()
            }
        }
        override fun onWebsocketPing(conn: WebSocket, frame: Framedata) {
            // Bound automatic pongs too. A slow peer cannot grow the client's unbounded outQueue.
            val overflow = synchronized(lock) {
                if (stopped || terminal) return
                if (engine().outQueue.size >= NetworkLimits.QUEUED_MESSAGES) true
                else { super.onWebsocketPing(conn, frame); false }
            }
            if (overflow) fail()
        }
    }.apply {
        isDaemon = true
        connectionLostTimeout = 0 // no unsolicited ping queue / timer; host owns session deadlines
        setSocketFactory(object : SocketFactory() {
            override fun createSocket(): Socket {
                // Publish the raw socket BEFORE DNS/connect/TLS, so cancellation closes actual I/O.
                val raw = Socket()
                synchronized(lock) {
                    if (stopped) { raw.close(); throw IOException("voice_socket_closed") }
                    rawSocket = raw
                }
                try {
                    raw.connect(InetSocketAddress(uri.host, if (uri.port == -1) 443 else uri.port),
                        NetworkLimits.CONNECT_TIMEOUT_MS)
                    raw.soTimeout = 30_000 // handshake/read deadline, not an automatic retry
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(raw, uri.host, if (uri.port == -1) 443 else uri.port, true) as SSLSocket
                    synchronized(lock) {
                        if (stopped) { ssl.close(); throw IOException("voice_socket_closed") }
                        tlsSocket = ssl
                    }
                    // WebSocketClient applies endpointIdentificationAlgorithm="HTTPS" before I/O.
                    return ssl
                } catch (_: Exception) {
                    try { raw.close() } catch (_: Exception) { }
                    throw IOException("voice_socket_connect_failed")
                }
            }
            override fun createSocket(host: String, port: Int): Socket = throw IOException("voice_socket_factory")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
                throw IOException("voice_socket_factory")
            override fun createSocket(host: InetAddress, port: Int): Socket = throw IOException("voice_socket_factory")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
                throw IOException("voice_socket_factory")
        })
    }

    private fun engine() = client.connection as WebSocketImpl

    override fun connect() {
        synchronized(lock) {
            if (stopped || started) return
            started = true
            client.connect() // starts the library's daemon I/O thread; never connectBlocking/reconnect
        }
    }

    private fun fail() {
        val deliver = synchronized(lock) {
            if (stopped || terminal) false else { terminal = true; true }
        }
        if (deliver) events.failed()
        abort()
    }

    override fun sendBinary(bytes: ByteArray): DuplexSendResult = send(bytes.size) { client.send(bytes) }
    override fun sendText(text: String): DuplexSendResult {
        val size = try { boundedUtf8(text, limit).size } catch (_: Exception) { return DuplexSendResult.REJECTED }
        return send(size) { client.send(text) }
    }

    private fun send(size: Int, action: () -> Unit): DuplexSendResult = synchronized(lock) {
        if (stopped || terminal || !client.isOpen || size > limit ||
            engine().outQueue.size >= NetworkLimits.QUEUED_MESSAGES) return DuplexSendResult.REJECTED
        try {
            action()
            // ACCEPTED means locally queued, never a server acknowledgment.
            DuplexSendResult.ACCEPTED
        } catch (_: Exception) {
            // Never attempt to decide whether bytes escaped or replay an uncertain send.
            DuplexSendResult.UNKNOWN
        }
    }

    override fun abort() {
        val state = synchronized(lock) {
            val first = !stopped
            stopped = true
            Triple(first, rawSocket, tlsSocket)
        }
        // Never acquire the engine's monitor while holding our state monitor: upstream calls
        // onClose with that monitor held. Closing raw TCP also prevents SSL close_notify stalling.
        try { state.second?.close() } catch (_: Exception) { }
        try { state.third?.close() } catch (_: Exception) { }
        engine().outQueue.clear()
        engine().inQueue.clear()
        // Force close only once (onClose may re-enter abort). Concurrent aborts still close raw I/O.
        if (state.first) try { client.closeConnection(1000, "") } catch (_: Exception) { }
    }
}
