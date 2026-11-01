package app.daiyu.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import app.daiyu.android.voice.*
import app.daiyu.android.diagnostics.DiagnosticEvent
import app.daiyu.android.diagnostics.DiagnosticLogStore
import app.daiyu.android.voice.network.VoiceNetworkAdapters
import app.daiyu.android.voice.providers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Explicit automatic personal-voice reservations use the prepared track, not text estimates. */
internal object RemoteVoicePlaybackBudget {
    fun accepts(durationMs: Long, limitMs: Long): Boolean = durationMs > 0 && limitMs > 0 && durationMs <= limitMs
}

/** Existing VoiceHost owns the ONLY audio lease. All media commands/callbacks are main-confined. */
internal class RemoteVoiceHostPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtime: ControllerVoiceRuntime,
    private val frozen: FrozenVoiceSpeech,
    transport: VoiceHttpTransport? = null,
    private val diagnostics: DiagnosticLogStore? = null,
    private val pcmFactory: (AudioAttributes, () -> Unit) -> StreamingPcmPlayer = ::androidStreamingPcmPlayer,
) : VoiceHostPlayer {
    private val transport = transport ?: VoiceNetworkAdapters.speechHttp(frozen.configuration.speech().engine, diagnostics)
    private val mutable = MutableStateFlow(SpeechPlaybackState())
    override val state = mutable.asStateFlow()
    private var owner: String? = null
    private var job: Job? = null
    private var deadline: Job? = null
    private var pcm: StreamingPcmPlayer? = null
    private var player: MediaPlayer? = null
    private var focus: SpeechAudioFocus? = null
    private var releasing = false
    private var file: File? = null
    @Volatile private var closed = false
    private var releaseFailure: Throwable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val unobserve: () -> Unit = runtime.observeInvalidation { profile ->
        if (profile == null || profile == frozen.profile) releaseOnMain(handler) { closeStrict() }
    }
    override fun bindOwner(owner: String): Unit = releaseOnMain(handler) {
        check(!closed)
        if (this.owner != null && this.owner != owner) releaseMedia()
        this.owner = owner
    }
    override fun play(owner: String, requestId: String, text: String): Boolean {
        var accepted = false
        releaseOnMain(handler) { accepted = playOnMain(owner, requestId, text) }
        return accepted
    }
    private fun playOnMain(owner: String, requestId: String, text: String): Boolean {
        if (closed || !scope.isActive || releaseFailure != null || this.owner != owner || !runtime.current(frozen)) return false
        val config = frozen.configuration
        if (config.voice.startsWith("mimo-local-") && frozen.reference == null) return false
        releaseMedia()
        mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.QUEUED)
        diagnostics?.recordAsync(DiagnosticEvent.SPEECH_PLAYER_QUEUED)
        deadline = scope.launch(Dispatchers.Main.immediate) {
            delay(VoiceNetworkAdapters.speechTimeoutMillis(config.speech().engine))
            if (!closed && mutable.value.requestId == requestId && mutable.value.phase == SpeechPhase.QUEUED && player == null) {
                finish(owner, requestId, false, SpeechError.SYNTHESIS_TIMEOUT)
            }
        }
        val synthesisJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                if (config.speech().engine == SpeechEngine.DOUBAO && !DoubaoSpeech.usesCreate(config.model) &&
                    frozen.upload == null && transport is VoiceHttpStreamingTransport) {
                    streamPcm(owner, requestId, text, transport)
                    return@launch
                }
                var cap = frozen.playbackLimitMs
                val bytes = withContext(Dispatchers.IO) {
                    check(runtime.current(frozen))
                    val reference = frozen.reference?.let { value -> PersonalVoiceReference { id, provider ->
                        check(id == config.voice && provider == config.referenceOwner() && runtime.current(frozen))
                        value
                    } }
                    // This wrapper is the last application boundary before actual HTTP execute.
                    // Each execute consumes independently; failures/UNKNOWN never refund or retry.
                    val guarded = VoiceHttpTransport { request, maxBytes ->
                        ensureActive()
                        check(!closed && runtime.referenceStillCurrent(frozen))
                        cap = frozen.upload?.consume(requestId, text) ?: frozen.playbackLimitMs
                        check(cap in 1..frozen.playbackLimitMs)
                        check(!closed && runtime.current(frozen))
                        diagnostics?.recordAsync(DiagnosticEvent.SPEECH_HTTP_STARTED)
                        try {
                            transport.execute(request, maxBytes).also {
                                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_HTTP_COMPLETED)
                            }
                        } catch (cancelled: CancellationException) {
                            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_HTTP_CANCELLED)
                            throw cancelled
                        } catch (failure: Exception) {
                            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_HTTP_FAILED)
                            throw failure
                        }
                    }
                    val account = if (frozen.doubaoResolver != null) frozen.doubaoResolver.invoke() else frozen.doubaoPersonal
                    SpeechSynthesisClient(guarded, reference).synthesize(config.speech(), text, config.voice, account)
                }
                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_AUDIO_DECODED)
                ensureActive()
                if (closed || mutable.value.requestId != requestId) return@launch
                if (!runtime.current(frozen)) {
                    finish(owner, requestId, false)
                    return@launch
                }
                deadline?.cancel(); deadline = null
                // The original player starts manual read-aloud after native preparation.
                file = File.createTempFile("voice-", ".audio", context.cacheDir)
                checkNotNull(file).writeBytes(SpeechAudioFile.normalize(bytes))
                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_AUDIO_STORED)
                val next = MediaPlayer()
                player = next
                val attributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
                next.setAudioAttributes(attributes)
                next.setVolume(1f, 1f)
                next.isLooping = false
                next.setOnPreparedListener { source ->
                    if (closed || player !== source || !runtime.current(frozen)) {
                        if (player === source) finish(owner, requestId, false)
                    } else try {
                        diagnostics?.recordAsync(DiagnosticEvent.SPEECH_NATIVE_PREPARED)
                        deadline?.cancel(); deadline = null
                        // Manual read-aloud starts unknown/long tracks as in the original player.
                        // Only an explicit automatic personal-voice grant reserves audio duration.
                        if (frozen.upload != null) check(RemoteVoicePlaybackBudget.accepts(source.duration.toLong(), cap)) {
                            "voice_audio_duration_limit"
                        }
                        val lease = SpeechAudioFocus(context,
                            attributes, handler) {
                            if (!closed && player === source) finish(owner, requestId, false, SpeechError.AUDIO_FOCUS_LOST)
                        }
                        focus = lease
                        if (!lease.acquire()) {
                            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_FOCUS_DENIED)
                            finish(owner, requestId, false, SpeechError.AUDIO_FOCUS_DENIED)
                            return@setOnPreparedListener
                        }
                        diagnostics?.recordAsync(DiagnosticEvent.SPEECH_FOCUS_GRANTED)
                        if (closed || player !== source || !runtime.current(frozen)) {
                            if (player === source) finish(owner, requestId, false)
                            return@setOnPreparedListener
                        }
                        source.start()
                        diagnostics?.recordAsync(DiagnosticEvent.SPEECH_NATIVE_STARTED)
                        mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.SPEAKING)
                        // Secondary wall-clock cutoff as well as rejecting over-limit media durations.
                        if (closed || player !== source) return@setOnPreparedListener
                        deadline = scope.launch(Dispatchers.Main.immediate) {
                            delay(cap)
                            if (!closed && player === source) finish(owner, requestId, false)
                        }
                    } catch (_: Exception) { finish(owner, requestId, false) }
                }
                next.setOnCompletionListener { if (!closed && player === it) finish(owner, requestId, true) }
                next.setOnErrorListener { source, _, _ ->
                    if (!closed && player === source) finish(owner, requestId, false)
                    true
                }
                next.setDataSource(checkNotNull(file).absolutePath)
                deadline = scope.launch(Dispatchers.Main.immediate) {
                    delay(PREPARATION_TIMEOUT_MS)
                    if (!closed && player === next && mutable.value.phase == SpeechPhase.QUEUED) {
                        finish(owner, requestId, false, SpeechError.PREPARATION_TIMEOUT)
                    }
                }
                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_NATIVE_PREPARING)
                next.prepareAsync()
            } catch (cancelled: CancellationException) {
                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_PLAYER_CANCELLED)
                throw cancelled
            }
            catch (_: Exception) {
                if (!closed && mutable.value.requestId == requestId) finish(owner, requestId, false)
            }
        }
        job = synthesisJob
        synthesisJob.invokeOnCompletion { cause ->
            if (cause is CancellationException) releaseOnMain(handler) {
                if (!closed && job === synthesisJob && mutable.value.requestId == requestId &&
                    mutable.value.phase in setOf(SpeechPhase.QUEUED, SpeechPhase.SPEAKING)) finish(owner, requestId, false)
            }
        }
        return true
    }
    private suspend fun streamPcm(owner: String, requestId: String, text: String, stream: VoiceHttpStreamingTransport) {
        val attributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA).build()
        var output: StreamingPcmPlayer? = null
        var receivedAudio = false
        try {
            withContext(Dispatchers.IO) {
                val account = if (frozen.doubaoResolver != null) frozen.doubaoResolver.invoke() else frozen.doubaoPersonal
                DoubaoPcmSynthesis(stream).synthesize(frozen.configuration.speech(), text, frozen.configuration.voice, account,
                    beforeSend = {
                        check(!closed && runtime.referenceStillCurrent(frozen) && runtime.current(frozen))
                        diagnostics?.recordAsync(DiagnosticEvent.SPEECH_HTTP_STARTED)
                    }) { bytes ->
                    ensureActive()
                    check(!closed && runtime.current(frozen))
                    if (!receivedAudio) {
                        receivedAudio = true
                        diagnostics?.recordAsync(DiagnosticEvent.SPEECH_STREAM_AUDIO_RECEIVED)
                        withContext(Dispatchers.Main.immediate) {
                            check(!closed && mutable.value.requestId == requestId && runtime.current(frozen))
                            var leasedOutput: StreamingPcmPlayer? = null
                            val lease = SpeechAudioFocus(context, attributes, handler) {
                                if (!closed && leasedOutput != null && pcm === leasedOutput) {
                                    finish(owner, requestId, false, SpeechError.AUDIO_FOCUS_LOST)
                                }
                            }
                            focus = lease
                            if (!lease.acquire()) {
                                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_FOCUS_DENIED)
                                finish(owner, requestId, false, SpeechError.AUDIO_FOCUS_DENIED)
                                throw CancellationException("speech_focus_denied")
                            }
                            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_FOCUS_GRANTED)
                            lateinit var created: StreamingPcmPlayer
                            created = pcmFactory(attributes) {
                                handler.post {
                                    if (!closed && pcm === created && mutable.value.requestId == requestId && runtime.current(frozen)) {
                                        diagnostics?.recordAsync(DiagnosticEvent.SPEECH_STREAM_PLAYBACK_STARTED)
                                        mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.SPEAKING)
                                        if (closed || pcm !== created) return@post
                                        deadline?.cancel()
                                        deadline = scope.launch(Dispatchers.Main.immediate) {
                                            delay(frozen.playbackLimitMs)
                                            if (!closed && pcm === created) finish(owner, requestId, false)
                                        }
                                    }
                                }
                            }
                            leasedOutput = created
                            pcm = created
                            output = created
                        }
                    }
                    checkNotNull(output).write(bytes)
                }
                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_HTTP_COMPLETED)
                checkNotNull(output).drain()
                diagnostics?.recordAsync(DiagnosticEvent.SPEECH_STREAM_DRAINED)
            }
            ensureActive()
            if (!closed && pcm === output && mutable.value.requestId == requestId && runtime.current(frozen)) {
                finish(owner, requestId, true)
            }
        } catch (cancelled: CancellationException) {
            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_HTTP_CANCELLED)
            throw cancelled
        } catch (failure: Exception) {
            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_HTTP_FAILED)
            throw failure
        }
    }

    private fun finish(owner: String, requestId: String, success: Boolean,
        error: SpeechError = SpeechError.PLAYBACK_FAILED) {
        diagnostics?.recordAsync(if (success) DiagnosticEvent.SPEECH_PLAYER_COMPLETED else DiagnosticEvent.SPEECH_PLAYER_FAILED)
        // Fence callbacks before strict release. Release failures propagate to the existing poisoned gate.
        try { releaseMedia() } catch (_: Throwable) { /* Latched; closeStrict MUST report this to the gate. */ }
        mutable.value = if (success && releaseFailure == null) SpeechPlaybackState(owner, requestId, SpeechPhase.COMPLETED)
            else SpeechPlaybackState(owner, requestId, SpeechPhase.ERROR,
                if (releaseFailure == null) error else SpeechError.PLAYBACK_FAILED)
    }
    override fun stop(owner: String): Boolean {
        if (this.owner != owner) return false
        closeStrict(); return true
    }
    override fun closeStrict(): Unit = releaseOnMain(handler) {
        check(!releasing) { "voice_release_in_progress" }
        if (!closed) {
            closed = true
            unobserve()
            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_PLAYER_CLOSED)
            val previous = mutable.value
            try { releaseMedia() } finally {
                // Publish only after strict release: a collector can synchronously close us again.
                if (previous.phase == SpeechPhase.QUEUED || previous.phase == SpeechPhase.SPEAKING) {
                    mutable.value = previous.copy(
                        phase = if (releaseFailure == null) SpeechPhase.STOPPED else SpeechPhase.ERROR,
                        error = if (releaseFailure == null) null else SpeechError.STOP_FAILED,
                    )
                }
            }
        }
        releaseFailure?.let { throw it }
    }
    private fun releaseMedia() {
        check(!releasing) { "voice_release_in_progress" }
        val oldFocus = focus; focus = null
        val oldJob = job; job = null
        val oldDeadline = deadline; deadline = null
        val oldPcm = pcm; pcm = null
        val p = player; player = null
        val oldFile = file; file = null
        // No control lock is held across cancellation or native reset/release.
        releasing = true
        try {
            try {
                oldJob?.cancel(); oldDeadline?.cancel()
                try { oldPcm?.closeStrict() } finally {
                    try { p?.reset() } finally {
                        try { p?.release() } finally {
                            check(oldFile == null || !oldFile.exists() || oldFile.delete()) { "voice_temp_delete_failed" }
                        }
                    }
                }
            } finally { oldFocus?.closeStrict() }
            if (oldPcm != null || p != null || oldFile != null || oldFocus != null) diagnostics?.recordAsync(DiagnosticEvent.SPEECH_MEDIA_RELEASED)
        } catch (failure: Throwable) {
            diagnostics?.recordAsync(DiagnosticEvent.SPEECH_MEDIA_RELEASE_FAILED)
            releaseFailure = releaseFailure ?: failure
            throw failure
        } finally { releasing = false }
    }
    override fun close() = closeStrict()
    companion object { internal const val PREPARATION_TIMEOUT_MS = 120_000L }
}
