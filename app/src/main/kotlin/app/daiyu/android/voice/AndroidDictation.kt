package app.daiyu.android.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

interface DictationHost {
    /** UI must request permission from an Activity and then explicitly retry. */
    fun permissionRequired()
    fun partial(owner: VoiceOwner, text: String)
    /** This is a draft, not an automatic send. ConfirmableTranscript.confirm gates a single send. */
    fun finalDraft(owner: VoiceOwner, transcript: ConfirmableTranscript)
    fun state(state: VoiceState)
}

internal interface SystemDictationRecognizer {
    fun listen(listener: RecognitionListener, language: String)
    fun cancel()
    fun destroy()
}
internal interface SystemDictationPlatform {
    fun permitted(): Boolean
    fun available(): Boolean
    fun create(): SystemDictationRecognizer
}
private class AndroidDictationPlatform(context: Context) : SystemDictationPlatform {
    private val app = context.applicationContext
    override fun permitted() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override fun available() = SpeechRecognizer.isRecognitionAvailable(app)
    override fun create(): SystemDictationRecognizer {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(app)
        return object : SystemDictationRecognizer {
            override fun listen(listener: RecognitionListener, language: String) {
                recognizer.setRecognitionListener(listener)
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
            }
            override fun cancel() { recognizer.cancel() }
            override fun destroy() { recognizer.destroy() }
        }
    }
}

/** SpeechRecognizer is one-shot dictation, NOT a real-time duplex backend. */
class AndroidDictation internal constructor(
    private val platform: SystemDictationPlatform,
    private val gate: AudioOwnerGate,
    private val main: Handler = Handler(Looper.getMainLooper())
) {
    constructor(context: Context, gate: AudioOwnerGate) : this(AndroidDictationPlatform(context), gate)

    fun start(owner: VoiceOwner, host: DictationHost, language: String = Locale.getDefault().toLanguageTag()) =
        start(owner, host, language, { true })

    internal fun start(owner: VoiceOwner, host: DictationHost, language: String, admission: () -> Boolean) {
        if (!admission()) return
        if (!platform.permitted()) {
            main.post { host.permissionRequired(); host.state(VoiceState(owner, VoicePhase.FAILED, detail = "recognizer_permission_denied")) }
            return
        }
        val session = Session(owner, host, language)
        session.lease = gate.acquire(owner, admission) {
            // Do not surrender ownership before both cancel and destroy have been attempted.
            releaseOnMain(main) {
                session.teardown(cancel = true)
                host.state(VoiceState(owner, VoicePhase.CANCELLED))
            }
        }
        main.post { session.start() }
    }

    private inner class Session(val owner: VoiceOwner, val host: DictationHost, val language: String) {
        lateinit var lease: AudioOwnerGate.Lease
        private var recognizer: SystemDictationRecognizer? = null
        private val transcript = ConfirmableTranscript()
        private fun live() = gate.current(lease)
        fun teardown(cancel: Boolean = false) {
            val old = recognizer; recognizer = null
            try { if (cancel) old?.cancel() } finally { old?.destroy() }
        }
        private fun finish(): Boolean = try {
            gate.releaseAfter(lease) { teardown() }
        } catch (_: Exception) {
            host.state(VoiceState(owner, VoicePhase.FAILED, detail = "recognizer_release_failed")); false
        }
        private fun failed(reason: String, permission: Boolean = false) {
            if (!live() || !finish()) return
            if (permission) host.permissionRequired()
            host.state(VoiceState(owner, VoicePhase.FAILED, detail = reason))
        }
        // Android delivers on main; posting also makes injected/binder callbacks lease-safe.
        private fun callback(action: () -> Unit) { main.post { if (live()) action() } }
        fun start() {
            if (!live()) return
            try {
                if (!platform.available()) { failed("recognizer_unavailable"); return }
                recognizer = platform.create()
                recognizer!!.listen(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = callback { host.state(VoiceState(owner, VoicePhase.LISTENING)) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() = callback { host.state(VoiceState(owner, VoicePhase.PREPARING, detail = "recognition_final_pending")) }
                    override fun onError(error: Int) = callback {
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) failed("recognizer_permission_denied", true)
                        else failed("recognizer_error_$error")
                    }
                    override fun onResults(results: Bundle?) = callback {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        // An empty final must not turn the last partial into a confirmable draft.
                        if (text.isBlank()) { failed("empty_recognition"); return@callback }
                        if (text.length > 100_000) { failed("recognition_too_long"); return@callback }
                        transcript.finish(text)
                        if (finish()) {
                            host.state(VoiceState(owner, VoicePhase.COMPLETE))
                            host.finalDraft(owner, transcript)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) = callback {
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return@callback
                        if (text.length > 100_000) { failed("recognition_too_long"); return@callback }
                        transcript.partialSnapshot(text); host.partial(owner, text)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }, language)
                host.state(VoiceState(owner, VoicePhase.PREPARING, detail = "dictation_not_duplex"))
            } catch (_: SecurityException) { failed("recognizer_permission_denied", true) }
            catch (_: Exception) { failed("recognizer_start_failed") }
        }
    }
    fun stop(owner: VoiceOwner): Boolean = gate.cancel(owner)
}
