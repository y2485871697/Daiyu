package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.icons.daiyuIconColor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatComposer(state: ComposerUi, actions: ChatScreenActions, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    var attachments by remember { mutableStateOf(false) }
    var voiceModes by remember { mutableStateOf(false) }
    val hasText = state.text.isNotBlank() || (state.phase == ComposerPhase.IDLE && state.imageSendAvailable)
    val primaryEnabled = if (hasText) state.textActionEnabled() else state.enabled && state.phase != ComposerPhase.IDLE
    val secondaryColor = if (state.enabled) colors.onSurfaceVariantSummary else colors.disabledOnSurface
    val primaryIcon = when {
        state.phase == ComposerPhase.PAUSED && !hasText -> DaiyuIcons.Resume
        hasText && state.phase != ComposerPhase.IDLE -> DaiyuIcons.Steer
        hasText -> DaiyuIcons.Send
        state.phase == ComposerPhase.RUNNING -> DaiyuIcons.Pause
        else -> DaiyuIcons.Send
    }
    val primaryLabel = when {
        state.phase == ComposerPhase.PAUSED && !hasText -> "继续"
        state.supplementPending -> "正在追加"
        hasText && state.phase != ComposerPhase.IDLE -> "追加消息"
        hasText -> "发送"
        state.phase == ComposerPhase.RUNNING -> "暂停"
        else -> "发送"
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh)) {
            Column {
                BasicTextField(
                    value = TextFieldValue(state.text, TextRange(state.selectionStart.coerceIn(0, state.text.length), state.selectionEnd.coerceIn(0, state.text.length))),
                    onValueChange = { value ->
                        actions.onTextChange(value.text)
                        actions.onSelectionChange(value.selection.start, value.selection.end)
                    },
                    enabled = state.enabled,
                    modifier = Modifier.testTag("chat-draft").fillMaxWidth().heightIn(min = 48.dp, max = 160.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    maxLines = 6, textStyle = MiuixTheme.textStyles.body1.copy(color = colors.onSurface, fontSize = 15.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(colors.primary),
                    decorationBox = { inner ->
                        Box {
                            if (state.text.isEmpty()) Text("给代鱼发消息", color = colors.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp, lineHeight = 22.sp))
                            inner()
                        }
                    },
                )
                // Same centered overlay as Eta's ChatComposerActionRow: both the visible
                // circle and its 48dp touch target are centered on the whole input row.
                Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChatIconButton(DaiyuIcons.Attach, "附件", { actions.onAttach(); attachments = true },
                                Modifier.size(40.dp), enabled = state.enabled, tint = daiyuIconColor(DaiyuIcons.Attach))
                            ChatIconButton(DaiyuIcons.Reasoning, "推理强度，当前为 ${state.reasoningLabel}", actions.onOpenReasoning,
                                Modifier.size(40.dp).testTag("chat-reasoning"), enabled = state.enabled, tint = daiyuIconColor(DaiyuIcons.Reasoning))
                            ChatIconButton(DaiyuIcons.Assistant, "选择助手，当前为 ${state.assistantLabel}", actions.onOpenAssistant,
                                Modifier.size(40.dp).testTag("chat-assistant"), enabled = state.enabled, tint = daiyuIconColor(DaiyuIcons.Assistant))
                        }
                        key(state.controlOwnerKey) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            if (hasText && state.phase != ComposerPhase.IDLE) {
                                ChatIconButton(if (state.phase == ComposerPhase.PAUSED) DaiyuIcons.Resume else DaiyuIcons.Pause,
                                    if (state.phase == ComposerPhase.PAUSED) "继续" else "暂停", actions.onPrimaryTap,
                                    Modifier.size(40.dp).testTag("chat-control").controlGesture(actions, state.enabled), enabled = state.enabled,
                                    onLongClick = actions.onPrimaryLongPress)
                            }
                            ChatIconButton(primaryIcon, primaryLabel, {
                                if (!hasText) actions.onPrimaryTap()
                                else if (state.phase == ComposerPhase.IDLE) actions.onSend(state.text)
                                else actions.onSupplement(state.text)
                            }, Modifier.size(48.dp).testTag(if (hasText || state.phase == ComposerPhase.IDLE) "chat-send" else "chat-control")
                                .controlGesture(actions, primaryEnabled).background(
                                if (!primaryEnabled) colors.disabledPrimaryButton
                                else if (state.phase == ComposerPhase.PAUSED || (!hasText && state.phase == ComposerPhase.RUNNING)) colors.onSurface else colors.primary,
                                CircleShape,
                            ),
                                enabled = primaryEnabled,
                                tint = if (state.phase == ComposerPhase.PAUSED || (!hasText && state.phase == ComposerPhase.RUNNING)) colors.surface else colors.onPrimary,
                                onLongClick = if (state.phase != ComposerPhase.IDLE) actions.onPrimaryLongPress else null)
                            }
                        }
                    }
                    Box {
                        SpeechMorphButton(
                            generating = state.showGenerationLoading(),
                            listening = state.listening,
                            enabled = state.enabled,
                            onClick = actions.onVoice,
                            onLongClick = { actions.onVoiceLongPress(); voiceModes = true },
                        )
                        ChatVoiceModeMenu(voiceModes, { voiceModes = false }, actions.onMenuAction)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ComposerChip(DaiyuIcons.Model, state.modelLabel.ifBlank { "模型" }, state.enabled, actions.onModelClick,
                Modifier.weight(1f).testTag("chat-model"),
                onLongClick = { actions.onMenuAction(ChatMenuAction.SUB_AGENT) })
            Row(Modifier.widthIn(min = 112.dp, max = 144.dp).testTag("chat-context").clip(RoundedCornerShape(12.dp))
                .combinedClickable(enabled = state.enabled, role = Role.Button, onClickLabel = "上下文统计", onClick = actions.onContextClick,
                    onLongClick = { actions.onMenuAction(ChatMenuAction.CONTEXT_SCOPE) })
                .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val fraction = state.contextFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                Canvas(Modifier.size(14.dp)) {
                    val stroke = Stroke(width = 2.dp.toPx())
                    drawArc(colors.outline, -90f, 360f, false, style = stroke)
                    if (fraction != null) drawArc(if (state.enabled) colors.primary else colors.disabledOnSurface, -90f, fraction * 360f, false, style = stroke)
                }
                Text(if (fraction == null) state.contextLabel else "${(fraction * 100).toInt()}% ${state.contextLabel}",
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.footnote1, color = secondaryColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (attachments) ChatAttachmentSheet({ attachments = false }, actions.onMenuAction)
}

@Composable
private fun Modifier.controlGesture(actions: ChatScreenActions, enabled: Boolean): Modifier {
    val latest by rememberUpdatedState(actions)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            latest.onControlPress()
            try {
                do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
            } finally { latest.onControlRelease() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComposerChip(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val colors = MiuixTheme.colorScheme
    val contentColor = if (enabled) colors.onSurfaceVariantSummary else colors.disabledOnSurface
    Row(modifier.clip(RoundedCornerShape(12.dp))
        .combinedClickable(enabled = enabled, role = Role.Button, onClickLabel = "选择模型",
            onClick = onClick, onLongClick = onLongClick).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = contentColor)
        Text(label, Modifier.weight(1f, fill = false), style = MiuixTheme.textStyles.footnote1,
            color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(DaiyuIcons.ChevronDown, null, Modifier.size(12.dp), tint = contentColor)
    }
}
