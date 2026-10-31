package app.daiyu.android.ui.screens.chat.conversation

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt

// This is a presentation-only contract. The owner must stop the real call synchronously in
// onHangUp, publish CLOSING, retain this session until completion, and ignore stale session keys.
enum class DoubaoCallPresentation { EXPANDED, MINI, CLOSING }
enum class DoubaoCallVisualState { CONNECTING, LISTENING, RESPONDING, MUTED, RECONNECTING, ENDED, ERROR }

data class DoubaoCallUi(
    val sessionKey: Long,
    val conversationKey: String?,
    val presentation: DoubaoCallPresentation = DoubaoCallPresentation.EXPANDED,
    val visualState: DoubaoCallVisualState = DoubaoCallVisualState.CONNECTING,
    /** Monotonic milliseconds in the SystemClock.elapsedRealtime() time base, not wall time. */
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val transcript: String = "",
    val reply: String = "",
    val reason: String? = null,
    val muted: Boolean = false,
    val speaker: Boolean = true,
    val captionsEnabled: Boolean = true,
)

data class DoubaoCallActions(
    val onExpand: () -> Unit = {},
    val onMinimize: () -> Unit = {},
    val onHangUp: () -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onToggleSpeaker: () -> Unit = {},
    val onToggleCaptions: () -> Unit = {},
    val onCloseAnimationFinished: (Long) -> Unit = {},
)

private val CallInk = Color(0xFF34313F)
private val CallMutedInk = Color(0xFF92899F)
private val CallLavender = Color(0xFFF3EFF8)
private val CallRed = Color(0xFFE77983)

/**
 * Place last in a chat-sized Box. anchorBounds and the measured composerTop are in that Box's
 * local pixel coordinates. When supplied, composerTop is the exact lower placement/drag boundary
 * (minus a 10dp gap); only the compatibility fallback estimates the composer from the anchor.
 * There is deliberately no full-screen pointer handler or scrim: chat outside the sheet stays live.
 */
@Composable
internal fun DoubaoCallOverlay(
    state: DoubaoCallUi,
    actions: DoubaoCallActions,
    anchorBounds: Rect,
    modifier: Modifier = Modifier,
    composerTop: Float? = null,
) {
    key(state.sessionKey) {
        DoubaoCallSession(state, actions, anchorBounds, modifier, composerTop)
    }
}

