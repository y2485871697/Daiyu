package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import app.daiyu.android.ui.haptics.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

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
) {
    val diameter by animateDpAsState(
        if (listening) 32.dp else 20.dp,
        spring(dampingRatio = 0.65f),
        label = "speechDiameter",
    )
    Box(
        modifier = modifier.testTag("chat-voice").size(32.dp).clip(CircleShape)
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
                onLongClickLabel = if (onLongClick != null) "选择语音功能" else null,
                onLongClick = onLongClick,
                onClick = { onClick() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        ContainedMorphLoadingIndicator(indicatorSize = diameter, animate = generating || listening)
    }
}
