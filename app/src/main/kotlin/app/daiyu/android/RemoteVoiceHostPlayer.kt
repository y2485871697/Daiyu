package app.daiyu.android

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import app.daiyu.android.voice.*
import app.daiyu.android.voice.network.VoiceNetworkAdapters
import app.daiyu.android.voice.providers.PersonalVoiceReference
import app.daiyu.android.voice.providers.SpeechSynthesisClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Existing VoiceHost owns the ONLY audio lease. This adapter never obtains a second gate. */
internal class RemoteVoiceHostPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtime: ControllerVoiceRuntime,
    private val frozen: FrozenVoiceSpeech,
) : VoiceHostPlayer {
    private val mutable = MutableStateFlow(SpeechPlaybackState())
    override val state = mutable.asStateFlow()
    private var owner: String? = null
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var closed = false
    override fun bindOwner(owner: String) { check(!closed); this.owner = owner }
    override fun play(owner: String, requestId: String, text: String): Boolean {
        if (closed || this.owner != owner || !runtime.current(frozen.configuration)) return false
        val config = frozen.configuration
        if (config.voice.startsWith("mimo-local-") && frozen.reference == null) return false
        releaseMedia()
        mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.QUEUED)
        job = scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    check(runtime.current(config))
                    val reference = frozen.reference?.let { value -> PersonalVoiceReference { id, provider ->
                        check(id == config.voice && provider == config.referenceOwner() && runtime.current(config))
                        value
                    } }
                    SpeechSynthesisClient(VoiceNetworkAdapters.http(), reference)
                        .synthesize(config.speech(), text, config.voice)
                }
                ensureActive()
                if (closed || mutable.value.requestId != requestId || !runtime.current(config)) return@launch
                // Network completion is not playback evidence. Prepare/play are actual Android events.
                file = File.createTempFile("voice-", ".audio", context.cacheDir).also { it.writeBytes(bytes) }
                val next = MediaPlayer()
                player = next
                next.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build())
                next.setOnPreparedListener {
                    if (!closed && player === it && runtime.current(config)) {
                        try {
                            it.start(); mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.SPEAKING)
                        } catch (_: Exception) {
                            mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.ERROR, SpeechError.PLAYBACK_FAILED)
                        }
                    } else closeStrict()
                }
                next.setOnCompletionListener {
                    if (!closed && player === it) mutable.value = SpeechPlaybackState(owner, requestId, SpeechPhase.COMPLETED)
                }
                next.setOnErrorListener { source, _, _ ->
                    if (!closed && player === source) mutable.value = SpeechPlaybackState(owner, requestId,
                        SpeechPhase.ERROR, SpeechError.PLAYBACK_FAILED)
                    true
                }
                next.setDataSource(checkNotNull(file).absolutePath)
                next.prepareAsync()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!closed && mutable.value.requestId == requestId) mutable.value =
                    SpeechPlaybackState(owner, requestId, SpeechPhase.ERROR, SpeechError.PLAYBACK_FAILED)
            }
        }
        return true
    }
    override fun stop(owner: String): Boolean {
        if (this.owner != owner) return false
        closeStrict(); return true
    }
    override fun closeStrict() {
        if (closed) return
        closed = true // Fence before cancellation, release and queued MediaPlayer callbacks.
        releaseMedia()
    }
    private fun releaseMedia() {
        job?.cancel(); job = null
        val p = player; player = null
        try { p?.reset() } finally {
            try { p?.release() } finally { file?.delete(); file = null }
        }
    }
    override fun close() = closeStrict()
}