@Composable
private fun DoubaoCallSession(
    state: DoubaoCallUi,
    actions: DoubaoCallActions,
    anchorBounds: Rect,
    modifier: Modifier,
    composerTop: Float?,
) {
    val density = LocalDensity.current
    val layer = rememberGraphicsLayer()
    val progress = remember { Animatable(if (state.presentation == DoubaoCallPresentation.MINI) 1f else 0f) }
    var snapshotReady by remember { mutableStateOf(false) }
    // Not snapshot state: this is only a display-list recording cache, owned by the draw pass.
    val recording = remember { CallRecording() }
    var presented by remember { mutableStateOf(false) }
    var completionReported by remember { mutableStateOf(false) }
    var retainedMini by remember { mutableStateOf(state.presentation == DoubaoCallPresentation.MINI) }
    var expandedDrag by remember { mutableStateOf(Offset.Zero) }
    var miniDrag by remember { mutableStateOf(Offset.Zero) }
    val latestActions by rememberUpdatedState(actions)
    val latestState by rememberUpdatedState(state)
    val closing = state.presentation == DoubaoCallPresentation.CLOSING
    // A display-list can still reference live clip/shadow child layers. Freeze the component's
    // inputs, not just its recording, once per session; even owners without an end timestamp
    // must stop the clock and cannot change captions, status or control appearance mid-close.
    var closingPicture by remember { mutableStateOf<DoubaoCallUi?>(null) }
    if (closing && closingPicture == null) {
        closingPicture = state.copy(endedAtMillis = state.endedAtMillis ?: SystemClock.elapsedRealtime())
    }
    val picture = if (closing) checkNotNull(closingPicture) else state
    val mini = if (closing) retainedMini else state.presentation == DoubaoCallPresentation.MINI
    SideEffect {
        if (!closing) retainedMini = mini
    }

    LaunchedEffect(state.presentation) {
        val firstPresentation = !presented
        presented = true
        when (state.presentation) {
            DoubaoCallPresentation.MINI -> {
                progress.snapTo(1f)
                snapshotReady = false
            }
            DoubaoCallPresentation.EXPANDED -> {
                if (firstPresentation) {
                    // Never start the clock on an empty/unrecorded GraphicsLayer.
                    snapshotFlow { snapshotReady }.first { it }
                    progress.animateTo(1f, tween(DoubaoGenieGeometry.DURATION_MILLIS, easing = LinearEasing))
                } else {
                    // Minimize/restore is intentionally immediate, not another Genie entrance.
                    progress.snapTo(1f)
                }
            }
            DoubaoCallPresentation.CLOSING -> {
                // Capture the immutable closing picture even during entrance: the opening
                // recording may predate the final captions/status. Do not reset progress.
                snapshotReady = false
                snapshotFlow { snapshotReady }.first { it }
                val remaining = (DoubaoGenieGeometry.DURATION_MILLIS * progress.value).roundToInt()
                progress.animateTo(0f, tween(remaining.coerceAtLeast(1), easing = LinearEasing))
                // Give the p=0 surface a draw opportunity before the owner unmounts it. A zero
                // duration scale has no extra wait; both directions already honor the system scale.
                if ((coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f) withFrameNanos { }
                // No custom wall-clock animator and no delayed business hang-up live here.
                coroutineContext.ensureActive()
                if (!completionReported && latestState.sessionKey == state.sessionKey &&
                    latestState.presentation == DoubaoCallPresentation.CLOSING) {
                    completionReported = true
                    latestActions.onCloseAnimationFinished(state.sessionKey)
                }
            }
        }
    }

    BackHandler(enabled = state.presentation == DoubaoCallPresentation.EXPANDED) {
        latestActions.onMinimize()
    }
    val clock = callClock(picture)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val hostWidth = constraints.maxWidth.toFloat()
        val hostHeight = constraints.maxHeight.toFloat()
        val gutter = with(density) { 10.dp.toPx() }
        val topGutter = with(density) { 8.dp.toPx() }
        val toolbarClearance = with(density) { 120.dp.toPx() }
        val desiredHeight = with(density) { (if (mini) 67.dp else 374.dp).toPx() }
        val desiredWidth = with(density) { 250.dp.toPx() }
        val panelWidth = (hostWidth - gutter * 2).coerceAtLeast(1f).let {
            if (mini) min(desiredWidth, it) else it
        }
        // The composer and the real central entry are never covered by the resting panel.
        val bottom = ((composerTop ?: (anchorBounds.top - toolbarClearance)) - gutter)
            .coerceIn(0f, hostHeight.coerceAtLeast(0f))
        val panelHeight = min(desiredHeight, (bottom - topGutter).coerceAtLeast(1f))
        val baseLeft = (hostWidth - panelWidth) / 2f
        val baseTop = (bottom - panelHeight).coerceAtLeast(0f)
        val drag = if (mini) miniDrag else expandedDrag
        val left = (baseLeft + drag.x).coerceIn(0f, (hostWidth - panelWidth).coerceAtLeast(0f))
        val top = (baseTop + drag.y).coerceIn(0f, baseTop)
        val panelBounds = Rect(left, top, left + panelWidth, top + panelHeight)
        val morphing = closing || (!mini && progress.value < 1f)
        // The draw matrix does not transform Compose hit targets. Until it settles, neither
        // controls nor drag/scroll gestures may act at the panel's untransformed coordinates.
        val interactive = !morphing
        val canDrag = interactive
        val onDrag: (Offset) -> Unit = { delta ->
            val newLeft = (left + delta.x).coerceIn(0f, (hostWidth - panelWidth).coerceAtLeast(0f))
            val newTop = (top + delta.y).coerceIn(0f, baseTop)
            val next = Offset(newLeft - baseLeft, newTop - baseTop)
            if (mini) miniDrag = next else expandedDrag = next
        }
        val latestDrag by rememberUpdatedState(onDrag)
        val dragHandle = Modifier.pointerInput(canDrag, mini) {
            if (canDrag) detectDragGestures { change, delta ->
                change.consume()
                latestDrag(delta)
            }
        }
        val closeInput = if (closing) Modifier
            .clearAndSetSemantics { }
            .pointerInput(state.sessionKey) {
                // Consume ONLY the measured panel. Never install a full-root closing mask.
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            } else Modifier
        val shape = RoundedCornerShape(if (mini) 23.dp else 29.dp)
        Box(
            Modifier
                .offset { IntOffset(panelBounds.left.roundToInt(), panelBounds.top.roundToInt()) }
                .size(with(density) { panelWidth.toDp() }, with(density) { panelHeight.toDp() })
                .then(closeInput)
                .testTag(if (mini) "doubao-call-mini" else "doubao-call-expanded")
                .drawWithContent {
                    if (!morphing) {
                        // Stable content remains live (not a stale screenshot of a running timer).
                        drawContent()
                    } else {
                        val captureSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                        if (!snapshotReady || recording.size != captureSize) {
                            // Resize re-records once at the new layout size; it cannot reuse an
                            // invalid old texture, nor reset progress or a closing completion.
                            layer.record(size = captureSize) { this@drawWithContent.drawContent() }
                            recording.size = captureSize
                            snapshotReady = true
                        }
                        val endpointSize = with(density) { 20.dp.toPx() }
                        val anchor = DoubaoGenieGeometry.Bounds(
                            anchorBounds.center.x - endpointSize / 2f - panelBounds.left,
                            anchorBounds.center.y - endpointSize / 2f - panelBounds.top,
                            endpointSize, endpointSize,
                        )
                        val panel = DoubaoGenieGeometry.Bounds(0f, 0f, size.width, size.height)
                        DoubaoGenieGeometry.strips(progress.value, anchor, panel).forEach { strip ->
                            withTransform({
                                transform(Matrix(strip.matrix))
                                clipRect(0f, 0f, size.width, strip.sourceHeight)
                                translate(0f, -strip.sourceY)
                            }) {
                                drawLayer(layer)
                            }
                        }
                    }
                }
                .shadow(14.dp, shape, clip = false, ambientColor = Color(0xFFB7A6CE), spotColor = Color(0xFFB7A6CE))
                .clip(shape)
                .background(if (mini) Color(0xFFFCFAFF) else Color(0xFFFDFBFF))
                .border(1.dp, Color.White, shape),
        ) {
            if (mini) {
                MiniCallContent(picture, clock, interactive, dragHandle, latestActions.onExpand)
            } else {
                ExpandedCallContent(picture, clock, interactive, dragHandle, latestActions)
            }
        }
    }
}

