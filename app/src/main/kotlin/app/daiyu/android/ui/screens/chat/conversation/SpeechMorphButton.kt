package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import app.daiyu.android.ui.haptics.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** ChatSpeechIndicator's visual/gesture layer, without permissions, capture or a speech backend. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpeechMorphButton(
    generating: Boolean,
    listening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    description: String? = null,
    callMotion: DoubaoCallMotionState? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var tapSequence by remember(enabled) { mutableIntStateOf(0) }
    var tapFeedback by remember(enabled) { mutableStateOf(false) }
    LaunchedEffect(tapSequence, enabled) {
        if (!enabled || tapSequence == 0) return@LaunchedEffect
        tapFeedback = true
        delay(180)
        tapFeedback = false
    }
    val showPressFeedback = enabled && (pressed || tapFeedback)
    val diameter = animateDpAsState(
        if (listening || showPressFeedback) 32.dp else 20.dp,
        spring(dampingRatio = 0.65f),
        label = "speechDiameter",
    )
    Box(
        modifier = modifier.testTag("chat-voice").size(32.dp).clip(CircleShape)
            .semantics {
                contentDescription = description ?: if (listening) "停止语音输入" else "语音输入"
                if (listening) stateDescription = "正在聆听"
                else if (generating) stateDescription = "正在生成"
            }
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                indication = null,
                interactionSource = interactionSource,
                onLongClickLabel = if (onLongClick != null) "选择语音功能" else null,
                onLongClick = onLongClick,
                onClick = { tapSequence++; onClick() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        ContainedMorphLoadingIndicator(
            modifier = Modifier.testTag("chat-voice-indicator").graphicsLayer {
                // Read the SAME genie progress in the layer, never once per frame in composition.
                val progress = callMotion?.progress?.value ?: 0f
                val scale = maxOf(diameter.value / 20.dp, callEntryScale(progress))
                scaleX = scale
                scaleY = scale
                rotationZ = callEntryRotation(progress)
            },
            indicatorSize = 20.dp,
            // Call motion adds activity; it must never replace generation/listening's oscillator.
            animate = generating || listening || showPressFeedback || callMotion?.transforming == true,
        )
    }
}
