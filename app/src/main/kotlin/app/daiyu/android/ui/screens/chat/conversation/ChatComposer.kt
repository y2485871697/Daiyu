package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.components.managedInput

import app.daiyu.android.ui.icons.ChatContextMark
import app.daiyu.android.ui.icons.ChatPreviewIcons
import androidx.compose.foundation.layout.height
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import app.daiyu.android.ui.components.hideChatInputIme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.Icon
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import app.daiyu.android.ui.icons.modelNameOpticalAlignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.onFocusChanged
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChatComposer(state: ComposerUi, actions: ChatScreenActions, modifier: Modifier = Modifier, assistant: AssistantDisplay? = null,
    balanceState: app.daiyu.android.balance.BalanceState? = null,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop? = null,
    onInputFocusChanged: (Boolean) -> Unit = {},
    isInputDismissInProgress: () -> Boolean = { false },
    onOpenWorkspace: () -> Unit = actions.onOpenWorkspace,
    onVoiceAnchorBounds: (Rect) -> Unit = {},
    callUi: DoubaoCallUi? = null,
    callMotion: DoubaoCallMotionState? = null,
    attachmentContent: @Composable () -> Unit = {}) {
    var lifecycleDismissInProgress by remember { mutableStateOf(false) }
    app.daiyu.android.ui.components.DismissImeOnStop { dismiss ->
        lifecycleDismissInProgress = true
        try { dismiss() } finally { lifecycleDismissInProgress = false }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val editing = actions.editingMessageKey != null
    val toolbarEnabled = state.enabled && !editing
    val attachmentEnabled = state.enabled
    val visualEnabled = state.enabled || state.gptSpeedSaving
    val colors = MiuixTheme.colorScheme
    val appearance = chatActionColors()
    val chrome = chatChromeColors().copy(accent = conversationColors().accent)
    val contextColor = if (visualEnabled) chatChromeColors().accent else colors.disabledOnSurface
    val dark = colors.background.luminance() < .3f
    val dividerColor = if (dark) Color(0xFFC8B5EB).copy(alpha = .12f) else Color(0xFF9280B5).copy(alpha = .14f)
    val currentEditorGeneration by rememberUpdatedState(state.editorGeneration)
    val currentEditorEnabled by rememberUpdatedState(state.enabled)
    val currentDraft by rememberUpdatedState(state.text)
    var composingSource by remember(state.editorGeneration, actions.editingMessageKey) { mutableStateOf<Pair<String, TextRange>?>(null) }
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
    var showContextTokens by rememberSaveable { mutableStateOf(false) }
    var attachments by remember(state.editorGeneration, actions.editingMessageKey, state.enabled) { mutableStateOf(false) }
    var voiceModes by remember(state.editorGeneration, state.enabled, state.voiceModes, callUi?.sessionKey) { mutableStateOf(false) }
    val voiceMenuEnabled = toolbarEnabled && state.voiceModes.isNotEmpty()
    val hasText = state.text.isNotBlank() || state.editingMessageCanSubmit || (state.phase == ComposerPhase.IDLE && (state.imageSendAvailable || state.videoSendAvailable))
    val preparingSend = state.preparingSendKey != null
    val textAction = hasText && !preparingSend
    val compressionBlocked = !preparingSend && state.mainContextCompressing && (textAction || state.phase == ComposerPhase.IDLE)
    val primaryEnabled = preparingSend || if (textAction) state.textActionEnabled() else state.enabled && state.phase != ComposerPhase.IDLE
    val primaryVisualEnabled = preparingSend || if (textAction) state.copy(enabled = visualEnabled).textActionEnabled()
        else visualEnabled && state.phase != ComposerPhase.IDLE
    val primaryIcon = when {
        preparingSend -> app.daiyu.android.ui.icons.DaiyuIcons.Stop
        state.phase == ComposerPhase.PAUSED && !hasText -> ChatPreviewIcons.Play
        textAction && state.phase != ComposerPhase.IDLE -> ChatPreviewIcons.Append
        textAction -> ChatPreviewIcons.Send
        state.phase == ComposerPhase.RUNNING -> ChatPreviewIcons.Pause
        else -> ChatPreviewIcons.Send
    }
    val primaryLabel = when {
        preparingSend -> "取消发送"
        state.phase == ComposerPhase.PAUSED && !hasText -> "继续"
        textAction && state.supplementPending -> "正在追加"
        textAction && state.phase != ComposerPhase.IDLE -> "追加消息"
        editing -> "保存并重新生成"
        textAction -> "发送"
        state.phase == ComposerPhase.RUNNING -> "暂停"
        else -> "发送"
    }
    val workspacePointers = remember(state.editorGeneration) { WorkspacePullUpPointers() }
    Column(modifier.observeWorkspacePointers(workspacePointers).managedInput().fillMaxWidth().padding(start = 9.dp, end = 9.dp, top = 10.dp, bottom = 16.dp)) {
        ChatComposerSurface(backdrop) {
        val openWorkspace = {
            lifecycleDismissInProgress = true
            try { hideChatInputIme(focus, keyboard, view) }
            finally { lifecycleDismissInProgress = false }
            onOpenWorkspace()
        }
        key(state.editorGeneration) { ComposerWorkspacePullUpArea(workspacePointers, openWorkspace) }
        // The original top padding is now a content-side blank drag strip.
        // Include the transparent 1dp CSS border in the composer box model.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 4.dp).testTag("chat-composer-header"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ComposerChip(state.modelLabel.ifBlank { "模型" }, state.enabled, actions.onModelClick,
                Modifier.weight(1f).testTag("chat-model"),
                onLongClick = if (editing) null else ({ actions.onMenuAction(ChatMenuAction.COLLABORATION) }), visualEnabled = visualEnabled)
            if (editing) ComposerCancelEdit(state.enabled, actions.onCancelMessageEdit)
            Row(Modifier.then(if (editing) Modifier.weight(1f).wrapContentWidth(Alignment.End) else Modifier)
                .widthIn(max = 120.dp).heightIn(min = 22.dp).testTag("chat-context").clip(RoundedCornerShape(11.dp))
                .border(1.dp, chrome.line, RoundedCornerShape(11.dp))
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    role = Role.Button,
                    onClickLabel = if (showContextTokens) "显示上下文百分比" else "显示上下文实际用量",
                    onClick = { showContextTokens = !showContextTokens })
                .padding(1.dp).padding(horizontal = 3.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val fraction = state.contextFraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                ChatContextMark(fraction, contextColor, Modifier.size(15.dp).testTag("chat-context-icon"))
                val contextText = if (showContextTokens) state.contextTokensText ?: "—/—" else state.contextUsageText
                    ?: if (fraction == null) state.contextLabel else "${(fraction * 100).toInt()}% ${state.contextLabel}"
                AnimatedContent(targetState = showContextTokens to contextText,
                    modifier = Modifier.weight(1f, fill = false), contentAlignment = Alignment.CenterEnd,
                    transitionSpec = {
                        ((fadeIn(tween(180)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 3 })
                            togetherWith (fadeOut(tween(120)) + slideOutVertically(tween(180)) { -it / 3 }))
                            .using(SizeTransform { _, _ -> tween(220, easing = FastOutSlowInEasing) })
                    }, label = "context-usage-mode") { (showTokens, text) ->
                    Text(text,
                        modifier = Modifier.testTag("chat-context-label"),
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 10.sp, lineHeight = 14.sp,
                            letterSpacing = if (showTokens) 0.3.sp else MiuixTheme.textStyles.footnote1.letterSpacing,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
                        color = contextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
                Box(Modifier.fillMaxWidth().padding(horizontal = 13.dp).height(1.dp)
                    .testTag("chat-composer-divider").background(dividerColor))
                Column(Modifier.fillMaxWidth().animateContentSize(
                    animationSpec = spring(dampingRatio = 1f, stiffness = 500f),
                    alignment = Alignment.BottomCenter,
                )) {
                    ComposerReferenceChips(references.references, state.enabled, visualEnabled) { reference ->
                        if (state.editorGeneration == currentEditorGeneration && references.source == currentDraft) {
                            val next = references.remove(reference)
                            val projected = ComposerReferences(next)
                            editReferenceDraft(next, projected.toSource(references.toVisible(state.selectionStart)),
                                projected.toSource(references.toVisible(state.selectionEnd)))
                        }
                    }
                    attachmentContent()
                    key(state.editorGeneration, actions.editingMessageKey) {
                        val mountedGeneration = state.editorGeneration
                        var editingValue by remember {
                            mutableStateOf(TextFieldValue(references.text).forComposer(references.text, references.toVisible(state.selectionStart), references.toVisible(state.selectionEnd)))
                        }
                        val renderedValue = editingValue.forComposer(references.text, references.toVisible(state.selectionStart), references.toVisible(state.selectionEnd))
                        SideEffect { if (editingValue != renderedValue) editingValue = renderedValue }
                        BasicTextField(
                            value = renderedValue,
                            onValueChange = { value ->
                                // Explicit dismissal and ON_STOP must not write back a collapsed selection.
                                if (((!lifecycleDismissInProgress && !isInputDismissInProgress() && !app.daiyu.android.ui.components.InputDismissal.inProgress) || value.text != references.text) &&
                                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                    mountedGeneration == currentEditorGeneration && state.enabled && currentEditorEnabled) {
                                    val bodyChanged = value.text != references.text
                                    val next = references.edit(value.text)
                                    fun rawOffset(offset: Int) = if (bodyChanged) offset.coerceIn(0, value.text.length) else references.toSource(offset)
                                    val rawComposition = value.composition?.let { TextRange(rawOffset(it.start), rawOffset(it.end)) }
                                    val projected = ComposerReferences(next, rawComposition?.let { it.min until it.max })
                                    val selection = TextRange(projected.toVisible(rawOffset(value.selection.start)),
                                        projected.toVisible(rawOffset(value.selection.end)))
                                    val composition = rawComposition?.let { TextRange(projected.toVisible(it.start), projected.toVisible(it.end)) }
                                    val accepted = if (!state.dictationPreview && references.references.isEmpty() && projected.references.isEmpty()) {
                                        if (next != state.text) actions.onTextChange(next)
                                        actions.onSelectionChange(rawOffset(value.selection.start), rawOffset(value.selection.end))
                                        true
                                    } else editReferenceDraft(next, projected.toSource(selection.start), projected.toSource(selection.end))
                                    if (accepted) {
                                        composingSource = rawComposition?.let { next to it }
                                        editingValue = TextFieldValue(projected.text, selection, composition)
                                        if (selection.collapsed) {
                                            ComposerShortcut.afterEdit(references.text, projected.text, selection.start,
                                                composition != null,
                                                renderedValue.composition != null && composition == null)?.let {
                                                actions.onReferenceShortcut(it, next)
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = state.enabled,
                            modifier = Modifier.testTag("chat-draft").workspacePullUpGesture(workspacePointers, openWorkspace).onFocusChanged { onInputFocusChanged(it.isFocused) }.fillMaxWidth().heightIn(min = 53.dp, max = 176.dp)
                                .padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 7.dp),
                            maxLines = 6, textStyle = MiuixTheme.textStyles.body1.copy(color = chrome.text, fontSize = 16.sp, lineHeight = 24.sp),
                            cursorBrush = SolidColor(if (WindowInsets.isImeVisible) chrome.accent else Color.Unspecified),
                            decorationBox = { inner ->
                                Box {
                                    if (references.text.isEmpty()) Text("给代鱼发消息，或使用 @ 和 / 引用", color = chrome.muted,
                                        style = MiuixTheme.textStyles.body1.copy(fontSize = 16.sp, lineHeight = 24.sp))
                                    inner()
                                }
                            },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 12.dp).heightIn(min = 36.dp).testTag("chat-composer-bottom"), contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChatIconButton(ChatPreviewIcons.Attach, "附件", { actions.onAttach(); attachments = true },
                                Modifier.size(32.dp).testTag("chat-attach"), enabled = attachmentEnabled, tint = chrome.toolbar, iconSize = 22.5.dp, visualEnabled = visualEnabled)
                            Box(Modifier.size(32.dp).testTag("chat-reasoning")
                                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    enabled = toolbarEnabled, role = Role.Button,
                                    onClickLabel = "调整思考深度",
                                    onLongClickLabel = if (state.gptSpeedMode != null && state.gptSpeedEnabled) "切换 GPT 速度" else null,
                                    onLongClick = if (state.gptSpeedMode != null && state.gptSpeedEnabled) actions.onCycleGptSpeed else null,
                                    onClick = actions.onOpenReasoning)
                                .semantics { contentDescription = "推理强度，当前为 ${state.reasoningLabel}" },
                                contentAlignment = Alignment.Center) {
                                app.daiyu.android.ui.components.ChatThinkingSpeedIcon(state.gptSpeedMode,
                                    tint = chrome.accent.copy(alpha = if (visualEnabled) 1f else .38f), modifier = Modifier.size(24.dp))
                            }
                            Box(Modifier.size(32.dp).testTag("chat-assistant").clip(CircleShape)
                                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = toolbarEnabled, role = Role.Button,
                                    onClickLabel = "选择助手", onClick = actions.onOpenAssistant)
                                .semantics { contentDescription = "选择助手，当前为 ${state.assistantLabel}" },
                                contentAlignment = Alignment.Center) {
                                AssistantAvatar(assistant, 19.dp, Modifier.testTag("chat-assistant-avatar"))
                            }
                        }
                        key(state.preparingSendKey ?: state.controlOwnerKey) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                            app.daiyu.android.balance.ui.ProviderBalanceIndicator(
                                state = balanceState, modifier = Modifier.testTag("chat-composer-provider-balance")
                                    .widthIn(max = 72.dp), animateChanges = true, iconSize = 16.dp,
                                textStyle = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 28.sp))
                            ChatIconButton(primaryIcon, primaryLabel, {
                                if (preparingSend) actions.onCancelSend()
                                else if (compressionBlocked) actions.onCompressionSendBlocked()
                                else if (!textAction) actions.onPrimaryTap()
                                else {
                                    lifecycleDismissInProgress = true
                                    try { hideChatInputIme(focus, keyboard, view) }
                                    finally { lifecycleDismissInProgress = false }
                                    if (state.phase == ComposerPhase.IDLE) actions.onSend(state.text)
                                    else actions.onSupplement(state.text)
                                }
                            }, Modifier.size(32.dp).testTag(when {
                                preparingSend -> "text-send-cancel"
                                textAction || state.phase == ComposerPhase.IDLE -> "chat-send"
                                else -> "chat-control"
                            }).then(if (preparingSend) Modifier else Modifier.controlGesture(actions, primaryEnabled)),
                                containerColor = if (compressionBlocked) MiuixTheme.colorScheme.onSurface.copy(alpha = .12f)
                                    else (if (textAction || state.phase != ComposerPhase.IDLE) appearance.appendContainer else appearance.sendContainer)
                                        .copy(alpha = if (primaryVisualEnabled || (!dark && state.phase == ComposerPhase.IDLE)) 1f else .4f),
                                enabled = primaryEnabled || compressionBlocked, containerSize = 28.dp, visualEnabled = primaryVisualEnabled,
                                tint = if (compressionBlocked) MiuixTheme.colorScheme.onSurface
                                    else if (textAction || state.phase != ComposerPhase.IDLE) appearance.appendContent else appearance.sendContent,
                                iconSize = if (preparingSend || !textAction && state.phase != ComposerPhase.IDLE) 14.dp else 16.dp,
                                onLongClick = if (preparingSend) actions.onCancelSend else if (textAction) ({}) else if (state.phase != ComposerPhase.IDLE) actions.onPrimaryLongPress else null)
                            }
                        }
                    }
                    Box {
                        SpeechMorphButton(
                            generating = state.showGenerationLoading(),
                            listening = state.listening,
                            // Closing remains gesture-enabled so a long press can say "请先挂断".
                            enabled = callUi != null || voiceMenuEnabled,
                            description = if (callUi != null) "挂断豆包实时通话"
                                else if (state.voiceMode == ChatMenuAction.VOICE_DOUBAO) "开始豆包实时通话" else null,
                            callMotion = callMotion,
                            modifier = Modifier.onGloballyPositioned { onVoiceAnchorBounds(it.boundsInRoot()) },
                            onClick = { if (callUi != null || state.voiceMode in state.voiceModes) actions.onVoice() },
                            onLongClick = if (callUi != null || voiceMenuEnabled) ({
                                val admitted = actions.onVoiceLongPress()
                                if (admitted && callUi == null) voiceModes = true
                            }) else null,
                        )
                    }
                }
        }
    }
    if (attachments) ChatAttachmentSheet({ attachments = false }, actions.onMenuAction)
    ChatVoiceModeMenu(voiceModes && voiceMenuEnabled && callUi == null, { voiceModes = false }, actions.onMenuAction, state.voiceMode, state.voiceModes)
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
internal fun ComposerChip(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, visualEnabled: Boolean = enabled) {
    val colors = MiuixTheme.colorScheme
    val chrome = chatChromeColors().copy(accent = conversationColors().accent)
    val contentColor = if (visualEnabled) chrome.toolbar else colors.disabledOnSurface
    val modelColor = chrome.accent
    Row(modifier.heightIn(min = 26.dp).clip(RoundedCornerShape(12.dp))
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClickLabel = "选择模型",
            onClick = onClick, onLongClick = onLongClick),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        ModelBrandIcon(label, Modifier.size(13.dp).alpha(if (visualEnabled) 1f else .45f), tint = if (visualEnabled) modelColor else contentColor)
        Text(label, Modifier.weight(1f, fill = false).modelNameOpticalAlignment().testTag("composer-model-label"),
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
            color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(ChatPreviewIcons.Chevron, null, Modifier.size(12.dp), tint = if (visualEnabled) chrome.muted else contentColor)
    }
}
