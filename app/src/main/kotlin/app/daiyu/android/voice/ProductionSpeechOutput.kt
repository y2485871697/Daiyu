package app.daiyu.android.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * A test seam over the existing, offline-only player. All methods run on main.
 * close MUST confirm release or throw: a best-effort lifecycle close is not sufficient here.
 */
internal interface ProductionSpeechPlayer : AutoCloseable {
    val state: StateFlow<SpeechPlaybackState>
    fun bindOwner(owner: String)
    fun play(owner: String, requestId: String, text: String): Boolean
}

// Evaluate applicationContext before forming the retained factory; never capture an Activity.
private fun localPlayerFactory(context: Context): () -> ProductionSpeechPlayer {
    val app = context.applicationContext
    return { LocalProductionSpeechPlayer(app) }
}

internal class LocalProductionSpeechPlayer(private val player: AndroidSpeechPlayer) : ProductionSpeechPlayer {
    constructor(context: Context) : this(AndroidSpeechPlayer(context))
    override val state get() = player.state
    override fun bindOwner(owner: String) = player.bindOwner(owner)
    override fun play(owner: String, requestId: String, text: String) = player.play(owner, requestId, text)
    override fun close() = player.closeStrict()
}

/**
 * One immutable VoiceOwner triple per output; use ProductionVoiceSession for owner-checked input.
 * Never concatenate owner fields into a player key: each instance gets an opaque, unique binding.
 * Acceptance/INITIALIZING/QUEUED are NOT start evidence. Only observed SPEAKING is started().
 * StateFlow can conflate a very short SPEAKING transition: absence of evidence is not invented.
 * Commands may originate on a VoiceChain worker. Main never invokes chain callbacks synchronously.
 */
class ProductionSpeechOutput internal constructor(
    val owner: VoiceOwner,
    private val playerFactory: () -> ProductionSpeechPlayer,
    private val scope: CoroutineScope,
    private val postMain: (() -> Unit) -> Unit,
    private val releaseMain: (() -> Unit) -> Unit,
    private val dispatchEvent: (() -> Unit) -> Unit,
) : SpeechOutput {
    constructor(context: Context, owner: VoiceOwner) : this(
        owner,
        localPlayerFactory(context),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        { action -> check(Handler(Looper.getMainLooper()).post { action() }) { "speech_main_rejected" } },
        { action -> releaseOnMain(Handler(Looper.getMainLooper()), action) },
        ::voiceEvent,
    )

    private class Request(val id: String, val events: SpeechOutput.Events) {
        var started = false // main-confined
        var terminal = false // main-confined
    }
    private val lock = Any()
    private val playerOwner = UUID.randomUUID().toString()
    private var active: Request? = null
    private var closed = false
    private var releasing = false
    private var releaseFailed = false
    // Main-confined resources. stop destroys the player as well as stopping it.
    private var player: ProductionSpeechPlayer? = null
    private var collector: Job? = null
    override val startedEvidence = PlaybackEvidence.TTS_ON_START

    private fun current(request: Request) = synchronized(lock) { !closed && active === request }

    override fun speak(text: String, events: SpeechOutput.Events) {
        val request = synchronized(lock) {
            check(!closed) { "speech_output_closed" }
            check(!releasing && !releaseFailed) { "audio_release_pending" }
            Request(UUID.randomUUID().toString(), events).also { active = it }
        }
        try {
            postMain {
                if (!current(request)) return@postMain
                try {
                    var instance = player
                    if (instance == null) {
                        instance = playerFactory()
                        player = instance
                        instance.bindOwner(playerOwner)
                        val observed = instance
                        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            observed.state.collect { accept(observed, it) }
                        }
                    }
                    if (!current(request)) return@postMain
                    // play's Boolean is only admission; never map it to started().
                    val accepted = instance.play(playerOwner, request.id, text)
                    if (!accepted && current(request) && !request.terminal) {
                        terminal(request, "speech_play_rejected")
                    }
                } catch (_: Exception) {
                    if (current(request) && !request.terminal) terminal(request, "speech_start_failed")
                }
            }
        } catch (failure: Exception) {
            synchronized(lock) { if (active === request) active = null }
            throw failure
        }
    }

    private fun accept(source: ProductionSpeechPlayer, state: SpeechPlaybackState) {
        val request = synchronized(lock) { active } ?: return
        if (player !== source || !current(request) || request.terminal ||
            state.owner != playerOwner || state.requestId != request.id) return
        when (state.phase) {
            SpeechPhase.SPEAKING -> if (!request.started) {
                request.started = true
                dispatchEvent { if (current(request)) request.events.started() }
            }
            SpeechPhase.COMPLETED -> terminal(request, null)
            SpeechPhase.ERROR, SpeechPhase.UNAVAILABLE -> terminal(request,
                "speech_${state.error?.name?.lowercase(java.util.Locale.ROOT) ?: "unavailable"}")
            SpeechPhase.STOPPED, SpeechPhase.CLOSED -> terminal(request, "speech_playback_stopped")
            else -> Unit
        }
    }

    private fun terminal(request: Request, failure: String?) {
        if (request.terminal) return
        request.terminal = true
        dispatchEvent {
            if (current(request)) {
                if (failure == null) request.events.completed() else request.events.failed(failure)
            }
        }
    }

    override fun stop() = release(false)
    override fun close() = release(true)

    private fun release(permanent: Boolean) {
        synchronized(lock) {
            if (closed) return
            check(!releasing && !releaseFailed) { "audio_release_pending" }
            if (permanent) closed = true
            active = null // Fence queued commands/events BEFORE waiting for main teardown.
            releasing = true
        }
        try {
            releaseMain {
                collector?.cancel(); collector = null
                val old = player; player = null
                old?.close() // Strict close: unconfirmed release must reach the gate as a failure.
            }
        } catch (failure: Throwable) {
            synchronized(lock) { releaseFailed = true }
            throw failure
        } finally {
            synchronized(lock) { releasing = false }
            if (permanent) scope.cancel()
        }
    }
}

/**
 * Single-use production chain: caller cannot rebind its output to a different conversation/run/session.
 * Share the same gate with draft dictation AND the parent's existing manual read-aloud adapter.
 */
class ProductionVoiceSession internal constructor(
    val owner: VoiceOwner,
    gate: AudioOwnerGate,
    output: ProductionSpeechOutput,
    publish: (VoiceState) -> Unit,
) : AutoCloseable {
    constructor(context: Context, owner: VoiceOwner, gate: AudioOwnerGate, publish: (VoiceState) -> Unit) :
        this(owner, gate, ProductionSpeechOutput(context, owner), publish)

    init { require(output.owner == owner) { "speech_owner_mismatch" } }
    private val chain = VoiceChain(gate, output, publish)
    private val begun = java.util.concurrent.atomic.AtomicBoolean()
    fun begin(admission: () -> Boolean = { true }) {
        check(begun.compareAndSet(false, true)) { "voice_session_already_begun" }
        chain.begin(owner, admission)
    }
    fun delta(expectedOwner: VoiceOwner, text: String) {
        if (expectedOwner == owner) chain.delta(owner, text)
    }
    fun done(expectedOwner: VoiceOwner, snapshot: String? = null) {
        if (expectedOwner == owner) chain.done(owner, snapshot)
    }
    fun stop(expectedOwner: VoiceOwner): Boolean = expectedOwner == owner && chain.stop(owner)
    override fun close() = chain.close()
}
