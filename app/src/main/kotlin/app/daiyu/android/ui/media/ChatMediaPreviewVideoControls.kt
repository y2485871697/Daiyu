package app.daiyu.android.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.haptics.material.Slider
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
) {
    val seeking: Boolean get() = seekPositionMillis != null
    val displayedPositionMillis: Int get() = (seekPositionMillis ?: positionMillis).coerceIn(0, durationMillis)
    val fraction: Float get() = if (durationMillis > 0) displayedPositionMillis.toFloat() / durationMillis else 0f

    fun toggleVisibility() = copy(visible = !visible)
    fun togglePlayback() = copy(playRequested = !playRequested)
    fun prepared(duration: Int) = copy(
        durationMillis = duration.coerceAtLeast(0),
        positionMillis = positionMillis.coerceIn(0, duration.coerceAtLeast(0)),
        seekPositionMillis = null,
    )
    fun positionChanged(position: Int) = copy(positionMillis = position.coerceIn(0, durationMillis))
    fun scrub(fraction: Float) = if (durationMillis > 0) copy(
        seekPositionMillis = (durationMillis.toDouble() * fraction.coerceIn(0f, 1f)).roundToInt(),
    ) else this
    fun finishSeeking() = copy(positionMillis = displayedPositionMillis, seekPositionMillis = null)
    fun hidden() = copy(visible = false, seekPositionMillis = null)
    fun shouldPlay(active: Boolean, resumed: Boolean) =
        chatMediaPreviewVideoShouldPlay(active, resumed) && playRequested && !seeking
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
) {
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
            visible = active && state.visible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("chat-media-video-controls")
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    enabled = active && state.visible && ready,
                    onClick = onTogglePlayback,
                    modifier = Modifier.size(48.dp).testTag("chat-media-video-play-pause"),
                ) {
                    Icon(
                        imageVector = if (state.playRequested) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playRequested) "暂停" else "播放",
                        tint = Color.White,
                    )
                }
                Slider(
                    value = state.fraction,
                    onValueChange = onScrub,
                    onValueChangeFinished = onSeekFinished,
                    enabled = active && state.visible && ready && state.durationMillis > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White, activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                    ),
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
            }
        }
    }
}
