package app.daiyu.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import app.daiyu.android.voice.*
import app.daiyu.android.voice.network.VoiceNetworkAdapters
import app.daiyu.android.voice.providers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Validated against the prepared media track, never against estimated text speaking speed. */
internal object RemoteVoicePlaybackBudget {
    fun accepts(durationMs: Long, limitMs: Long): Boolean = durationMs > 0 && limitMs > 0 && durationMs <= limitMs
}

/** Existing VoiceHost owns the ONLY audio lease. All media commands/callbacks are main-confined. */
internal class RemoteVoiceHostPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtime: ControllerVoiceRuntime,
    private val frozen: FrozenVoiceSpeech,
    private val transport: VoiceHttpTransport = VoiceNetworkAdapters.http(),
) : VoiceHostPlayer {
    private val mutable = MutableStateFlow(SpeechPlaybackState())
    override val state = mutable.asStateFlow()
    private var owner: String? = null
    private var job: Job? = null
    private var deadline: Job? = null
    private var player: MediaPlayer? = null
    private var file: File? = null
    @Volatile private var closed = false
    private var releaseFailure: Throwable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val unobserve = runtime.observeInvalidation { profile ->
        if (profile == null || profile == frozen.profile) releaseOnMain(handler) { closeStrict() }
    }
    override fun bindOwner(owner: String) { check(!closed); this.owner = owner }
    override fun play(owner: String, requestId: String, text: String): Boolean {
        if (closed || releaseFailure != null || this.owner != owner || !runtime.current(frozen)) return false
        val config = frozen.configuration
        if (config.voice.startsWith("mimo-local-") && frozen.reference == null) return false
        releaseMedia()
        mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.QUEUED)
        job = scope.launch {
            try {
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
                        transport.execute(request, maxBytes)
                    }
                    val account = if (frozen.doubaoResolver != null) frozen.doubaoResolver.invoke() else frozen.doubaoPersonal
                    SpeechSynthesisClient(guarded, reference).synthesize(config.speech(), text, config.voice, account)
                }
                ensureActive()
                if (closed || mutable.value.requestId != requestId || !runtime.current(frozen)) return@launch
                // Native prepared track duration is the admission bound. Unknown/long tracks NEVER start.
                file = File.createTempFile("voice-", ".audio", context.cacheDir).also { it.writeBytes(bytes) }
                val next = MediaPlayer()
                player = next
                next.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build())
                next.isLooping = false
                next.setOnPreparedListener { source ->
                    if (closed || player !== source || !runtime.current(frozen)) {
                        if (player === source) finish(owner, requestId, false)
                    } else try {
                        check(RemoteVoicePlaybackBudget.accepts(source.duration.toLong(), cap)) { "voice_audio_duration_limit" }
                        source.start()
                        mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.SPEAKING)
                        // Secondary wall-clock cutoff as well as rejecting over-limit media durations.
                        deadline = scope.launch {
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
                next.prepareAsync()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!closed && mutable.value.requestId == requestId) finish(owner, requestId, false)
            }
        }
        return true
    }
    private fun finish(owner: String, requestId: String, success: Boolean) {
        // Fence callbacks before strict release. Release failures propagate to the existing poisoned gate.
        try { releaseMedia() } catch (_: Throwable) { /* Latched; closeStrict MUST report this to the gate. */ }
        mutable.value = if (success && releaseFailure == null) SpeechPlaybackState(owner, requestId, SpeechPhase.COMPLETED)
            else SpeechPlaybackState(owner, requestId, SpeechPhase.ERROR, SpeechError.PLAYBACK_FAILED)
    }
    override fun stop(owner: String): Boolean {
        if (this.owner != owner) return false
        closeStrict(); return true
    }
    override fun closeStrict() {
        if (!closed) {
            closed = true
            unobserve()
            releaseMedia()
        }
        releaseFailure?.let { throw it }
    }
    private fun releaseMedia() {
        val oldJob = job; job = null
        val oldDeadline = deadline; deadline = null
        val p = player; player = null
        val oldFile = file; file = null
        // No control lock is held across cancellation or native reset/release.
        oldJob?.cancel(); oldDeadline?.cancel()
        try {
            try { p?.reset() } finally {
                try { p?.release() } finally { check(oldFile == null || !oldFile.exists() || oldFile.delete()) { "voice_temp_delete_failed" } }
            }
        } catch (failure: Throwable) {
            releaseFailure = failure
            throw failure
        }
    }
    override fun close() = closeStrict()
}
