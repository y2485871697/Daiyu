package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal class VoiceCallCompressionState {
    var dots by mutableIntStateOf(1)
    var success by mutableStateOf(false)
    var searching by mutableStateOf(false)
    val check = Animatable(0f)
}

@Composable
internal fun rememberVoiceCallCompressionState(compressing: Boolean, completion: String?, running: Boolean,
    searching: Boolean = false, searchCompletion: String? = null, resetKey: Long = 0): VoiceCallCompressionState {
    val state = remember { VoiceCallCompressionState() }
    var consumed by remember { mutableStateOf<String?>(null) }
    var consumedSearch by remember { mutableStateOf<String?>(null) }
    val busy = compressing || searching
    LaunchedEffect(busy, running, resetKey) {
        state.dots = 1
        while (busy && running) { delay(350); state.dots = state.dots % 3 + 1 }
    }
    LaunchedEffect(compressing, completion, searching, searchCompletion, resetKey, running) {
        state.success = false
        state.check.snapTo(0f)
        val compressed = completion != null && completion != consumed
        val searched = searchCompletion != null && searchCompletion != consumedSearch
        state.searching = !compressing && (searching || searched && !compressed)
        if (running && !busy && (compressed || searched)) {
            consumed = completion
            consumedSearch = searchCompletion
            state.success = true
            state.check.animateTo(1f, tween(280))
            delay(2_000)
            state.success = false
        }
    }
    return state
}

@Composable
internal fun VoiceCallCompressionIndicator(compressing: Boolean, completion: String?, running: Boolean = true,
    state: VoiceCallCompressionState = rememberVoiceCallCompressionState(compressing, completion, running)) {
    Box(Modifier.width(82.dp).height(24.dp).testTag("voice-call-activity-slot"), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
        if (compressing) Row(Modifier.testTag(if (state.searching) "voice-call-searching" else "voice-call-compressing")) {
            Text(if (state.searching) "正在搜索" else "正在压缩", color = Color(0xFF9483A8), fontSize = 10.sp)
            Text(".".repeat(state.dots), Modifier.width(15.dp), color = Color(0xFF9483A8), fontSize = 10.sp)
        }
        AnimatedVisibility(state.success && !compressing, enter = fadeIn(tween(150)) + scaleIn(tween(180)),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.65f)) {
            Canvas(Modifier.size(12.dp).testTag(if (state.searching) "voice-call-searched" else "voice-call-compressed")
                .semantics { contentDescription = if (state.searching) "搜索完成" else "压缩完成" }) {
                val start = Offset(size.width * .16f, size.height * .51f)
                val corner = Offset(size.width * .41f, size.height * .76f)
                val end = Offset(size.width * .86f, size.height * .25f)
                val progress = state.check.value
                if (progress > 0f) drawLine(Color(0xFF54AF78), start,
                    start + (corner - start) * (progress / .35f).coerceAtMost(1f), 1.5.dp.toPx(), StrokeCap.Round)
                if (progress > .35f) drawLine(Color(0xFF54AF78), corner,
                    corner + (end - corner) * ((progress - .35f) / .65f), 1.5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}
