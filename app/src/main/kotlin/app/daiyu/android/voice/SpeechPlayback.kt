package app.daiyu.android.voice

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeechPhase { IDLE, INITIALIZING, READY, QUEUED, SPEAKING, COMPLETED, STOPPED, UNAVAILABLE, ERROR, CLOSED }

enum class SpeechError {
    ENGINE_UNAVAILABLE, INITIALIZATION_FAILED, LANGUAGE_UNSUPPORTED, LANGUAGE_DATA_MISSING,
    OFFLINE_VOICE_UNAVAILABLE, EMPTY_TEXT, TEXT_TOO_LONG, PLAYBACK_FAILED, STOP_FAILED
}

/** No message text, engine exception, credentials or audio is exposed in playback state. */
data class SpeechPlaybackState(
    val owner: String? = null,
    val requestId: String? = null,
    val phase: SpeechPhase = SpeechPhase.IDLE,
    val error: SpeechError? = null,
    val platformErrorCode: Int? = null,
)

internal interface SpeechBackend {
    val maxInputLength: Int
    fun initialize(onReady: (SpeechError?) -> Unit, onEvent: (String, SpeechEvent, Int?) -> Unit)
    fun configureOfflineLanguage(locale: Locale): SpeechError?
    fun speak(text: String, utteranceId: String): Boolean
    fun stop(): Boolean
    fun shutdown()
}

internal enum class SpeechEvent { START, DONE, STOP, ERROR }

/**
 * Single-threaded controller; only backend callbacks cross threads, through [dispatch].
 * StateFlow collectors can still call us synchronously from a publication. Every continuation
 * that can touch an engine must revalidate its command and engine after such a boundary.
 */
