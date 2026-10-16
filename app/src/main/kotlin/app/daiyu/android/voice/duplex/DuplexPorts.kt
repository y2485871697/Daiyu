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
    /** Abort pending I/O, discard queued sends and detach callbacks before returning. Idempotent. */
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
    /** Must stop capture, purge playback, release effects/recorder/player, and join workers. */
    fun closeAndDrain()
}
