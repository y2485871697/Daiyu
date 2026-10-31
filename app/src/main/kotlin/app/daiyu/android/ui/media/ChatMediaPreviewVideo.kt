package app.daiyu.android.ui.media

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Original VideoView rendering, scoped to the current page and the foreground lifecycle. */
@Composable
internal fun ChatMediaPreviewVideo(item: ChatMediaPreviewItem, active: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val currentActive by rememberUpdatedState(active)
    val view = remember(context, item.key) { VideoView(context) }
    var loading by remember(item) { mutableStateOf(false) }
    var failed by remember(item) { mutableStateOf(false) }
    val shouldPlay = chatMediaPreviewVideoShouldPlay(active, lifecycleState.isAtLeast(Lifecycle.State.RESUMED))

    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            // Pause synchronously on background/navigation, before a recomposition can cancel staging.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.setOnPreparedListener(null)
            view.setOnErrorListener(null)
            view.stopPlayback()
        }
    }
    LaunchedEffect(item, view, shouldPlay) {
        if (!shouldPlay) {
            view.pause()
            return@LaunchedEffect
        }
        loading = true
        failed = false
        try {
            withChatMediaPreviewVideo(context, item) { file ->
                try {
                    view.setOnPreparedListener { player ->
                        player.isLooping = true
                        loading = false
                        if (chatMediaPreviewVideoShouldPlay(
                                currentActive,
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                            )) {
                            view.start()
                        } else {
                            view.pause()
                        }
                    }
                    view.setOnErrorListener { _, _, _ ->
                        loading = false
                        failed = true
                        true
                    }
                    view.setVideoURI(Uri.fromFile(file))
                    // The private file belongs to this suspended playback lifetime, not just preparation.
                    awaitCancellation()
                } finally {
                    view.setOnPreparedListener(null)
                    view.setOnErrorListener(null)
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
    Box(modifier) {
        AndroidView(
            factory = { view },
            modifier = Modifier.fillMaxSize(),
            onReset = null,
            update = { if (!shouldPlay) it.pause() },
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
    }
}
