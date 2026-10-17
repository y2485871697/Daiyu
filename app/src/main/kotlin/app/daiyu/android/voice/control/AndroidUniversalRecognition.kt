package app.daiyu.android.voice.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import app.daiyu.android.voice.*
import app.daiyu.android.voice.duplex.AndroidDuplexAudio
import app.daiyu.android.voice.network.VoiceNetworkAdapters
import app.daiyu.android.voice.offline.AndroidOfflineSpeechDevice
import app.daiyu.android.voice.offline.OfflineSpeechPack
import app.daiyu.android.voice.offline.OfflineSpeechSession
import app.daiyu.android.voice.providers.DoubaoAsrConfig
import app.daiyu.android.voice.providers.DoubaoAsrConnection
import app.daiyu.android.voice.providers.DoubaoAsrProtocol
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Frozen independently of the dictation/read-aloud settings; never contains chat credentials. */
internal sealed class UniversalRecognitionConfiguration {
    class System(val language: String = Locale.getDefault().toLanguageTag()) : UniversalRecognitionConfiguration()
    class Doubao(val key: String, val resource: String, val captureMillis: Long = 20_000) : UniversalRecognitionConfiguration() {
        override fun toString() = "UniversalRecognitionConfiguration.Doubao(redacted)"
    }
    /** Explicitly selected offline. Disabled/unready pack is unavailable, NEVER a system fallback. */
    data object Offline : UniversalRecognitionConfiguration()
}

internal class AndroidUniversalRecognition(private val context: Context) {
    fun available(config: UniversalRecognitionConfiguration): Boolean = when (config) {
        is UniversalRecognitionConfiguration.System -> config.language.isNotBlank()
        is UniversalRecognitionConfiguration.Doubao -> config.key.isNotBlank() &&
            config.resource in DoubaoAsrProtocol.resources && config.captureMillis in 1_000..60_000 &&
            '\r' !in config.key && '\n' !in config.key
        UniversalRecognitionConfiguration.Offline -> offlineReady()
    }

    fun start(turn: VoiceControlTurn, config: UniversalRecognitionConfiguration,
        allowed: () -> Boolean, events: VoiceControlPorts.RecognitionEvents): VoiceControlRecognition {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(available(config)) { "universal_recognition_configuration_unavailable" }
        check(allowed() && permitted()) { "universal_recognition_revoked" }
        val owner = VoiceOwner(turn.permit.conversationId, turn.requestId)
        val admitted = { allowed() && permitted() && available(config) }
        return when (config) {
            is UniversalRecognitionConfiguration.System -> system(owner, config, admitted, events)
            is UniversalRecognitionConfiguration.Doubao -> DoubaoInput(owner, turn, config, admitted, events).also { it.start() }
            UniversalRecognitionConfiguration.Offline -> offline(owner, admitted, events)
        }
    }
    private fun permitted() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun offlineReady() = OfflineSpeechPack.state.value.enabled && OfflineSpeechPack.state.value.ready

    private fun system(owner: VoiceOwner, config: UniversalRecognitionConfiguration.System,
        allowed: () -> Boolean, events: VoiceControlPorts.RecognitionEvents): VoiceControlRecognition {
        val closed = AtomicBoolean(false)
        val releaseFailure = AtomicReference<Throwable?>(null)
        val dictation = AndroidDictation(context.applicationContext, ActivityVoiceAudio.gate)
        val input = object : VoiceControlRecognition {
            override fun closeAndDrain() {
                closed.set(true)
                try {
                    dictation.stop(owner) // Existing recognizer synchronously cancel+destroy, exact lease only.
                } catch (failure: Throwable) { releaseFailure.set(failure); throw failure }
                releaseFailure.get()?.let { throw it }
            }
        }
        try {
            dictation.start(owner, object : DictationHost {
                private fun live() = !closed.get() && allowed()
                override fun permissionRequired() { if (!closed.get()) events.failed() }
                override fun partial(owner: VoiceOwner, text: String) {
                    if (live()) events.partial(text) else if (!closed.get()) events.failed()
                }
                override fun finalDraft(owner: VoiceOwner, transcript: ConfirmableTranscript) {
                    // AndroidDictation delivers this only after onResults + strict input release.
                    if (!live()) { if (!closed.get()) events.failed(); return }
                    if (!transcript.isDone()) { events.failed(); return }
                    val text = transcript.confirm() ?: run { events.failed(); return }
                    if (closed.compareAndSet(false, true)) events.finalResult(text)
                }
                override fun state(state: VoiceState) {
                    if (closed.get()) return
                    when (state.phase) {
                        VoicePhase.LISTENING -> if (live()) events.listening() else events.failed()
                        VoicePhase.FAILED, VoicePhase.CANCELLED -> {
                            if (state.detail == "recognizer_release_failed") releaseFailure.set(
                                IllegalStateException("universal_recognizer_release_failed"))
                            events.failed()
                        }
                        else -> Unit // End-of-speech/PREPARING is not a final result.
                    }
                }
            }, config.language, { !closed.get() && allowed() })
        } catch (failure: Throwable) { input.closeAndDrain(); throw failure }
        return input
    }

