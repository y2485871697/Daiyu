package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Icon

@Composable
internal fun MessageSpeechIcon(speaking: Boolean, modifier: Modifier = Modifier) {
    val tint = conversationColors().muted
    if (!speaking) {
        Icon(DaiyuIcons.Speak, "朗读", modifier.testTag("message-speech-idle"), tint = tint)
        return
    }
    val transition = rememberInfiniteTransition(label = "messageSpeech")
    val wave by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "speakerWaves")
    val body = remember { Path().apply {
        moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f)
        lineTo(8f, 15f); lineTo(4f, 15f); close()
    } }
    val inner = remember { Path().apply {
        moveTo(16f, 9f); cubicTo(18f, 10.5f, 18f, 13.5f, 16f, 15f)
    } }
    val outer = remember { Path().apply {
        moveTo(18f, 5f); cubicTo(22f, 8.5f, 22f, 15.5f, 18f, 19f)
    } }
    Canvas(modifier.testTag("message-speech-playing").semantics { contentDescription = "停止朗读" }) {
        val stroke = Stroke(width = 1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(body, tint, style = stroke)
            drawPath(inner, tint, alpha = if (wave < 0.33f) 0.25f else 1f, style = stroke)
            drawPath(outer, tint, alpha = if (wave < 0.66f) 0.25f else 1f, style = stroke)
        }
    }
}
