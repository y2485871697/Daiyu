package app.daiyu.android.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * Explicit, local-only Android TTS. Construct and call on the main thread. No automatic reading,
 * microphone access, audio files, downloads, network clients, or provider credentials.
 * The application context (not the Activity) is retained until close.
 */
class AndroidSpeechPlayer internal constructor(
    private val controller: SpeechPlaybackController,
    private val checkMainThread: () -> Unit,
) : AutoCloseable {
    constructor(context: Context) : this(androidSpeechController(context), {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Speech player must be called on main thread" }
    })

    val state: StateFlow<SpeechPlaybackState> = controller.state

    /** A different owner immediately stops/releases the old engine and invalidates old callbacks. */
    fun bindOwner(owner: String) = onMain { controller.bindOwner(owner) }

    /** Optional; play also initializes asynchronously and retains only the latest pending request. */
    fun initialize(owner: String): Boolean = onMain { controller.initialize(owner) }

    /** requestId identifies a message/preview for UI only; it is never sent to the TTS engine. */
    fun play(owner: String, requestId: String, text: String, locale: Locale = Locale.getDefault()): Boolean =
        onMain { controller.play(owner, requestId, text, locale) }

    fun stop(owner: String): Boolean = onMain { controller.stop(owner) }

    /** Terminal, idempotent best-effort cleanup for lifecycle callers; not proof of audio release. */
    override fun close() = onMain { controller.close() }

    /** Production ownership handoff must fail closed if stop/shutdown could not confirm release. */
    internal fun closeStrict() = onMain { controller.closeStrict() }

    private inline fun <T> onMain(block: () -> T): T {
        checkMainThread()
        return block()
    }
}

private fun androidSpeechController(context: Context): SpeechPlaybackController {
    val applicationContext = context.applicationContext
    val handler = Handler(Looper.getMainLooper())
    return SpeechPlaybackController(
        backendFactory = { AndroidSpeechBackend(applicationContext, handler) },
        dispatch = { callback -> handler.post { callback() }; Unit },
    )
}

private class AndroidSpeechBackend(
    private val context: Context,
    private val handler: Handler,
) : SpeechBackend {
    private var tts: TextToSpeech? = null
    private var closed = false
    override val maxInputLength: Int get() = TextToSpeech.getMaxSpeechInputLength()

    override fun initialize(onReady: (SpeechError?) -> Unit, onEvent: (String, SpeechEvent, Int?) -> Unit) {
        check(tts == null && !closed)
        // Posting even a synchronous onInit ensures the constructor has assigned tts first.
        tts = TextToSpeech(context) { status -> handler.post {
            if (!closed) {
                val failure = try {
                    val engine = tts
                    if (status != TextToSpeech.SUCCESS || engine == null) {
                        if (engine?.defaultEngine.isNullOrBlank()) SpeechError.ENGINE_UNAVAILABLE
                        else SpeechError.INITIALIZATION_FAILED
                    } else {
                        val result = engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                utteranceId?.let { onEvent(it, SpeechEvent.START, null) }
                            }
                            override fun onDone(utteranceId: String?) {
                                utteranceId?.let { onEvent(it, SpeechEvent.DONE, null) }
                            }
                            @Deprecated("Required Android callback")
                            override fun onError(utteranceId: String?) {
                                utteranceId?.let { onEvent(it, SpeechEvent.ERROR, null) }
                            }
                            override fun onError(utteranceId: String?, errorCode: Int) {
                                utteranceId?.let { onEvent(it, SpeechEvent.ERROR, errorCode) }
                            }
                            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                utteranceId?.let { onEvent(it, SpeechEvent.STOP, null) }
                            }
                        })
                        if (result == TextToSpeech.SUCCESS) null else SpeechError.INITIALIZATION_FAILED
                    }
                } catch (_: Exception) {
                    SpeechError.INITIALIZATION_FAILED
                }
                onReady(failure)
            }
        } }
    }

    override fun configureOfflineLanguage(locale: Locale): SpeechError? {
        val engine = tts ?: return SpeechError.ENGINE_UNAVAILABLE
        when (engine.isLanguageAvailable(locale)) {
            TextToSpeech.LANG_MISSING_DATA -> return SpeechError.LANGUAGE_DATA_MISSING
            TextToSpeech.LANG_NOT_SUPPORTED -> return SpeechError.LANGUAGE_UNSUPPORTED
        }
        // Do not use setLanguage's default voice: that voice may require a network connection.
        val selected = engine.voices.orEmpty().asSequence()
            .filter { voice ->
                voice.locale.language == locale.language && !voice.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty() &&
                    TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS !in voice.features.orEmpty()
            }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == locale }
                .thenByDescending { it.locale.country == locale.country }
                .thenByDescending { it.locale.script == locale.script }
                .thenBy { it.name })
            .firstOrNull() ?: return SpeechError.OFFLINE_VOICE_UNAVAILABLE
        if (engine.setVoice(selected) != TextToSpeech.SUCCESS) return SpeechError.OFFLINE_VOICE_UNAVAILABLE
        val actual = engine.voice ?: return SpeechError.OFFLINE_VOICE_UNAVAILABLE
        return if (actual.name == selected.name && !actual.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in actual.features.orEmpty() &&
            TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS !in actual.features.orEmpty()) null
        else SpeechError.OFFLINE_VOICE_UNAVAILABLE
    }

    override fun speak(text: String, utteranceId: String): Boolean {
        val parameters = Bundle().apply {
            // Defense in depth for engines honoring Android's embedded-only synthesis hint.
            putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
        }
        return tts?.speak(text, TextToSpeech.QUEUE_FLUSH, parameters, utteranceId) == TextToSpeech.SUCCESS
    }

    override fun stop(): Boolean = tts?.stop() == TextToSpeech.SUCCESS

    override fun shutdown() {
        if (closed) return
        closed = true
        val engine = tts
        tts = null
        engine?.shutdown()
    }
}
