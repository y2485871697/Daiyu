/*
 * Wave drawing adapted from NextPlayerCloud/NeoPlayer, Seekbar.kt (Apache-2.0),
 * commit 4151a45f862550a91b7a8efe35a6b19841242d48.
 * Daiyu changes: local video controls, native slider gestures/accessibility,
 * cancellation-safe animations, and no chapter, timer or A-B loop UI.
 * See THIRD_PARTY/neoplayer/NOTICE.md and LICENSE.
 */
package app.daiyu.android.ui.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp

internal fun chatMediaPreviewVideoWaveAmplitude(x: Float, playedX: Float, heightFraction: Float): Float =
    6f * heightFraction.coerceIn(0f, 1f) * ((playedX + 60f - x) / 120f).coerceIn(0f, 1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatMediaPreviewVideoProgress(
    value: Float,
    playing: Boolean,
    seeking: Boolean,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val dragged by interactions.collectIsDraggedAsState()
    val scrubbing = seeking || dragged
    val target = value.coerceIn(0f, 1f)
    val position = remember { Animatable(target) }
    val immediate = scrubbing || !playing || target == 0f || target == 1f || target < position.value
    val fraction = if (immediate) target else position.value
    LaunchedEffect(target, immediate) {
        if (immediate) position.snapTo(target)
        else position.animateTo(target, tween(200, easing = LinearEasing))
    }
    val height = remember { Animatable(if (playing) 1f else 0f) }
    var lastPhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, scrubbing, enabled) {
        val flatten = !playing || scrubbing || !enabled
        height.animateTo(if (flatten) 0f else 1f,
            tween(if (flatten) 550 else 800, delayMillis = if (flatten) 0 else 60, easing = LinearEasing))
    }
    val phase = if (playing && enabled) {
        val start = remember { lastPhase }
        val transition = rememberInfiniteTransition(label = "video-progress-wave")
        val animated by transition.animateFloat(start, start + 80f,
            infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "video-progress-phase")
        SideEffect { lastPhase = animated % 80f }
        animated % 80f
    } else lastPhase
    val primary = MaterialTheme.colorScheme.primary
    Slider(
        value = value,
        onValueChange = { if (enabled) onValueChange(it) },
        onValueChangeFinished = { if (enabled) onValueChangeFinished() },
        enabled = enabled,
        interactionSource = interactions,
        modifier = modifier,
        thumb = {},
        track = {
            Canvas(Modifier.fillMaxWidth().height(48.dp).testTag("chat-media-video-progress-track")) {
                val stroke = 5.dp.toPx()
                val playedX = size.width * fraction
                val centerY = size.height / 2f
                val waveStart = -phase - 40f
                val path = Path().apply { moveTo(waveStart, centerY) }
                var x = waveStart
                var sign = 1f
                var amplitude = chatMediaPreviewVideoWaveAmplitude(x, playedX, height.value)
                while (x < size.width) {
                    sign = -sign
                    val nextX = x + 40f
                    val nextAmplitude = sign * chatMediaPreviewVideoWaveAmplitude(nextX, playedX, height.value)
                    path.cubicTo(x + 20f, centerY + amplitude, x + 20f, centerY + nextAmplitude,
                        nextX, centerY + nextAmplitude)
                    x = nextX
                    amplitude = nextAmplitude
                }
                val color = primary.copy(alpha = if (enabled) 1f else 0.38f)
                fun segment(left: Float, right: Float, alpha: Float) {
                    if (right <= left) return
                    clipRect(left, centerY - 6f - stroke, right, centerY + 6f + stroke) {
                        drawPath(path, color.copy(alpha = color.alpha * alpha),
                            style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
                segment(0f, playedX, 1f)
                segment(playedX, size.width, 77f / 255f)
                val startAmplitude = kotlin.math.cos(kotlin.math.abs(waveStart) / 80f * (2f * kotlin.math.PI.toFloat()))
                drawCircle(color, stroke / 2f, Offset(0f, centerY + startAmplitude * 6f * height.value))
                drawLine(color, Offset(playedX, centerY - 6f - stroke), Offset(playedX, centerY + 6f + stroke),
                    strokeWidth = stroke, cap = StrokeCap.Round)
            }
        },
    )
}
