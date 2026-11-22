package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

internal val ReplyWaveGap = 6.dp

@Composable
internal fun StreamingReplyWave(moving: Boolean, modifier: Modifier = Modifier) {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(moving) {
        if (moving) {
            val start = phase.value % 1f
            phase.snapTo(start)
            phase.animateTo(start + 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)))
        }
    }
    val tint = conversationColors().accent
    val path = remember { Path() }
    Canvas(modifier.padding(top = ReplyWaveGap).size(30.dp, 8.dp).testTag("reply-output-wave")) {
        val stroke = 1.5.dp.toPx()
        val pos = phase.value
        path.reset()
        for (i in 0..48) {
            val t = i / 48f
            val x = stroke / 2 + t * (size.width - stroke)
            val y = size.height * (.5f + .32f * sin(PI * t).toFloat() * sin(2 * PI * (2 * t - pos)).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
