package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.icons.ChatPreviewIcons
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.ExperimentalFoundationApi
import app.daiyu.android.ui.haptics.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChatTopBar(title: String, actions: ChatScreenActions, modifier: Modifier = Modifier, assistantLabel: String = "助手",
    interactiveModeEnabled: Boolean = false, interactiveModeChangeEnabled: Boolean = true,
    automaticCompression: Boolean? = null, automaticCompressionPending: Boolean = false, modelLabel: String = "",
    readAutomaticCompression: (() -> ChatAutomaticCompressionState)? = null, menuAvailable: Boolean = true) {
    var expanded by remember(menuAvailable) { mutableStateOf(false) }
    val chrome = chatChromeColors()
    Row(modifier.testTag("chat-top-bar").fillMaxWidth().heightIn(min = 64.dp).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatIconButton(ChatPreviewIcons.Menu, "打开侧边栏", { expanded = false; actions.onOpenSidebar() },
            Modifier.size(40.dp), tint = chrome.text, iconSize = 22.dp)
        val titleBlockHeight = with(LocalDensity.current) { 24.sp.toDp() + 18.sp.toDp() } + 4.dp
        Column(Modifier.weight(1f).heightIn(min = titleBlockHeight)) {
            Text(title.ifBlank { "新会话" }, Modifier.testTag("chat-title"),
                color = chrome.text,
                style = MiuixTheme.textStyles.title3.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight(650)),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(listOf(assistantLabel, modelLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    modifier = Modifier.weight(1f, fill = false).testTag("chat-subtitle"), color = chrome.muted,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ChatIconButton(ChatPreviewIcons.SubAgents, "本会话协作", { expanded = false; actions.onMenuAction(ChatMenuAction.COLLABORATION) }, modifier = Modifier.size(40.dp), tint = assistantPreviewPalette().primary, iconSize = 22.dp)
        Box {
            val moreShape = RoundedCornerShape(10.dp)
            ChatIconButton(ChatPreviewIcons.More, "更多", { actions.onMore(); expanded = true },
                Modifier.size(40.dp).semantics { selected = expanded },
                tint = if (expanded) chrome.accent else chrome.text, iconSize = 22.dp, shape = moreShape, indication = null, enabled = menuAvailable)
            if (menuAvailable) ChatOverflowMenu(expanded, { expanded = false }, actions.onMenuAction,
                interactiveModeEnabled, interactiveModeChangeEnabled, automaticCompression, automaticCompressionPending,
                readAutomaticCompression = readAutomaticCompression)
        }
    }
}

internal class ChatActionMotion(
    val interactionSource: MutableInteractionSource,
    private val pressed: State<Boolean>,
    private val heldScale: State<Float>,
    private val scope: CoroutineScope,
) {
    private val feedbackScale = Animatable(1f)
    private var feedbackJob: Job? = null
    private var playingFeedback by mutableStateOf(false)

    val scale: Float
        get() = if (playingFeedback) {
            if (pressed.value) minOf(heldScale.value, feedbackScale.value) else feedbackScale.value
        } else heldScale.value

    fun onAction() {
        val start = scale
        feedbackJob?.cancel()
        val job = scope.launch {
            feedbackScale.snapTo(start)
            playingFeedback = true
            feedbackScale.animateTo(.84f, tween(70))
            feedbackScale.animateTo(1f, spring(dampingRatio = .6f, stiffness = 900f))
        }
        feedbackJob = job
        job.invokeOnCompletion { if (feedbackJob === job) playingFeedback = false }
    }
}

@Composable
internal fun rememberChatActionMotion(): ChatActionMotion {
    val source = remember { MutableInteractionSource() }
    val pressed = source.collectIsPressedAsState()
    val heldScale = animateFloatAsState(
        targetValue = if (pressed.value) .84f else 1f,
        animationSpec = if (pressed.value) tween(75) else spring(dampingRatio = .6f, stiffness = 900f),
        label = "chat-action-press",
    )
    val scope = rememberCoroutineScope()
    return remember(source, scope) { ChatActionMotion(source, pressed, heldScale, scope) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color? = null,
    iconSize: androidx.compose.ui.unit.Dp? = null,
    containerSize: androidx.compose.ui.unit.Dp = 20.dp,
    shape: Shape = CircleShape,
    indication: Indication? = LocalIndication.current,
    visualEnabled: Boolean = enabled,
    animateChanges: Boolean = false,
    motion: ChatActionMotion? = null,
    iconCrossfade: Boolean = true,
) {
    val actionMotion = if (animateChanges) motion ?: rememberChatActionMotion() else null
    val interactionSource = actionMotion?.interactionSource ?: remember { MutableInteractionSource() }
    Box(modifier.size(48.dp).clip(shape)
        .then(if (animateChanges) Modifier.semantics { contentDescription = label } else Modifier)
        .combinedClickable(
        enabled = enabled, role = Role.Button, onClickLabel = label,
        interactionSource = interactionSource, indication = indication,
        onLongClick = onLongClick?.let { callback -> { actionMotion?.onAction(); callback() } },
        onClick = { actionMotion?.onAction(); onClick() },
    ), contentAlignment = Alignment.Center) {
        Box(Modifier.then(if (actionMotion == null) Modifier else Modifier.graphicsLayer {
            scaleX = actionMotion.scale
            scaleY = actionMotion.scale
        }).then(if (containerColor == null) Modifier else Modifier.size(containerSize).background(containerColor, CircleShape)),
            contentAlignment = Alignment.Center) {
            val imageSize = iconSize ?: if (containerColor == null) 20.dp else 12.dp
            if (animateChanges) {
                AnimatedContent(icon to iconCrossfade, modifier = Modifier.testTag("chat-action-icon"), contentAlignment = Alignment.Center,
                    contentKey = { it.first },
                    transitionSpec = {
                        if (initialState.second && targetState.second) {
                            ((fadeIn(tween(200)) + scaleIn(tween(200), initialScale = .72f)) togetherWith
                                (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = .72f))).using(null)
                        } else {
                            scaleIn(tween(200), initialScale = .72f).togetherWith(
                                androidx.compose.animation.ExitTransition.None).using(null)
                        }
                    }, label = "chat-action-icon") { image ->
                    Icon(image.first, contentDescription = null, modifier = Modifier.size(imageSize), tint = if (visualEnabled) tint else tint.copy(alpha = 0.38f))
                }
            } else {
                Icon(icon, contentDescription = label, modifier = Modifier.size(imageSize), tint = if (visualEnabled) tint else tint.copy(alpha = 0.38f))
            }
        }
    }
}
