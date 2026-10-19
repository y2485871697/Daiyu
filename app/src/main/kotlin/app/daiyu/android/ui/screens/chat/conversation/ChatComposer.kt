package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.daiyu.android.ui.components.AssistantDisplay
import app.daiyu.android.ui.components.AssistantAvatar
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
import androidx.compose.runtime.SideEffect
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
internal fun ChatComposer(state: ComposerUi, actions: ChatScreenActions, modifier: Modifier = Modifier, assistant: AssistantDisplay? = null,
    balanceState: app.daiyu.android.balance.BalanceState? = null,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop? = null) {
    val colors = MiuixTheme.colorScheme
    val currentEditorGeneration by rememberUpdatedState(state.editorGeneration)
    var attachments by remember { mutableStateOf(false) }
    var voiceModes by remember { mutableStateOf(false) }
    val hasText = state.text.isNotBlank() || (state.phase == ComposerPhase.IDLE && state.imageSendAvailable)
    val textAction = hasText && state.phase != ComposerPhase.RUNNING
    val primaryEnabled = if (textAction) state.textActionEnabled() else state.enabled && state.phase != ComposerPhase.IDLE
    val secondaryColor = if (state.enabled) colors.onSurfaceVariantSummary else colors.disabledOnSurface
    val primaryIcon = when {
        state.phase == ComposerPhase.PAUSED && !hasText -> DaiyuIcons.Resume
        textAction && state.phase != ComposerPhase.IDLE -> DaiyuIcons.Steer
        textAction -> DaiyuIcons.Send
        state.phase == ComposerPhase.RUNNING -> DaiyuIcons.Pause
        else -> DaiyuIcons.Send
    }
    val primaryLabel = when {
        state.phase == ComposerPhase.PAUSED && !hasText -> "继续"
        state.supplementPending -> "正在追加"
        textAction && state.phase != ComposerPhase.IDLE -> "追加消息"
        textAction -> "发送"
        state.phase == ComposerPhase.RUNNING -> "暂停"
        else -> "发送"
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        ChatComposerSurface(backdrop) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ComposerChip(DaiyuIcons.Model, state.modelLabel.ifBlank { "模型" }, state.enabled, actions.onModelClick,
                Modifier.weight(1f).testTag("chat-model"),
                onLongClick = { actions.onMenuAction(ChatMenuAction.COLLABORATION) })
            Row(Modifier.widthIn(min = 112.dp, max = 144.dp).testTag("chat-context").clip(RoundedCornerShape(12.dp))
                .combinedClickable(enabled = state.enabled, role = Role.Button, onClickLabel = "上下文统计", onClick = actions.onContextClick,
                    onLongClick = { actions.onMenuAction(ChatMenuAction.CONTEXT_SCOPE) })
                .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val fraction = state.contextFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                Canvas(Modifier.size(16.dp)) {
                    val path = Path().apply {
                        moveTo(size.width * .5f, size.height * .48f)
                        cubicTo(-size.width * .15f, -size.height * .25f, 0f, size.height * .9f, size.width * .5f, size.height * .55f)
                        cubicTo(size.width * .02f, size.height * 1.35f, size.width * 1.1f, size.height * 1.18f, size.width * .5f, size.height * .48f)
                        cubicTo(size.width * 1.15f, -size.height * .25f, size.width, size.height * .9f, size.width * .5f, size.height * .55f)
                    }
                    val stroke = Stroke(width = 1.5.dp.toPx())
                    drawPath(path, colors.primary.copy(alpha = .24f), style = stroke)
                    if (fraction != null) {
                        val measure = PathMeasure().apply { setPath(path, false) }
                        val progress = Path()
                        measure.getSegment(0f, measure.length * fraction, progress)
                        drawPath(progress, if (state.enabled) colors.primary else colors.disabledOnSurface, style = stroke)
                    }
                }
                Text(if (fraction == null) state.contextLabel else "${(fraction * 100).toInt()}% ${state.contextLabel}",
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.footnote1, color = secondaryColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp)
                    .background(colors.primary.copy(alpha = 0.12f)))
                key(state.editorGeneration) {
                    val mountedGeneration = state.editorGeneration
                    var editingValue by remember {
                        mutableStateOf(TextFieldValue(state.text).forComposer(state.text, state.selectionStart, state.selectionEnd))
                    }
                    val renderedValue = editingValue.forComposer(state.text, state.selectionStart, state.selectionEnd)
                    SideEffect { if (editingValue != renderedValue) editingValue = renderedValue }
                    BasicTextField(
                        value = renderedValue,
                        onValueChange = { value ->
                            if (mountedGeneration == currentEditorGeneration) {
                                editingValue = value
                                if (value.text != state.text) actions.onTextChange(value.text)
                                actions.onSelectionChange(value.selection.start, value.selection.end)
                            }
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
                }
                // Same centered overlay as Eta's ChatComposerActionRow: both the visible
                // circle and its 48dp touch target are centered on the whole input row.
                Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChatIconButton(DaiyuIcons.Attach, "附件", { actions.onAttach(); attachments = true },
                                Modifier.size(40.dp), enabled = state.enabled, tint = daiyuIconColor(DaiyuIcons.Attach), iconSize = 16.dp)
                            ChatIconButton(DaiyuIcons.Reasoning, "推理强度，当前为 ${state.reasoningLabel}", actions.onOpenReasoning,
                                Modifier.size(40.dp).testTag("chat-reasoning"), enabled = state.enabled, tint = daiyuIconColor(DaiyuIcons.Reasoning))
                            Box(Modifier.size(40.dp).testTag("chat-assistant").clip(CircleShape)
                                .combinedClickable(enabled = state.enabled, role = Role.Button,
                                    onClickLabel = "选择助手", onClick = actions.onOpenAssistant)
                                .semantics { contentDescription = "选择助手，当前为 ${state.assistantLabel}" },
                                contentAlignment = Alignment.Center) {
                                AssistantAvatar(assistant, 18.dp)
                            }
                        }
                        key(state.controlOwnerKey) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            app.daiyu.android.balance.ui.ProviderBalanceIndicator(
                                state = balanceState, modifier = Modifier.testTag("chat-provider-balance")
                                    .widthIn(max = 60.dp).padding(horizontal = 2.dp), animateChanges = true)
                            ChatIconButton(primaryIcon, primaryLabel, {
                                if (!textAction) actions.onPrimaryTap()
                                else if (state.phase == ComposerPhase.IDLE) actions.onSend(state.text)
                                else actions.onSupplement(state.text)
                            }, Modifier.size(48.dp).testTag(if (textAction || state.phase == ComposerPhase.IDLE) "chat-send" else "chat-control")
                                .controlGesture(actions, primaryEnabled),
                                containerColor = if (!primaryEnabled) colors.disabledPrimaryButton
                                    else if (state.phase == ComposerPhase.PAUSED || (!textAction && state.phase == ComposerPhase.RUNNING)) colors.onSurface else colors.primary,
                                enabled = primaryEnabled,
                                tint = if (state.phase == ComposerPhase.PAUSED || (!textAction && state.phase == ComposerPhase.RUNNING)) colors.surface else colors.onPrimary,
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
                        ChatVoiceModeMenu(voiceModes, { voiceModes = false }, actions.onMenuAction, state.voiceMode)
                    }
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
