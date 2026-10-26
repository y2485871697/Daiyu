package app.daiyu.android.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.ui.icons.ChatPreviewIcons

@Composable
internal fun ChatThinkingSpeedIcon(mode: GptSpeedMode?, tint: Color, modifier: Modifier = Modifier) {
    AnimatedContent(targetState = mode ?: GptSpeedMode.NORMAL,
        modifier = modifier.semantics { if (mode != null) stateDescription = when (mode) {
            GptSpeedMode.NORMAL -> "标准速度"
            GptSpeedMode.FAST -> "快速速度"
            GptSpeedMode.ULTRA_FAST -> "极速速度"
        } }, contentAlignment = Alignment.Center,
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(tween(320), initialScale = .65f)) togetherWith
                (fadeOut(tween(140)) + scaleOut(tween(220), targetScale = 1.2f))
        }, label = "chat-thinking-speed") { speed ->
        Icon(when (speed) {
            GptSpeedMode.NORMAL -> ChatPreviewIcons.Thought
            GptSpeedMode.FAST -> ChatPreviewIcons.ThoughtFast
            GptSpeedMode.ULTRA_FAST -> ChatPreviewIcons.ThoughtUltraFast
        }, contentDescription = null, modifier = Modifier.fillMaxSize(), tint = tint)
    }
}
