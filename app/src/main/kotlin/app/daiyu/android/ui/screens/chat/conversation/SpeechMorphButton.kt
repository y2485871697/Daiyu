package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.haptics.TouchHaptics

/** ChatSpeechIndicator's visual/gesture layer, without permissions, capture or a speech backend. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpeechMorphButton(
    generating: Boolean,
    listening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val diameter by animateDpAsState(
        if (listening) 28.dp else 20.dp,
        spring(dampingRatio = 0.65f),
        label = "speechDiameter",
    )
    Box(
        modifier = modifier.size(28.dp).clip(CircleShape)
            .semantics {
                contentDescription = if (listening) "停止语音输入" else "语音输入"
                if (listening) stateDescription = "正在聆听"
                else if (generating) stateDescription = "正在生成"
            }
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                hapticFeedbackEnabled = false,
                onLongClick = { TouchHaptics.longPress(view); onLongClick() },
                onClick = { TouchHaptics.click(view); onClick() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        ContainedMorphLoadingIndicator(indicatorSize = diameter, animate = generating || listening)
    }
}