    private fun offline(owner: VoiceOwner, allowed: () -> Boolean,
        events: VoiceControlPorts.RecognitionEvents): VoiceControlRecognition {
        val closed = AtomicBoolean(false)
        val session = OfflineSpeechSession(ActivityVoiceAudio.gate, owner,
            { !closed.get() && allowed() && offlineReady() }, AndroidOfflineSpeechDevice(context.applicationContext),
            object : OfflineSpeechSession.Events {
                override fun listening() { if (!closed.get() && allowed()) events.listening() }
                override fun partial(text: String) { if (!closed.get() && allowed()) events.partial(text) }
                override fun complete(text: String) {
                    if (!closed.get() && allowed() && closed.compareAndSet(false, true)) events.finalResult(text)
                    else if (!closed.get()) events.failed()
                }
                override fun cancelled() { if (!closed.get()) events.failed() }
                override fun failed() { if (!closed.get()) events.failed() }
            })
        val input = object : VoiceControlRecognition {
            override fun closeAndDrain() { closed.set(true); session.close() }
        }
        try { session.start() } catch (failure: Throwable) { input.closeAndDrain(); throw failure }
        return input
    }

    /**
     * Real provider protocol + existing Android PCM device. A definite utterance closes capture
     * and sends the protocol last marker; it NEVER submits the hypothesis. Only final-frame text
     * is a final result. A finite capture ceiling also sends last (reserving time for final reply).
     * No retry/reconnect/VAD substitute, no provider calls on the control thread.
     */
    private class DoubaoInput(
        private val owner: VoiceOwner,
        private val turn: VoiceControlTurn,
        private val config: UniversalRecognitionConfiguration.Doubao,
        private val allowed: () -> Boolean,
        private val events: VoiceControlPorts.RecognitionEvents,
    ) : VoiceControlRecognition {
        private val main = Handler(Looper.getMainLooper())
        private val gate = ActivityVoiceAudio.gate
        private val audio = AndroidDuplexAudio()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val frames = Channel<ByteArray>(64)
        private val ended = AtomicBoolean(false)
        private val finishing = AtomicBoolean(false)
        private val connection = AtomicReference<DoubaoAsrConnection?>(null)
        @Volatile private var lease: AudioOwnerGate.Lease? = null
        @Volatile private var releaseFailure: Throwable? = null
        private val ceiling = Runnable { if (!ended.get()) try { finishCapture() } catch (_: Throwable) { fail() } }
        private fun live() = !ended.get() && allowed() && lease?.let(gate::current) == true

        fun start() {
            try {
                lease = gate.acquire(owner, { !ended.get() && allowed() }) {
                    val notify = ended.compareAndSet(false, true)
                    try { releaseResources() } finally { if (notify) main.post { events.failed() } }
                }
                scope.launch {
                    try {
                        val socket = DoubaoAsrProtocol.open(DoubaoAsrConfig(config.key, config.resource), VoiceNetworkAdapters.asr())
                        connection.set(socket)
                        if (ended.get()) { connection.getAndSet(null)?.close(); return@launch }
                        try {
                            withContext(Dispatchers.Main.immediate) {
                                check(live())
                                audio.open { main.post { fail() } }
                                audio.startCapture { pcm ->
                                    if (!ended.get() && !finishing.get() && !frames.trySend(pcm).isSuccess)
                                        main.post { fail() }
                                }
                                check(main.postDelayed(ceiling, minOf(config.captureMillis, turn.permit.limits.recognitionMillis / 2)))
                                events.listening()
                            }
                            coroutineScope {
                                val sender = launch {
                                    for (frame in frames) socket.sendPcm(frame)
                                    socket.sendPcm(ByteArray(0), last = true)
                                }
                                try {
                                    while (true) {
                                        val result = socket.receive() ?: error("universal_asr_closed")
                                        check(result.text.length <= 100_000)
                                        withContext(Dispatchers.Main.immediate) {
                                            check(live())
                                            if (result.final) complete(result.text)
                                            else {
                                                events.partial(result.text)
                                                if (result.utteranceDone) finishCapture()
                                            }
                                        }
                                        if (result.final) break
                                    }
                                } finally { sender.cancelAndJoin() }
                            }
                        } finally { connection.compareAndSet(socket, null); socket.close() }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Throwable) { main.post { fail() } }
                }
            } catch (failure: Throwable) { closeAndDrain(); throw failure }
        }

        private fun finishCapture() {
            if (!live() || !finishing.compareAndSet(false, true)) return
            main.removeCallbacks(ceiling)
            audio.closeAndDrain() // Stop/release capture before sending last; network remains open.
            frames.close()
        }
        private fun complete(text: String) {
            if (!live() || !ended.compareAndSet(false, true)) return
            val token = lease
            try {
                if (token == null || !gate.releaseAfter(token, ::releaseResources)) releaseResources()
                events.finalResult(text)
            } catch (_: Throwable) { events.failed() }
        }
        private fun fail() {
            if (ended.get()) return
            // Retain strict release failure for the controller's close; do not crash a Handler.
            try { closeAndDrain() } catch (_: Throwable) { }
            events.failed()
        }
        private fun releaseResources() {
            // Fence first; no host/control monitor is held across audio joins or network close.
            ended.set(true)
            main.removeCallbacks(ceiling)
            frames.cancel()
            connection.getAndSet(null)?.close()
            scope.cancel()
            try { audio.closeAndDrain() } catch (failure: Throwable) { releaseFailure = failure; throw failure }
            releaseFailure?.let { throw it }
        }
        override fun closeAndDrain() {
            ended.set(true)
            // Cancellation invokes releaseResources and poisons the existing gate on release failure.
            if (!gate.cancel(owner)) releaseResources()
            releaseFailure?.let { throw it }
        }
    }
}