private class CallRecording(var size: IntSize = IntSize.Zero)

@Composable
private fun callClock(state: DoubaoCallUi): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.startedAtMillis, state.endedAtMillis) {
        while (state.endedAtMillis == null) {
            now = SystemClock.elapsedRealtime()
            delay(250L)
        }
    }
    val seconds = ((state.endedAtMillis ?: now) - state.startedAtMillis).coerceAtLeast(0L) / 1000L
    return "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun DoubaoCallUi.statusDetail(): String = reason?.takeIf { it.isNotBlank() } ?: when (visualState) {
    DoubaoCallVisualState.CONNECTING -> "正在建立通话连接"
    DoubaoCallVisualState.LISTENING -> if (muted) "麦克风已关闭" else "不用着急，我一直在这里"
    DoubaoCallVisualState.RESPONDING -> "正在回应你的话"
    DoubaoCallVisualState.MUTED -> "麦克风已关闭"
    DoubaoCallVisualState.RECONNECTING -> "正在恢复通话连接"
    DoubaoCallVisualState.ENDED -> "本次通话已结束"
    DoubaoCallVisualState.ERROR -> "暂时无法继续通话"
}

private fun DoubaoCallUi.statusLabel(): String = when (visualState) {
    DoubaoCallVisualState.CONNECTING -> "正在连接…"
    DoubaoCallVisualState.LISTENING -> "我在听，你说"
    DoubaoCallVisualState.RESPONDING -> "豆包正在回应"
    DoubaoCallVisualState.MUTED -> "麦克风已静音"
    DoubaoCallVisualState.RECONNECTING -> "正在重新连接…"
    DoubaoCallVisualState.ENDED -> "通话已结束"
    DoubaoCallVisualState.ERROR -> "连接遇到问题"
}

