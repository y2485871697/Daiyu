package app.daiyu.android.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text

internal data class ChatMediaPreviewVideoControlsState(
    val visible: Boolean = false,
    val playRequested: Boolean = true,
    val durationMillis: Int = 0,
    val positionMillis: Int = 0,
    val seekPositionMillis: Int? = null,
    val seekPending: Boolean = false,
) {
    val seeking: Boolean get() = seekPositionMillis != null
    val displayedPositionMillis: Int get() = (seekPositionMillis ?: positionMillis).coerceIn(0, durationMillis)
    val fraction: Float get() = if (durationMillis > 0) displayedPositionMillis.toFloat() / durationMillis else 0f

    fun toggleVisibility() = copy(visible = !visible)
    fun togglePlayback() = if (seekPending) this else if (!playRequested && durationMillis > 0 && positionMillis >= durationMillis)
        copy(playRequested = true, positionMillis = 0, seekPending = true)
    else copy(playRequested = !playRequested)
    fun completed() = if (seeking || seekPending) this else copy(playRequested = false, positionMillis = durationMillis,
        seekPositionMillis = null, seekPending = false)
    fun prepared(duration: Int) = copy(
        durationMillis = duration.coerceAtLeast(0),
        positionMillis = positionMillis.coerceIn(0, duration.coerceAtLeast(0)),
        seekPositionMillis = null,
        seekPending = false,
    )
    fun positionChanged(position: Int) = if (seekPending || (!playRequested && positionMillis == durationMillis)) this
        else copy(positionMillis = position.coerceIn(0, durationMillis))
    fun seekCompleted() = copy(seekPending = false)
    fun scrub(fraction: Float) = if (durationMillis > 0 && !seekPending) copy(
        seekPositionMillis = (durationMillis.toDouble() * fraction.coerceIn(0f, 1f)).roundToInt(),
    ) else this
    fun finishSeeking() = if (!seeking || seekPending) this else copy(
        playRequested = playRequested && displayedPositionMillis < durationMillis,
        positionMillis = displayedPositionMillis, seekPositionMillis = null, seekPending = true)
    fun hidden() = copy(visible = false, seekPositionMillis = null)
    fun shouldPlay(active: Boolean, resumed: Boolean) =
        chatMediaPreviewVideoShouldPlay(active, resumed) && playRequested && !seeking && !seekPending
}

internal fun chatMediaPreviewVideoTime(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    val remainder = (seconds % 60).toString().padStart(2, '0')
    return if (minutes < 60) "$minutes:$remainder"
    else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$remainder"
}

@Composable
internal fun ChatMediaPreviewVideoControls(
    state: ChatMediaPreviewVideoControlsState,
    active: Boolean,
    ready: Boolean,
    onToggleVisibility: () -> Unit,
    onTogglePlayback: () -> Unit,
    onScrub: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    onToggleOrientation: () -> Unit = {},
    insets: WindowInsets = WindowInsets.safeDrawing,
) {
    val controlsEnabled = active && state.visible
    val playbackEnabled = controlsEnabled && ready && !state.seekPending
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val physical = insets
    val safe = chatMediaPreviewVideoInsets(physical.getLeft(density, direction), physical.getTop(density),
        physical.getRight(density, direction), physical.getBottom(density), landscape)
    val horizontalInset = maxOf(safe[0], safe[2])
    val controlInsets = WindowInsets(left = horizontalInset, top = safe[1], right = horizontalInset, bottom = safe[3])
    Box(modifier) {
        // This sibling is below the controls: button and slider gestures cannot toggle the surface.
        Box(
            Modifier.fillMaxSize().testTag("chat-media-video-surface")
                .semantics { contentDescription = "视频画面" }
                .clickable(
                    enabled = active,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = if (state.visible) "隐藏播放控件" else "显示播放控件",
                    onClick = onToggleVisibility,
                ),
        )
        AnimatedVisibility(
            visible = controlsEnabled,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(controlInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = 20.dp).padding(bottom = if (landscape) 8.dp else 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("chat-media-video-controls")
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    enabled = playbackEnabled,
                    onClick = onTogglePlayback,
                    modifier = Modifier.size(48.dp).testTag("chat-media-video-play-pause")
                        .semantics { if (!playbackEnabled) disabled() },
                ) {
                    Icon(
                        imageVector = if (state.playRequested) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playRequested) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                ChatMediaPreviewVideoProgress(
                    value = state.fraction,
                    playing = state.playRequested,
                    seeking = state.seeking,
                    onValueChange = onScrub,
                    onValueChangeFinished = onSeekFinished,
                    enabled = playbackEnabled && state.durationMillis > 0,
                    modifier = Modifier.weight(1f).testTag("chat-media-video-progress")
                        .semantics { contentDescription = "视频进度" },
                )
                Text(
                    text = "${chatMediaPreviewVideoTime(state.displayedPositionMillis)} / ${chatMediaPreviewVideoTime(state.durationMillis)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp).testTag("chat-media-video-time"),
                )
                IconButton(
                    enabled = controlsEnabled,
                    onClick = onToggleOrientation,
                    modifier = Modifier.size(48.dp).testTag("chat-media-video-orientation")
                        .semantics { if (!controlsEnabled) disabled() },
                ) {
                    Icon(Icons.Rounded.ScreenRotation,
                        contentDescription = if (landscape) "切换竖屏" else "切换横屏",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Clockwise preview rotation maps the physical bottom gesture inset to logical right. */
internal fun chatMediaPreviewVideoInsets(left: Int, top: Int, right: Int, bottom: Int, landscape: Boolean): List<Int> =
    if (landscape) listOf(top, right, bottom, left) else listOf(left, top, right, bottom)
