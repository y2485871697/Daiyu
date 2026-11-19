package app.daiyu.android.ui.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView

/** TextureView participates in the preview's Compose rotation, unlike a separate SurfaceView. */
internal class ChatMediaPreviewVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var output: Surface? = null
    private var source: Uri? = null
    private var prepared = false
    private var seeking = false
    private var playRequested = false
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var errorListener: (() -> Unit)? = null
    private var interruptedListener: (() -> Unit)? = null
    private var completionListener: (() -> Unit)? = null
    private var seekCompleteListener: (() -> Unit)? = null
    private var firstFrameListener: (() -> Unit)? = null
    private val firstFrame = ChatMediaPreviewVideoFirstFrameState()
    private var sourceGeneration = 0L
    private var sizeListener: MediaPlayer.OnVideoSizeChangedListener? = null
    private val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change < 0) { pause(); interruptedListener?.invoke() }
        }.build()

    init { surfaceTextureListener = this }
    val currentPosition: Int get() = if (prepared) player?.currentPosition ?: 0 else 0
    fun setOnCompletionListener(listener: (() -> Unit)?) { completionListener = listener }
    fun setOnSeekCompleteListener(listener: (() -> Unit)?) { seekCompleteListener = listener }
    fun setOnFirstFrameListener(listener: (() -> Unit)?) { firstFrameListener = listener }
    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) { preparedListener = listener }
    fun setOnErrorListener(listener: (() -> Unit)?) { errorListener = listener }
    fun setOnPlaybackInterruptedListener(listener: (() -> Unit)?) { interruptedListener = listener }
    fun setOnVideoSizeChangedListener(listener: MediaPlayer.OnVideoSizeChangedListener?) { sizeListener = listener }
    fun setVideoURI(uri: Uri) {
        stopPlayback()
        source = uri
        surfaceTexture?.takeIf { isAvailable }?.let(::attach)
    }
    fun seekTo(position: Int) {
        val current = player ?: return
        if (!prepared || seeking) return
        seeking = true
        current.seekTo(position.coerceAtLeast(0).toLong(), MediaPlayer.SEEK_CLOSEST)
    }
    fun start() {
        playRequested = true
        val current = player
        if (prepared && output != null && current != null && !current.isPlaying) {
            if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) current.start()
            else { playRequested = false; interruptedListener?.invoke() }
        }
    }
    fun pause() {
        playRequested = false
        if (prepared) player?.takeIf { it.isPlaying }?.pause()
        audio.abandonAudioFocusRequest(focus)
    }
    fun stopPlayback() {
        playRequested = false
        source = null
        prepared = false
        seeking = false
        sourceGeneration++
        firstFrame.reset(sourceGeneration)
        val previous = player
        player = null
        previous?.release()
        output?.release()
        output = null
        audio.abandonAudioFocusRequest(focus)
    }
    private fun attach(texture: SurfaceTexture) {
        val uri = source ?: return
        try {
            output?.release()
            val surface = Surface(texture).also { output = it }
            val existing = player
            if (existing != null) {
                existing.setSurface(surface)
                if (playRequested) start()
                return
            }
            val generation = sourceGeneration
            val current = MediaPlayer().also { player = it }
            current.setAudioAttributes(attributes)
            current.setSurface(surface)
            current.setOnVideoSizeChangedListener { owner, width, height ->
                if (player === owner) sizeListener?.onVideoSizeChanged(owner, width, height)
            }
            current.setOnPreparedListener { owner ->
                if (player === owner) {
                    prepared = true
                    preparedListener?.onPrepared(owner)
                }
            }
            current.setOnInfoListener { owner, what, _ ->
                if (player === owner && generation == sourceGeneration &&
                    what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && firstFrame.renderingStarted(generation)) {
                    firstFrameListener?.invoke()
                }
                false
            }
            current.setOnCompletionListener { owner ->
                if (player === owner) {
                    playRequested = false
                    audio.abandonAudioFocusRequest(focus)
                    completionListener?.invoke()
                }
            }
            current.setOnSeekCompleteListener { owner ->
                if (player === owner && seeking) {
                    seeking = false
                    seekCompleteListener?.invoke()
                }
            }
            current.setOnErrorListener { owner, _, _ ->
                if (player === owner) { stopPlayback(); errorListener?.invoke() }
                true
            }
            current.setDataSource(context, uri)
            current.prepareAsync()
        } catch (_: Exception) {
            stopPlayback()
            errorListener?.invoke()
        }
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = attach(surface)
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (surface === surfaceTexture && prepared && player != null && output != null &&
            firstFrame.textureUpdated(sourceGeneration)) {
            firstFrameListener?.invoke()
        }
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (prepared) player?.takeIf { it.isPlaying }?.pause()
        player?.setSurface(null)
        output?.release()
        output = null
        audio.abandonAudioFocusRequest(focus)
        return true
    }
}

internal fun chatMediaPreviewVideoFit(width: Float, height: Float, videoWidth: Int, videoHeight: Int): Pair<Float, Float> {
    if (videoWidth <= 0 || videoHeight <= 0) return width to height
    val scale = minOf(width / videoWidth, height / videoHeight)
    return videoWidth * scale to videoHeight * scale
}
