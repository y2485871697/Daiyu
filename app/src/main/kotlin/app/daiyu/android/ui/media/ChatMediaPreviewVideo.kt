package app.daiyu.android.ui.media

import android.net.Uri
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Texture-backed original video rendering, scoped to the current page and the foreground lifecycle. */
@Composable
internal fun ChatMediaPreviewVideo(item: ChatMediaPreviewItem, active: Boolean, modifier: Modifier = Modifier,
    landscape: Boolean = false, onToggleOrientation: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val currentActive by rememberUpdatedState(active)
    val view = remember(context, item.key) { ChatMediaPreviewVideoView(context) }
    var controls by remember(item.key) { mutableStateOf(ChatMediaPreviewVideoControlsState()) }
    var loading by remember(item) { mutableStateOf(false) }
    var failed by remember(item) { mutableStateOf(false) }
    var prepared by remember(item.key) { mutableStateOf(false) }
    var videoSize by remember(item.key) { mutableStateOf(0 to 0) }
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val shouldStage = chatMediaPreviewVideoShouldPlay(active, resumed)

    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            // Pause synchronously on background/navigation, before cancellation releases the private file.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                view.pause()
                if (prepared) controls = controls.positionChanged(view.currentPosition)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (prepared) controls = controls.positionChanged(view.currentPosition)
            prepared = false
            view.setOnVideoSizeChangedListener(null)
            view.setOnPreparedListener(null)
            view.setOnErrorListener(null)
            view.setOnPlaybackInterruptedListener(null)
            view.stopPlayback()
        }
    }
    LaunchedEffect(item, view, shouldStage) {
        if (!shouldStage) {
            view.pause()
            controls = controls.hidden()
            return@LaunchedEffect
        }
        loading = true
        failed = false
        try {
            withChatMediaPreviewVideo(context, item) { file ->
                try {
                    view.setOnPlaybackInterruptedListener { controls = controls.copy(playRequested = false) }
                    view.setOnVideoSizeChangedListener { _, width, height -> videoSize = width to height }
                    view.setOnPreparedListener { player ->
                        videoSize = player.videoWidth to player.videoHeight
                        player.isLooping = true
                        controls = controls.prepared(player.duration)
                        prepared = true
                        loading = false
                        view.seekTo(controls.positionMillis)
                        if (controls.shouldPlay(
                                currentActive,
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                            )) {
                            view.start()
                        } else {
                            view.pause()
                        }
                    }
                    view.setOnErrorListener {
                        prepared = false
                        loading = false
                        failed = true
                        controls = controls.hidden()
                    }
                    view.setVideoURI(Uri.fromFile(file))
                    // Pausing or scrubbing must not restart staging or delete the playback file.
                    awaitCancellation()
                } finally {
                    if (prepared) controls = controls.positionChanged(view.currentPosition)
                    prepared = false
                    controls = controls.hidden()
                    loading = false
                    view.setOnPreparedListener(null)
                    view.setOnErrorListener(null)
                    view.setOnPlaybackInterruptedListener(null)
                    view.stopPlayback()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loading = false
            failed = true
        }
    }
    LaunchedEffect(view, prepared, shouldStage, controls.playRequested, controls.seeking) {
        if (!prepared || !shouldStage) return@LaunchedEffect
        if (!controls.shouldPlay(active, resumed)) {
            view.pause()
            return@LaunchedEffect
        }
        view.start()
        while (true) {
            delay(200)
            controls = controls.positionChanged(view.currentPosition)
        }
    }
    BoxWithConstraints(modifier) {
        val frame = chatMediaPreviewVideoFit(maxWidth.value, maxHeight.value, videoSize.first, videoSize.second)
        AndroidView(
            factory = { view },
            modifier = Modifier.align(Alignment.Center).size(frame.first.dp, frame.second.dp)
                .testTag("chat-media-video-renderer"),
            onReset = null,
            update = { if (!controls.shouldPlay(active, resumed)) it.pause() },
            onRelease = { it.stopPlayback() },
        )
        when {
            failed -> Text(
                text = "视频加载失败",
                color = Color.White,
                style = MiuixTheme.textStyles.body1,
                modifier = Modifier.align(Alignment.Center),
            )
            loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center), size = 28.dp, strokeWidth = 2.5.dp,
            )
        }
        ChatMediaPreviewVideoControls(
            state = controls,
            active = active,
            ready = prepared && !failed,
            landscape = landscape,
            onToggleOrientation = onToggleOrientation,
            onToggleVisibility = { controls = controls.toggleVisibility() },
            onTogglePlayback = {
                if (prepared && shouldStage) {
                    view.pause()
                    controls = controls.positionChanged(view.currentPosition).togglePlayback()
                }
            },
            onScrub = { fraction ->
                if (prepared && shouldStage) {
                    view.pause()
                    controls = controls.positionChanged(view.currentPosition).scrub(fraction)
                }
            },
            onSeekFinished = {
                if (prepared && shouldStage && controls.seeking) {
                    view.seekTo(controls.displayedPositionMillis)
                    controls = controls.finishSeeking()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