internal class SpeechPlaybackController(
    private val backendFactory: () -> SpeechBackend,
    private val dispatch: (() -> Unit) -> Unit,
) {
    private data class Request(val id: String, val token: String, val text: String, val locale: Locale)
    private val mutableState = MutableStateFlow(SpeechPlaybackState())
    val state: StateFlow<SpeechPlaybackState> = mutableState.asStateFlow()
    private var owner: String? = null
    private var backend: SpeechBackend? = null
    private var ready = false
    private var closed = false
    private var engineGeneration = 0L
    private var requestGeneration = 0L
    private var commandGeneration = 0L
    private var pending: Request? = null
    private var activeToken: String? = null

    fun bindOwner(newOwner: String) {
        require(newOwner.isNotBlank())
        if (closed || owner == newOwner) return
        invalidateRequest()
        val command = commandGeneration
        owner = newOwner
        releaseBackend()
        if (isCurrentCommand(command)) publish(SpeechPhase.IDLE)
    }

    /** Prewarming is optional and never queues speech. Must follow bindOwner. */
    fun initialize(expectedOwner: String): Boolean {
        if (!owns(expectedOwner)) return false
        if (backend == null) createBackend()
        return true
    }

    /** Acceptance is not completion: observe state for asynchronous initialization/playback errors. */
    fun play(expectedOwner: String, requestId: String, text: String, locale: Locale): Boolean {
        if (!owns(expectedOwner)) return false
        require(requestId.isNotBlank())
        invalidateRequest()
        val command = commandGeneration
        if (!stopReadyBackend(requestId, command) || !isCurrentCommand(command)) return false
        if (text.isBlank()) {
            fail(SpeechError.EMPTY_TEXT, requestId)
            return false
        }
        pending = Request(requestId, "daiyu-tts-${++requestGeneration}", text, locale)
        if (backend == null) createBackend()
        else if (ready) startPending()
        else publish(SpeechPhase.INITIALIZING, requestId)
        return true
    }

    /** Wrong-owner calls cannot stop the currently visible conversation. */
    fun stop(expectedOwner: String): Boolean {
        if (!owns(expectedOwner)) return false
        val requestId = state.value.requestId
        invalidateRequest() // Fence synchronous or late stop/done callbacks before touching the engine.
        val command = commandGeneration
        if (!ready) releaseBackend()
        else if (!stopReadyBackend(requestId, command)) return false
        if (!isCurrentCommand(command)) return false
        publish(SpeechPhase.STOPPED, requestId)
        return true
    }

    fun close() {
        if (closed) return
        closed = true
        invalidateRequest()
        releaseBackend()
        publish(SpeechPhase.CLOSED)
    }

    private fun owns(expectedOwner: String) = !closed && owner != null && owner == expectedOwner
    private fun isCurrentCommand(command: Long) = !closed && command == commandGeneration
    private fun isCurrentEngine(instance: SpeechBackend, generation: Long) =
        !closed && generation == engineGeneration && backend === instance

    private fun createBackend() {
        val generation = ++engineGeneration
        val command = commandGeneration
        fun creationIsCurrent() = isCurrentCommand(command) && generation == engineGeneration
        publish(SpeechPhase.INITIALIZING, pending?.id)
        // In particular, stop/close/bindOwner from INITIALIZING must not create an engine.
        if (!creationIsCurrent()) return
        val instance = try { backendFactory() } catch (_: Exception) {
            if (creationIsCurrent()) {
                val id = pending?.id
                pending = null
                fail(SpeechError.ENGINE_UNAVAILABLE, id)
            }
            return
        }
        // A factory can reenter too. An engine not yet assigned is still our resource to release.
        if (!creationIsCurrent()) {
            disposeBackend(instance)
            return
        }
        backend = instance
        var awaitingInitialization = true
        try {
            instance.initialize(
                onReady = { error -> dispatch {
                    if (awaitingInitialization && isCurrentEngine(instance, generation)) {
                        awaitingInitialization = false
                        if (error != null) {
                            failInitialization(error)
                        } else {
                            ready = true
                            if (pending != null) startPending() else publish(SpeechPhase.READY)
                        }
                    }
                } },
                onEvent = { token, event, code -> dispatch {
                    if (isCurrentEngine(instance, generation) && token == activeToken) {
                        when (event) {
                            SpeechEvent.START -> publish(SpeechPhase.SPEAKING, state.value.requestId)
                            SpeechEvent.DONE -> finish(SpeechPhase.COMPLETED)
                            SpeechEvent.STOP -> finish(SpeechPhase.STOPPED)
                            SpeechEvent.ERROR -> {
                                activeToken = null
                                fail(SpeechError.PLAYBACK_FAILED, state.value.requestId, code)
                            }
                        }
                    }
                } },
            )
        } catch (_: Exception) {
            // A synchronous ready/progress callback may already have closed or replaced us.
            if (isCurrentCommand(command) && isCurrentEngine(instance, generation)) {
                failInitialization(SpeechError.INITIALIZATION_FAILED)
            }
        }
    }

    private fun failInitialization(error: SpeechError) {
        val id = pending?.id
        invalidateRequest()
        val command = commandGeneration
        releaseBackend()
        if (isCurrentCommand(command)) fail(error, id)
    }

    private fun startPending() {
        val request = pending ?: return
        val engine = backend ?: return
        val generation = engineGeneration
        val command = commandGeneration
        fun requestIsCurrent() = isCurrentCommand(command) && isCurrentEngine(engine, generation) && ready
        pending = null
        val maxLength = engine.maxInputLength
        if (!requestIsCurrent()) return
        if (request.text.length > maxLength) {
            fail(SpeechError.TEXT_TOO_LONG, request.id)
            return
        }
        val languageError = try { engine.configureOfflineLanguage(request.locale) }
            catch (_: Exception) { SpeechError.OFFLINE_VOICE_UNAVAILABLE }
        if (!requestIsCurrent()) return
        if (languageError != null) {
            fail(languageError, request.id)
            return
        }
        activeToken = request.token
        publish(SpeechPhase.QUEUED, request.id)
        if (!requestIsCurrent() || activeToken != request.token) return
        val accepted = try { engine.speak(request.text, request.token) } catch (_: Exception) { false }
        // A synchronous START can publish SPEAKING and cause stop/close/owner replacement.
        // Neither a false return nor an exception may overwrite that newer state.
        if (!requestIsCurrent() || activeToken != request.token) return
        if (!accepted) {
            activeToken = null
            fail(SpeechError.PLAYBACK_FAILED, request.id)
        }
    }

    private fun stopReadyBackend(requestId: String?, command: Long): Boolean {
        if (!ready) return true
        val engine = backend ?: return false
        val generation = engineGeneration
        val stopped = try { engine.stop() } catch (_: Exception) { false }
        if (!isCurrentCommand(command) || !isCurrentEngine(engine, generation)) return false
        if (!stopped) {
            releaseBackend()
            if (isCurrentCommand(command)) fail(SpeechError.STOP_FAILED, requestId)
        }
        return stopped
    }

    private fun finish(phase: SpeechPhase) {
        activeToken = null
        publish(phase, state.value.requestId)
    }

    private fun invalidateRequest() {
        ++commandGeneration
        activeToken = null
        pending = null
    }

    private fun releaseBackend() {
        ++engineGeneration
        val old = backend
        backend = null
        ready = false
        old?.let(::disposeBackend)
    }

    private fun disposeBackend(instance: SpeechBackend) {
        try { instance.stop() } catch (_: Exception) { /* shutdown still required */ }
        try { instance.shutdown() } catch (_: Exception) { /* never expose engine exception text */ }
    }

    private fun fail(error: SpeechError, requestId: String?, code: Int? = null) {
        val unavailable = error in setOf(
            SpeechError.ENGINE_UNAVAILABLE, SpeechError.INITIALIZATION_FAILED,
            SpeechError.LANGUAGE_UNSUPPORTED, SpeechError.LANGUAGE_DATA_MISSING,
            SpeechError.OFFLINE_VOICE_UNAVAILABLE,
        )
        mutableState.value = SpeechPlaybackState(owner, requestId,
            if (unavailable) SpeechPhase.UNAVAILABLE else SpeechPhase.ERROR, error, code)
    }

    private fun publish(phase: SpeechPhase, requestId: String? = null) {
        mutableState.value = SpeechPlaybackState(owner, requestId, phase)
    }
}