@Composable
private fun ExpandedCallContent(
    state: DoubaoCallUi,
    clock: String,
    enabled: Boolean,
    dragHandle: Modifier,
    actions: DoubaoCallActions,
) {
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 12.dp)) {
        // The original drag zone extends 7dp into each horizontal padding; the heading adds
        // its own 7dp start inset, keeping the title aligned with the captions below.
        Column(Modifier.fillMaxWidth().height(58.dp).layout { measurable, constraints ->
            val inset = 7.dp.roundToPx()
            val child = measurable.measure(constraints.copy(
                minWidth = constraints.minWidth + inset * 2, maxWidth = constraints.maxWidth + inset * 2))
            layout(child.width - inset * 2, child.height) { child.placeRelative(-inset, 0) }
        }) {
        Box(Modifier.fillMaxWidth().height(18.dp).then(dragHandle)
            .testTag("doubao-call-drag-handle"), contentAlignment = Alignment.Center) {
            Box(Modifier.size(31.dp, 4.dp).background(Color(0xFFD9D1E3), RoundedCornerShape(2.dp)))
        }
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().then(dragHandle),
                verticalAlignment = Alignment.CenterVertically) {
                Text("豆包实时通话", color = CallInk, fontSize = 14.sp, fontWeight = FontWeight(650))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(4.dp).background(Color(0xFFAC97CD), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(5.dp))
                Text(clock, Modifier.testTag("doubao-call-clock"), color = Color(0xFFAAA1B6), fontSize = 11.sp)
            }
            Box(
                Modifier.size(40.dp).testTag("doubao-call-minimize")
                    .semantics { contentDescription = "收起通话" }
                    .clickable(enabled = enabled, role = Role.Button, onClick = actions.onMinimize),
                contentAlignment = Alignment.Center,
            ) { CallGlyph(CallControl.MINIMIZE, CallMutedInk, Modifier.size(20.dp)) }
        }
        }
        // At the original 374dp size the body is 304dp: focus 143, captions 64, controls 78,
        // and the reserved 19dp preview-notice space (no preview disclaimer in production).
        // Only this body scrolls on short screens; controls never share the drag recognizer.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val focusHeight = (maxHeight - 161.dp).coerceAtLeast(104.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = enabled)) {
            Column(Modifier.fillMaxWidth().height(focusHeight).padding(top = 3.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)) {
                // CSS's 66dp orb has an out-of-layout 7dp halo.
                Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
                    CallWave(state.visualState, Modifier.requiredSize(80.dp))
                }
                Text(state.statusLabel(), color = Color(0xFF51445F), fontSize = 19.sp, lineHeight = 28.sp,
                    fontWeight = FontWeight(550),
                    modifier = Modifier.height(28.dp).testTag("doubao-call-status"),
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.statusDetail(), color = Color(0xFFAAA0B6), fontSize = 10.sp, lineHeight = 16.sp,
                    modifier = Modifier.height(16.dp).testTag("doubao-call-status-detail"),
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.fillMaxWidth().height(64.dp).padding(bottom = 4.dp)
                .drawWithContent {
                    drawContent()
                    drawLine(Color(0x4DECE6F2), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }.padding(top = 7.dp, bottom = 5.dp).verticalScroll(rememberScrollState(), enabled = enabled)) {
                if (state.captionsEnabled) {
                    if (state.transcript.isNotBlank()) CaptionLine("我", state.transcript)
                    if (state.reply.isNotBlank()) CaptionLine("豆包", state.reply, reply = true)
                }
            }
            Row(Modifier.fillMaxWidth().height(78.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CallControlButton(CallControl.MIC, "麦克风", "doubao-call-mute", state.muted, enabled, actions.onToggleMute, Modifier.weight(1f))
                CallControlButton(CallControl.SPEAKER, "扬声器", "doubao-call-speaker", state.speaker, enabled, actions.onToggleSpeaker, Modifier.weight(1f))
                CallControlButton(CallControl.CAPTIONS, "字幕", "doubao-call-captions", state.captionsEnabled, enabled, actions.onToggleCaptions, Modifier.weight(1f))
                CallControlButton(CallControl.HANG_UP, "挂断", "doubao-call-hang-up", false, enabled, actions.onHangUp, Modifier.weight(1f))
            }
            Spacer(Modifier.height(19.dp))
            }
        }
    }
}

