package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.icons.ChatContextMark
import app.daiyu.android.ui.icons.ChatPreviewIcons
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.daiyu.android.ui.components.AssistantDisplay
import app.daiyu.android.ui.components.AssistantAvatar
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import app.daiyu.android.ui.haptics.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.ModelBrandIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.icons.daiyuIconColor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatComposer(state: ComposerUi, actions: ChatScreenActions, modifier: Modifier = Modifier, assistant: AssistantDisplay? = null,
    balanceState: app.daiyu.android.balance.BalanceState? = null,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop? = null,
    attachmentContent: @Composable () -> Unit = {}) {
    val colors = MiuixTheme.colorScheme
    val appearance = chatActionColors()
    val chrome = chatChromeColors().copy(accent = conversationColors().accent)
    val contextColor = if (state.enabled) chatChromeColors().accent else colors.disabledOnSurface
    val dark = colors.background.luminance() < .3f
    val dividerColor = if (dark) Color(0xFFC8B5EB).copy(alpha = .12f) else Color(0xFF9280B5).copy(alpha = .14f)
    val currentEditorGeneration by rememberUpdatedState(state.editorGeneration)
    val currentDraft by rememberUpdatedState(state.text)
    var composingSource by remember(state.editorGeneration) { mutableStateOf<Pair<String, TextRange>?>(null) }
    val references = remember(state.text, composingSource) {
        ComposerReferences(state.text, composingSource?.takeIf { it.first == state.text }?.second?.let { it.min until it.max })
    }
    fun editReferenceDraft(next: String, start: Int, end: Int): Boolean {
        val edit = actions.onProjectedDraftEdit
        if (edit != null) return edit(references.source, next, start, end)
        actions.onTextChange(next)
        actions.onSelectionChange(start, end)
        return true
    }
    var attachments by remember { mutableStateOf(false) }
    var voiceModes by remember { mutableStateOf(false) }
    val hasText = state.text.isNotBlank() || (state.phase == ComposerPhase.IDLE && (state.imageSendAvailable || state.videoSendAvailable))
    val textAction = hasText && state.phase != ComposerPhase.RUNNING
    val primaryEnabled = if (textAction) state.textActionEnabled() else state.enabled && state.phase != ComposerPhase.IDLE
        val primaryIcon = when {
        state.phase == ComposerPhase.PAUSED && !hasText -> ChatPreviewIcons.Play
        textAction && state.phase != ComposerPhase.IDLE -> ChatPreviewIcons.Append
        textAction -> ChatPreviewIcons.Send
        state.phase == ComposerPhase.RUNNING -> ChatPreviewIcons.Pause
        else -> ChatPreviewIcons.Send
    }
    val primaryLabel = when {
        state.phase == ComposerPhase.PAUSED && !hasText -> "继续"
        state.supplementPending -> "正在追加"
        textAction && state.phase != ComposerPhase.IDLE -> "追加消息"
        textAction -> "发送"
        state.phase == ComposerPhase.RUNNING -> "暂停"
        else -> "发送"
    }
    Column(modifier.fillMaxWidth().padding(start = 9.dp, end = 9.dp, top = 10.dp, bottom = 16.dp)) {
        ChatComposerSurface(backdrop) {
        // Include the transparent 1dp CSS border in the composer box model.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ComposerChip(state.modelLabel.ifBlank { "模型" }, state.enabled, actions.onModelClick,
                Modifier.weight(1f).testTag("chat-model"),
                onLongClick = { actions.onMenuAction(ChatMenuAction.COLLABORATION) })
            Row(Modifier.widthIn(max = 120.dp).heightIn(min = 24.dp).testTag("chat-context").clip(RoundedCornerShape(12.dp))
                .border(1.dp, chrome.line, RoundedCornerShape(12.dp))
                .padding(1.dp).padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val fraction = state.contextFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                ChatContextMark(fraction, contextColor, Modifier.size(18.dp).testTag("chat-context-icon"))
                Text(if (fraction == null) state.contextLabel else "${(fraction * 100).toInt()}% ${state.contextLabel}",
                    modifier = Modifier.weight(1f, fill = false).testTag("chat-context-label"),
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 10.sp, lineHeight = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
                    color = contextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
                Box(Modifier.fillMaxWidth().padding(horizontal = 13.dp).height(1.dp)
                    .testTag("chat-composer-divider").background(dividerColor))
                ComposerReferenceChips(references.references, state.enabled) { reference ->
                    if (state.editorGeneration == currentEditorGeneration && references.source == currentDraft) {
                        val next = references.remove(reference)
                        val projected = ComposerReferences(next)
                        editReferenceDraft(next, projected.toSource(references.toVisible(state.selectionStart)),
                            projected.toSource(references.toVisible(state.selectionEnd)))
                    }
                }
                attachmentContent()
                key(state.editorGeneration) {
                    val mountedGeneration = state.editorGeneration
                    var editingValue by remember {
                        mutableStateOf(TextFieldValue(references.text).forComposer(references.text, references.toVisible(state.selectionStart), references.toVisible(state.selectionEnd)))
                    }
                    val renderedValue = editingValue.forComposer(references.text, references.toVisible(state.selectionStart), references.toVisible(state.selectionEnd))
                    SideEffect { if (editingValue != renderedValue) editingValue = renderedValue }
                    BasicTextField(
                        value = renderedValue,
                        onValueChange = { value ->
                            if (mountedGeneration == currentEditorGeneration) {
                                val bodyChanged = value.text != references.text
                                val next = references.edit(value.text)
                                fun rawOffset(offset: Int) = if (bodyChanged) offset.coerceIn(0, value.text.length) else references.toSource(offset)
                                val rawComposition = value.composition?.let { TextRange(rawOffset(it.start), rawOffset(it.end)) }
                                val projected = ComposerReferences(next, rawComposition?.let { it.min until it.max })
                                val selection = TextRange(projected.toVisible(rawOffset(value.selection.start)),
                                    projected.toVisible(rawOffset(value.selection.end)))
                                val composition = rawComposition?.let { TextRange(projected.toVisible(it.start), projected.toVisible(it.end)) }
                                val accepted = if (references.references.isEmpty() && projected.references.isEmpty()) {
                                    if (next != state.text) actions.onTextChange(next)
                                    actions.onSelectionChange(rawOffset(value.selection.start), rawOffset(value.selection.end))
                                    true
                                } else editReferenceDraft(next, projected.toSource(selection.start), projected.toSource(selection.end))
                                if (accepted) {
                                    composingSource = rawComposition?.let { next to it }
                                    editingValue = TextFieldValue(projected.text, selection, composition)
                                }
                            }
                        },
                        enabled = state.enabled,
                        modifier = Modifier.testTag("chat-draft").fillMaxWidth().heightIn(min = 53.dp, max = 176.dp)
                            .padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 7.dp),
                        maxLines = 6, textStyle = MiuixTheme.textStyles.body1.copy(color = chrome.text, fontSize = 16.sp, lineHeight = 24.sp),
                        cursorBrush = SolidColor(chrome.accent),
                        decorationBox = { inner ->
                            Box {
                                if (references.text.isEmpty()) Text("给代鱼发消息", color = chrome.muted,
                                    style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, lineHeight = 24.sp))
                                inner()
                            }
                        },
                    )
                }
                Box(Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 12.dp).heightIn(min = 36.dp).testTag("chat-composer-bottom"), contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChatIconButton(ChatPreviewIcons.Attach, "附件", { actions.onAttach(); attachments = true },
                                Modifier.size(32.dp), enabled = state.enabled, tint = chrome.toolbar, iconSize = 24.dp)
                            ChatIconButton(ChatPreviewIcons.Thought, "推理强度，当前为 ${state.reasoningLabel}", actions.onOpenReasoning,
                                Modifier.size(32.dp).testTag("chat-reasoning"), enabled = state.enabled, tint = chrome.accent, iconSize = 24.dp)
                            Box(Modifier.size(32.dp).testTag("chat-assistant").clip(CircleShape)
                                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = state.enabled, role = Role.Button,
                                    onClickLabel = "选择助手", onClick = actions.onOpenAssistant)
                                .semantics { contentDescription = "选择助手，当前为 ${state.assistantLabel}" },
                                contentAlignment = Alignment.Center) {
                                AssistantAvatar(assistant, 24.dp, Modifier.testTag("chat-assistant-avatar"))
                            }
                        }
                        key(state.controlOwnerKey) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            app.daiyu.android.balance.ui.ProviderBalanceIndicator(
                                state = balanceState, modifier = Modifier.testTag("chat-composer-provider-balance")
                                    .widthIn(max = 72.dp), animateChanges = true, iconSize = 16.dp,
                                textStyle = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 28.sp))
                            ChatIconButton(primaryIcon, primaryLabel, {
                                if (!textAction) actions.onPrimaryTap()
                                else if (state.phase == ComposerPhase.IDLE) actions.onSend(state.text)
                                else actions.onSupplement(state.text)
                            }, Modifier.size(32.dp).testTag(if (textAction || state.phase == ComposerPhase.IDLE) "chat-send" else "chat-control")
                                .controlGesture(actions, primaryEnabled),
                                containerColor = (if (textAction && state.phase != ComposerPhase.IDLE) appearance.appendContainer else appearance.sendContainer)
                                    .copy(alpha = if (primaryEnabled) 1f else .4f),
                                enabled = primaryEnabled, containerSize = 28.dp,
                                tint = if (textAction && state.phase != ComposerPhase.IDLE) appearance.appendContent else appearance.sendContent,
                                iconSize = if (state.phase == ComposerPhase.RUNNING || state.phase == ComposerPhase.PAUSED && !hasText) 14.dp else 16.dp,
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
internal fun ComposerChip(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val colors = MiuixTheme.colorScheme
    val chrome = chatChromeColors().copy(accent = conversationColors().accent)
    val contentColor = if (enabled) chrome.toolbar else colors.disabledOnSurface
    val modelColor = chrome.accent
    Row(modifier.heightIn(min = 26.dp).clip(RoundedCornerShape(12.dp))
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClickLabel = "选择模型",
            onClick = onClick, onLongClick = onLongClick),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        ModelBrandIcon(label, Modifier.size(13.dp).alpha(if (enabled) 1f else .45f), tint = if (enabled) modelColor else contentColor)
        Text(label, Modifier.weight(1f, fill = false).offset(y = (-.75f).dp).testTag("composer-model-label"),
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
            color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(ChatPreviewIcons.Chevron, null, Modifier.size(12.dp), tint = if (enabled) chrome.muted else contentColor)
    }
}
