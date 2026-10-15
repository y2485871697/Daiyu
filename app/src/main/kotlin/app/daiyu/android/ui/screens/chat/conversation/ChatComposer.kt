package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChatComposer(state: ComposerUi, actions: ChatScreenActions, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    var attachments by remember { mutableStateOf(false) }
    val hasText = state.text.isNotBlank()
    val primaryIcon = when {
        state.phase == ComposerPhase.PAUSED -> DaiyuIcons.Resume
        hasText && state.phase == ComposerPhase.RUNNING -> DaiyuIcons.Steer
        hasText -> DaiyuIcons.Send
        state.phase == ComposerPhase.RUNNING -> DaiyuIcons.Pause
        else -> DaiyuIcons.Send
    }
    val primaryLabel = when {
        state.phase == ComposerPhase.PAUSED -> "继续"
        hasText && state.phase == ComposerPhase.RUNNING -> "追加消息"
        hasText -> "发送"
        state.phase == ComposerPhase.RUNNING -> "暂停"
        else -> "发送"
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surface)
            .border(1.dp, colors.onSurfaceVariantSummary.copy(alpha = 0.15f), RoundedCornerShape(24.dp))) {
            Column {
                BasicTextField(
                    value = state.text, onValueChange = actions.onTextChange,
                    enabled = state.enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 160.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    maxLines = 6, textStyle = MiuixTheme.textStyles.body1.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    decorationBox = { inner ->
                        Box {
                            if (state.text.isEmpty()) Text("给代鱼发消息", color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body1)
                            inner()
                        }
                    },
                )
                // Same centered overlay as Eta's ChatComposerActionRow: both the visible
                // circle and its 48dp touch target are centered on the whole input row.
                Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        ChatIconButton(DaiyuIcons.Attach, "附件", { actions.onAttach(); attachments = true }, enabled = state.enabled)
                        ChatIconButton(primaryIcon, primaryLabel, {
                            if (state.phase == ComposerPhase.PAUSED) actions.onPrimaryTap()
                            else if (hasText) actions.onSend(state.text)
                            else actions.onPrimaryTap()
                        }, Modifier.background(
                            if (state.phase == ComposerPhase.PAUSED || (!hasText && state.phase == ComposerPhase.RUNNING)) colors.onSurface else colors.primary,
                            CircleShape,
                        ),
                            enabled = state.enabled && (hasText || state.phase != ComposerPhase.IDLE),
                            tint = if (state.phase == ComposerPhase.PAUSED || (!hasText && state.phase == ComposerPhase.RUNNING)) colors.surface else colors.onPrimary,
                            onLongClick = if (state.phase != ComposerPhase.IDLE) actions.onPrimaryLongPress else null)
                    }
                    SpeechMorphButton(
                        generating = state.phase == ComposerPhase.RUNNING,
                        listening = state.listening,
                        enabled = state.enabled,
                        onClick = actions.onVoice,
                        onLongClick = actions.onVoiceLongPress,
                    )
                }
            }
        }
        if (state.phase != ComposerPhase.IDLE) {
            Text("短按暂停 · 长按停止", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ComposerChip(DaiyuIcons.Model, state.modelLabel, state.enabled, actions.onModelClick)
            ComposerChip(DaiyuIcons.Reasoning, state.reasoningLabel, state.enabled, actions.onOpenReasoning)
            ComposerChip(DaiyuIcons.Assistant, state.assistantLabel, state.enabled, actions.onOpenAssistant)
            Row(Modifier.clip(RoundedCornerShape(50)).background(colors.surfaceContainerHigh)
                .clickable(enabled = state.enabled, onClick = actions.onContextClick).padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val fraction = state.contextFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                Canvas(Modifier.size(16.dp)) {
                    val stroke = Stroke(width = 2.dp.toPx())
                    drawArc(colors.onSurfaceVariantSummary.copy(alpha = 0.2f), -90f, 360f, false, style = stroke)
                    if (fraction != null) drawArc(colors.primary, -90f, fraction * 360f, false, style = stroke)
                }
                Text(if (fraction == null) state.contextLabel else "${state.contextLabel} ${(fraction * 100).toInt()}%",
                    style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
            }
        }
    }
    if (attachments) ChatAttachmentSheet({ attachments = false }, actions.onMenuAction)
}

@Composable
internal fun ComposerChip(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(MiuixTheme.colorScheme.surfaceContainerHigh)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    }
}