@Composable
private fun CaptionLine(who: String, text: String, reply: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.Top) {
        Text(who, Modifier.width(25.dp), color = Color(0xFFB4A8C3), fontSize = 10.sp, lineHeight = 20.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), color = if (reply) Color(0xFF736180) else Color(0xFF8E839C), fontSize = 11.sp, lineHeight = 20.sp,
            softWrap = true, textAlign = TextAlign.Start)
    }
}

@Composable
private fun MiniCallContent(
    state: DoubaoCallUi,
    clock: String,
    enabled: Boolean,
    dragHandle: Modifier,
    onExpand: () -> Unit,
) {
    val latestExpand by rememberUpdatedState(onExpand)
    Row(Modifier.fillMaxSize().padding(start = 13.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1f).fillMaxHeight().then(dragHandle)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { latestExpand() }
            }.testTag("doubao-call-drag-handle"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CallWave(state.visualState, Modifier.size(37.dp), compact = true)
            Column(Modifier.weight(1f)) {
                Text(state.statusLabel(), color = Color(0xFF675477), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(4.dp).background(Color(0xFFAC97CD), RoundedCornerShape(2.dp)))
                    Text(clock, Modifier.testTag("doubao-call-clock"), color = Color(0xFFB0A2BD), fontSize = 10.sp)
                    Text("豆包实时通话", color = Color(0xFFB0A2BD), fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(
            Modifier.size(44.dp).testTag("doubao-call-expand")
                .semantics { contentDescription = "展开通话" }
                .clickable(enabled = enabled, role = Role.Button, onClick = onExpand),
            contentAlignment = Alignment.Center,
        ) { CallGlyph(CallControl.EXPAND, CallMutedInk, Modifier.size(20.dp)) }
    }
}

private enum class CallControl { MIC, SPEAKER, CAPTIONS, HANG_UP, MINIMIZE, EXPAND }

@Composable
private fun CallControlButton(
    control: CallControl,
    label: String,
    tag: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val red = control == CallControl.HANG_UP
    val off = if (control == CallControl.MIC) active else !active
    Column(modifier.heightIn(min = 72.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        Box(
            Modifier.size(48.dp, 45.dp).clip(RoundedCornerShape(16.dp))
                .background(if (red) CallRed else if (off) Color(0xFFE8DFF2) else CallLavender)
                .border(1.dp, if (red) CallRed else Color(0xFFEAE3F2), RoundedCornerShape(16.dp))
                .testTag(tag)
                .semantics { contentDescription = label; selected = active }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { CallGlyph(control, if (red) Color.White else if (off) Color(0xFF79618F) else Color(0xFF9580B1), Modifier.size(23.dp), active) }
        Text(label, color = if (red) Color(0xFFB87985) else Color(0xFF8D819C), fontSize = 10.sp, lineHeight = 16.sp)
    }
}

/** The lavender five-bar orb is not the dark-purple composer/entry glyph. */
@Composable
private fun CallWave(state: DoubaoCallVisualState, modifier: Modifier, compact: Boolean = false) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val unit = size.minDimension / if (compact) 66f else 80f
            val center = this.center
            if (!compact) drawCircle(Color(if (state == DoubaoCallVisualState.MUTED) 0xFFF5F1F8 else 0xFFF6F2FC), 40f * unit, center)
            val fill = when (state) {
                DoubaoCallVisualState.RESPONDING -> Color(0xFFE9DDF9)
                DoubaoCallVisualState.MUTED -> Color(0xFFEEE9F2)
                DoubaoCallVisualState.ENDED -> Color(0xFFEFEAF4)
                else -> Color(if (compact) 0xFFEEE5F9 else 0xFFEEE6FB)
            }
            drawCircle(fill, 33f * unit, center)
            if (state == DoubaoCallVisualState.MUTED || state == DoubaoCallVisualState.ENDED) return@Canvas
            if (!compact && state in listOf(DoubaoCallVisualState.CONNECTING, DoubaoCallVisualState.RECONNECTING)) {
                val radius = 14f * unit
                drawArc(Color(0xFFD6C7E9), 0f, 360f, false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(2f * unit))
                drawArc(Color(0xFF9577BB), -90f, 90f, false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(2f * unit, cap = StrokeCap.Round))
                return@Canvas
            }
            val heights = if (state == DoubaoCallVisualState.RESPONDING) listOf(14f, 27f, 34f, 23f, 12f)
                else listOf(12f, 21f, 29f, 20f, 11f)
            heights.forEachIndexed { index, height ->
                val x = center.x + (index - 2) * 6f * unit
                drawLine(Color(0xFFA18AC7), Offset(x, center.y - (height - 3f) * unit / 2),
                    Offset(x, center.y + (height - 3f) * unit / 2), 3f * unit, StrokeCap.Round)
            }
        }
        val iconSize = if (compact) 18.dp else 25.dp
        when (state) {
            DoubaoCallVisualState.MUTED -> CallGlyph(CallControl.MIC, Color(0xFFB1A4BD), Modifier.size(iconSize), active = true)
            DoubaoCallVisualState.ENDED -> CallGlyph(CallControl.HANG_UP, Color(0xFFAD9CBA), Modifier.size(iconSize))
            else -> Unit
        }
    }
}

/** Tiny native line icons keep the panel self-contained; no web content or extra icon dependency. */
@Composable
private fun CallGlyph(control: CallControl, color: Color, modifier: Modifier, active: Boolean = false) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) {
            val line = 1.8f
            when (control) {
                CallControl.MIC -> {
                    drawRoundRect(color, Offset(9f, 3f), androidx.compose.ui.geometry.Size(6f, 11f),
                        androidx.compose.ui.geometry.CornerRadius(3f), style = Stroke(line))
                    val p = Path().apply { moveTo(6f, 10f); cubicTo(6f, 19f, 18f, 19f, 18f, 10f) }
                    drawPath(p, color, style = Stroke(line, cap = StrokeCap.Round))
                    drawLine(color, Offset(12f, 17f), Offset(12f, 21f), line, StrokeCap.Round)
                    drawLine(color, Offset(9f, 21f), Offset(15f, 21f), line, StrokeCap.Round)
                    if (active) drawLine(color, Offset(3f, 3f), Offset(21f, 21f), line, StrokeCap.Round)
                }
                CallControl.SPEAKER -> {
                    val p = Path().apply {
                        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
                        lineTo(7f, 15f); lineTo(3f, 15f); close()
                    }
                    drawPath(p, color, style = Stroke(line, cap = StrokeCap.Round))
                    val wave = Path().apply { moveTo(16f, 8f); cubicTo(20f, 10f, 20f, 14f, 16f, 16f) }
                    if (active) drawPath(wave, color, style = Stroke(line, cap = StrokeCap.Round))
                    else drawLine(color, Offset(16f, 9f), Offset(21f, 16f), line, StrokeCap.Round)
                }
                CallControl.CAPTIONS -> {
                    drawRoundRect(color, Offset(3f, 5f), androidx.compose.ui.geometry.Size(18f, 14f),
                        androidx.compose.ui.geometry.CornerRadius(4f), style = Stroke(line))
                    drawLine(color, Offset(7f, 10f), Offset(17f, 10f), line, StrokeCap.Round)
                    drawLine(color, Offset(7f, 14f), Offset(14f, 14f), line, StrokeCap.Round)
                }
                CallControl.HANG_UP -> {
                    val p = Path().apply {
                        moveTo(3f, 13f); cubicTo(7f, 7f, 17f, 7f, 21f, 13f)
                        lineTo(18f, 16f); lineTo(15f, 14f); lineTo(15f, 12f)
                        cubicTo(13f, 11f, 11f, 11f, 9f, 12f)
                        lineTo(9f, 14f); lineTo(6f, 16f); close()
                    }
                    drawPath(p, color)
                }
                CallControl.MINIMIZE -> {
                    drawLine(color, Offset(5f, 9f), Offset(12f, 16f), line, StrokeCap.Round)
                    drawLine(color, Offset(12f, 16f), Offset(19f, 9f), line, StrokeCap.Round)
                }
                CallControl.EXPAND -> {
                    drawLine(color, Offset(5f, 15f), Offset(12f, 8f), line, StrokeCap.Round)
                    drawLine(color, Offset(12f, 8f), Offset(19f, 15f), line, StrokeCap.Round)
                }
            }
        }
    }
}
