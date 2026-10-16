package app.daiyu.android.voice.network

import app.daiyu.android.voice.duplex.DuplexRequest
import app.daiyu.android.voice.duplex.DuplexSendResult
import app.daiyu.android.voice.duplex.DuplexTransport
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class DuplexNetworkTransport(
    private val sockets: NetworkSocketFactory = realSockets,
) : DuplexTransport, SocketEvents {
    private val lock = Any()
    private val callbacks = Any()
    private var listener: DuplexTransport.Listener? = null
    private var peer: NetworkSocket? = null
    private var used = false
    private var drained = false
    private var opened = false
    private var terminal = false
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(NetworkLimits.QUEUED_MESSAGES), { task ->
            Thread(task, "voice-duplex-network-callback").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())

    override fun connect(request: DuplexRequest, listener: DuplexTransport.Listener) {
        synchronized(lock) {
            // One instance per call: a duplicate connect must never create another billable socket.
            if (used || drained) return
            used = true
            this.listener = listener
        }
        try {
            checkedUri(request.url, "wss")
            val headers = checkedHeaders(request.headers)
            val socket = sockets.create(request.url, headers, NetworkLimits.FRAME_BYTES, this)
            synchronized(lock) {
                if (drained || terminal) { socket.abort(); return }
                peer = socket
                socket.connect()
            }
        } catch (_: Exception) { failed() }
    }

    override fun send(json: String): DuplexSendResult {
        try { boundedUtf8(json, NetworkLimits.FRAME_BYTES) }
        catch (_: Exception) { return DuplexSendResult.REJECTED }
        val result = synchronized(lock) {
            if (drained || terminal || !opened) return DuplexSendResult.REJECTED
            try { peer?.sendText(json) ?: DuplexSendResult.REJECTED }
            catch (_: Exception) { DuplexSendResult.UNKNOWN }
        }
        if (result == DuplexSendResult.UNKNOWN) failed()
        return result
    }

    override fun opened() {
        synchronized(lock) {
            if (drained || terminal || opened) return
            opened = true
        }
        enqueue { it.opened() }
    }
    override fun text(text: String) {
        try { boundedUtf8(text, NetworkLimits.FRAME_BYTES) }
        catch (_: Exception) { failed(); return }
        enqueue { it.message(text) }
    }
    override fun binary(bytes: ByteArray) {
        if (bytes.size > NetworkLimits.FRAME_BYTES) { failed(); return }
        val json = try { strictUtf8(bytes) } catch (_: Exception) { failed(); return }
        enqueue { it.message(json) }
    }
    override fun failed() { finish(failure = true) }
    override fun closed() { finish(failure = false) }

    /** Serialize user callbacks on a dedicated worker, never on connect/send/library call stacks. */
    private fun enqueue(action: (DuplexTransport.Listener) -> Unit) {
        val rejected = synchronized(lock) {
            if (drained || terminal) return
            try { executor.execute { deliver(action) }; false }
            catch (_: RejectedExecutionException) { true }
        }
        if (rejected) finish(failure = true)
    }

    private fun deliver(action: (DuplexTransport.Listener) -> Unit) {
        synchronized(callbacks) {
            val target = synchronized(lock) { if (drained) null else listener } ?: return
            try { action(target) }
            catch (_: Exception) { closeAndDrain() } // Never let a listener exception reach an uncaught logger.
        }
    }

    private fun finish(failure: Boolean) {
        val socket = synchronized(lock) {
            if (drained || terminal) return
            terminal = true
            // Failure/overflow discards stale audio/transcripts. EOF preserves already queued messages.
            if (failure) executor.queue.clear()
            try {
                executor.execute {
                    try { deliver { if (failure) it.failed() else it.closed() } }
                    finally { closeAndDrain() }
                }
            } catch (_: RejectedExecutionException) {
                // A full EOF queue becomes a bounded failure, never an unbounded fallback queue.
                executor.queue.clear()
                try {
                    executor.execute {
                        try { deliver { it.failed() } } finally { closeAndDrain() }
                    }
                } catch (_: RejectedExecutionException) { /* Already draining. */ }
            }
            peer
        }
        try { socket?.abort() } catch (_: Exception) { }
    }

    override fun closeAndDrain() {
        // Detach FIRST so a worker cannot start queued callbacks while close waits for the active one.
        val socket = synchronized(lock) {
            drained = true
            listener = null
            executor.queue.clear()
            // No shutdownNow(): do not interrupt a host callback which is closing itself.
            executor.shutdown()
            peer // Retain the reference so concurrent close callers also fence/abort actual I/O.
        }
        // Fence an executing callback; reentrant close from that callback remains safe.
        synchronized(callbacks) {
            try { socket?.abort() } catch (_: Exception) { }
        }
    }
}
