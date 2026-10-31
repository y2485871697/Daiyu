package app.daiyu.android.voice.duplex

/** UNKNOWN means bytes might have left the process. Never replay an uncertain send. */
enum class DuplexSendResult { ACCEPTED, REJECTED, UNKNOWN }

/**
 * One instance per call; connect/send are nonblocking and must not wait for a callback.
 * All listener callbacks must be asynchronous (never inline from connect/send).
 * Deliver complete WebSocket text (or UTF-8 JSON binary) messages, not TCP fragments.
 * Implementations must disable automatic reconnect/replay and must not log credentials,
 * audio, server error text or transcripts. Transport/TLS implementation is host-injected.
 */
interface DuplexTransport {
    interface Listener {
        fun opened()
        fun message(json: String)
        fun failed()
        fun closed()
    }
    fun connect(request: DuplexRequest, listener: Listener)
    fun send(json: String): DuplexSendResult
    /** Immediate I/O cancellation, discarding queued sends without waiting for a listener. */
    fun abort() = closeAndDrain()
    /** Abort I/O and fence executing callbacks. Idempotent; may wait. */
    fun closeAndDrain()
}

/**
 * One instance per call. PCM16LE mono: 16k input, 24k output. The audio adapter owns
 * its bounded playback queue. Control methods must never wait for session callbacks;
 * callbacks must not be invoked inline by open/startCapture/enqueue/flush. Epoch changes drop queued AND
 * partly-written frames, pause/flush hardware and fence later writes of the old epoch.
 */
interface DuplexAudio {
    fun open(onFailure: () -> Unit)
    fun startCapture(onPcm: (ByteArray) -> Unit)
    fun enqueue(pcm: ByteArray, epoch: Long)
    fun flush(epoch: Long)
    /** Fence capture/playback and stop/pause/flush hardware now; never join workers. */
    fun stopImmediately() { flush(Long.MAX_VALUE) }
    /** False means unavailable/failed; callers must not optimistically update UI. */
    fun setSpeaker(speaker: Boolean): Boolean = false
    val speaker: Boolean get() = true
    /** Must stop capture, purge playback, release effects/recorder/player, and join workers. */
    fun closeAndDrain()
}
