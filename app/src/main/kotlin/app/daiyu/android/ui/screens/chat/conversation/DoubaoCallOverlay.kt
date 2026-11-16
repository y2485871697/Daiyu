package app.daiyu.android.ui.screens.chat.conversation

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.haptics.combinedClickable
import app.daiyu.android.ui.haptics.hapticClick
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
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
enum class DoubaoCallVisualState { LISTENING, RESPONDING, MUTED, ENDED, ERROR }

data class DoubaoCallUi(
    val sessionKey: Long,
    val conversationKey: String?,
    val presentation: DoubaoCallPresentation = DoubaoCallPresentation.EXPANDED,
    val visualState: DoubaoCallVisualState = DoubaoCallVisualState.LISTENING,
    /** Monotonic milliseconds in the SystemClock.elapsedRealtime() time base, not wall time. */
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val transcript: String = "",
    val reply: String = "",
    val reason: String? = null,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val captionsEnabled: Boolean = true,
    val title: String = "豆包实时通话",
    val modelSelectable: Boolean = false,
    val compressing: Boolean = false,
    val compressionCompletion: String? = null,
    val searching: Boolean = false,
    val searchCompletion: String? = null,
    val responseLabel: String = "豆包",
    val captionEpoch: Long = 0,
    val errorTitle: String = "连接遇到问题",
    val replyTextComplete: Boolean = false,
    val transcriptTextComplete: Boolean = false,
    val browsing: Boolean = false,
    val skipEntryAnimation: Boolean = false,
)

data class DoubaoCallActions(
    val onSelectModel: () -> Unit = {},
    val onModelLongPress: (() -> Unit)? = null,
    val onExpand: () -> Unit = {},
    val onMinimize: () -> Unit = {},
    val onHangUp: () -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onToggleSpeaker: () -> Unit = {},
    val onToggleCaptions: () -> Unit = {},
    val onCloseAnimationFinished: (Long) -> Unit = {},
    val onPresentationReady: ((Long) -> Unit)? = null,
)

internal class DoubaoCallMotionState {
    val progress = Animatable(0f)
    var transforming by mutableStateOf(true)
}

// Compose 1.9's API 26–27 ImageReader snapshot treats premultiplied RGBA as straight ARGB.
// Undo that extra multiplication once per capture, never on each animation frame.
internal fun ImageBitmap.restoreLegacyCallCaptureAlpha(): ImageBitmap {
    if (android.os.Build.VERSION.SDK_INT >= 28) return this
    val source = asAndroidBitmap()
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (index in pixels.indices) {
        val color = pixels[index]
        val alpha = color ushr 24
        if (alpha == 0 || alpha == 255) continue
        fun restore(channel: Int) = ((channel * 255 + alpha / 2) / alpha).coerceAtMost(255)
        pixels[index] = (alpha shl 24) or
            (restore((color ushr 16) and 255) shl 16) or
            (restore((color ushr 8) and 255) shl 8) or restore(color and 255)
    }
    return android.graphics.Bitmap.createBitmap(pixels, source.width, source.height,
        android.graphics.Bitmap.Config.ARGB_8888).apply { density = source.density }.asImageBitmap()
}

private val CallShadowOutset = 28.dp
private val CallMotionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// Keep the software shadow out of GraphicsLayer's lossy API 26 readback.
private fun Density.createCallPanelShadow(size: IntSize, cornerRadius: Dp): ImageBitmap {
    val padding = CallShadowOutset.roundToPx()
    val inset = padding.toFloat()
    val bitmap = android.graphics.Bitmap.createBitmap(
        size.width + padding * 2, size.height + padding * 2,
        android.graphics.Bitmap.Config.ARGB_8888,
    ).apply { density = android.graphics.Bitmap.DENSITY_NONE }
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val radius = minOf(cornerRadius.toPx(), size.width / 2f, size.height / 2f)
    fun layer(blur: Dp, offset: Dp, alpha: Float) {
        paint.color = android.graphics.Color.argb((alpha * 255).roundToInt(), 183, 166, 206)
        paint.maskFilter = android.graphics.BlurMaskFilter(blur.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
        val y = inset + offset.toPx()
        canvas.drawRoundRect(inset, y, inset + size.width, y + size.height, radius, radius, paint)
    }
    layer(14.dp, 4.dp, .24f)
    layer(7.dp, 0.dp, .12f)
    return bitmap.asImageBitmap()
}

private fun ImageBitmap.withCallPanelShadow(shadow: ImageBitmap): ImageBitmap {
    val source = asAndroidBitmap()
    val content = if (source.config == android.graphics.Bitmap.Config.HARDWARE)
        checkNotNull(source.copy(android.graphics.Bitmap.Config.ARGB_8888, false)) else source
    val bitmap = android.graphics.Bitmap.createBitmap(shadow.width, shadow.height,
        android.graphics.Bitmap.Config.ARGB_8888).apply { density = android.graphics.Bitmap.DENSITY_NONE }
    android.graphics.Canvas(bitmap).apply {
        drawBitmap(shadow.asAndroidBitmap(), 0f, 0f, null)
        drawBitmap(content, (shadow.width - content.width) / 2f, (shadow.height - content.height) / 2f, null)
    }
    return bitmap.asImageBitmap()
}


private val CallInk = Color(0xFF34313F)
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
    motion: DoubaoCallMotionState? = null,
) {
    key(state.sessionKey) {
        val fallbackMotion = remember { DoubaoCallMotionState() }
        DoubaoCallSession(state, actions, anchorBounds, modifier, composerTop, motion ?: fallbackMotion)
    }
}

@Composable
private fun DoubaoCallSession(
    state: DoubaoCallUi,
    actions: DoubaoCallActions,
    anchorBounds: Rect,
    modifier: Modifier,
    composerTop: Float?,
    motion: DoubaoCallMotionState,
) {
    val density = LocalDensity.current
    val presentationView = androidx.compose.ui.platform.LocalView.current
    val captionScroll = rememberScrollState()
    val captionFollow = remember { CallCaptionFollow() }
    LaunchedEffect(state.captionEpoch, state.captionsEnabled) {
        captionFollow.followTail = true
    }
    val compressionState = rememberVoiceCallCompressionState(state.compressing, state.compressionCompletion,
        state.presentation != DoubaoCallPresentation.CLOSING &&
            state.visualState !in setOf(DoubaoCallVisualState.ERROR, DoubaoCallVisualState.ENDED), searching = state.searching,
        searchCompletion = state.searchCompletion, resetKey = state.captionEpoch, browsing = state.browsing)
    val layer = rememberGraphicsLayer()
    val progress = motion.progress
    var snapshotReady by remember { mutableStateOf(false) }
    var recordedShadow by remember { mutableStateOf<ImageBitmap?>(null) }
    var frozenBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    // Keep the last live surface for closing; a morph reuses one padded raster.
    var presented by remember { mutableStateOf(false) }
    var completionReported by remember { mutableStateOf(false) }
    var retainedMini by remember { mutableStateOf(state.presentation == DoubaoCallPresentation.MINI) }
    var expandedDrag by remember { mutableStateOf(Offset.Zero) }
    var miniDrag by remember { mutableStateOf(Offset.Zero) }
    val latestActions by rememberUpdatedState(actions)
    val latestState by rememberUpdatedState(state)
    val closing = state.presentation == DoubaoCallPresentation.CLOSING
    // 保留关闭前的状态和字幕，避免 CLOSING 同时带来的 ENDED 或新字幕改变退场内容。
    // 子图层仍可变化，退场画面由下方栅格快照固定。
    var lastLivePicture by remember { mutableStateOf(state) }
    var closingPicture by remember { mutableStateOf<DoubaoCallUi?>(null) }
    if (closing && closingPicture == null) {
        closingPicture = lastLivePicture.copy(endedAtMillis = SystemClock.elapsedRealtime())
    }
    val picture = if (closing) checkNotNull(closingPicture) else state
    val mini = if (closing) retainedMini else state.presentation == DoubaoCallPresentation.MINI
    val captionsRunning = !closing && !mini && state.captionsEnabled
    val captions = key(picture.captionEpoch) {
        rememberCallCaptionReveal(picture.transcript, picture.transcriptTextComplete, captionsRunning) to
            rememberCallCaptionReveal(picture.reply, picture.replyTextComplete, captionsRunning)
    }
    SideEffect {
        if (!closing) {
            retainedMini = mini
            lastLivePicture = state
            closingPicture = null
        }
    }

    LaunchedEffect(state.presentation) {
        val firstPresentation = !presented
        presented = true
        when (state.presentation) {
            DoubaoCallPresentation.MINI -> {
                motion.transforming = false
                frozenBitmap = null
                progress.snapTo(1f)
            }
            DoubaoCallPresentation.EXPANDED -> {
                frozenBitmap = null
                if (firstPresentation && !state.skipEntryAnimation) {
                    motion.transforming = true
                    // Never start the clock on an empty/unrecorded GraphicsLayer.
                    snapshotFlow { snapshotReady }.first { it }
                    val shadow = checkNotNull(recordedShadow)
                    val content = layer.toImageBitmap()
                    coroutineContext.ensureActive()
                    val bitmap = content.restoreLegacyCallCaptureAlpha().withCallPanelShadow(shadow)
                    coroutineContext.ensureActive()
                    frozenBitmap = bitmap
                    progress.animateTo(1f, tween(DoubaoGenieGeometry.DURATION_MILLIS, easing = CallMotionEasing))
                } else {
                    // Minimize/restore is intentionally immediate, not another Genie entrance.
                    progress.snapTo(1f)
                }
                motion.transforming = false
                frozenBitmap = null
            }
            DoubaoCallPresentation.CLOSING -> {
                motion.transforming = true
                // A display list can reference mutable child layers. Rasterize the last recorded
                // live frame BEFORE reversing; never record the owner's CLOSING/ENDED content.
                // If closed before the first draw, the draw pass supplies one fallback recording.
                snapshotFlow { snapshotReady }.first { it }
                if (frozenBitmap == null) {
                    val shadow = checkNotNull(recordedShadow)
                    val content = layer.toImageBitmap()
                    coroutineContext.ensureActive()
                    val bitmap = content.restoreLegacyCallCaptureAlpha().withCallPanelShadow(shadow)
                    coroutineContext.ensureActive()
                    frozenBitmap = bitmap
                }
                val remaining = (DoubaoGenieGeometry.DURATION_MILLIS * progress.value).roundToInt()
                progress.animateTo(0f, tween(remaining.coerceAtLeast(1), easing = CallMotionEasing))
                // Give the p=0 surface a draw opportunity before the owner unmounts it. A zero
                // duration scale has no extra wait; both directions already honor the system scale.
                if ((coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f) withFrameNanos { }
                
                coroutineContext.ensureActive()
                if (!completionReported && latestState.sessionKey == state.sessionKey &&
                    latestState.presentation == DoubaoCallPresentation.CLOSING) {
                    completionReported = true
                    motion.transforming = false
                    latestActions.onCloseAnimationFinished(state.sessionKey)
                }
            }
        }
    }

    DisposableEffect(motion) {
        onDispose { motion.transforming = false }
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
        val morphing = closing || (!mini && motion.transforming)
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
        val cornerRadius = if (mini) 23.dp else 29.dp
        val shape = RoundedCornerShape(cornerRadius)
        val panelSize = IntSize(panelWidth.roundToInt(), panelHeight.roundToInt())
        val shadow = remember(panelSize, cornerRadius, density) {
            with(density) { createCallPanelShadow(panelSize, cornerRadius) }
        }
        Box(
            Modifier
                .offset { IntOffset(panelBounds.left.roundToInt(), panelBounds.top.roundToInt()) }
                .size(with(density) { panelWidth.toDp() }, with(density) { panelHeight.toDp() })
                .then(closeInput)
                .testTag(if (mini) "doubao-call-mini" else "doubao-call-expanded")
                .drawWithContent {
                    if (size.width <= 0f || size.height <= 0f) return@drawWithContent
                    val outset = CallShadowOutset.roundToPx()
                    val padding = outset.toFloat()
                    val captureSize = IntSize(size.width.roundToInt() + outset * 2,
                        size.height.roundToInt() + outset * 2)
                    if (!morphing || !snapshotReady) {
                        layer.record(size = IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                            this@drawWithContent.drawContent()
                        }
                        recordedShadow = shadow
                        snapshotReady = true
                    }
                    // Preserve the last visible frame while the closing raster is prepared.
                    if (!morphing || (closing && frozenBitmap == null && progress.value == 1f)) {
                        drawImage(checkNotNull(recordedShadow), topLeft = Offset(-padding, -padding))
                        drawLayer(layer)
                        if (!closing && !morphing && progress.value == 1f) {
                            latestActions.onPresentationReady?.let { ready ->
                                presentationView.post { ready(state.sessionKey) }
                            }
                        }
                    } else {
                        // Never replay the full live display list once per strip.
                        val bitmap = frozenBitmap ?: return@drawWithContent
                        // At full size, composite the translucent raster once, without strip clips.
                        if (progress.value == 1f) {
                            drawImage(bitmap, dstOffset = IntOffset(-outset, -outset), dstSize = captureSize)
                            return@drawWithContent
                        }
                        val sourceWidth = bitmap.width.toFloat()
                        val sourceHeight = bitmap.height.toFloat()
                        val endpointSize = with(density) { 20.dp.toPx() }
                        val anchor = DoubaoGenieGeometry.Bounds(
                            anchorBounds.center.x - endpointSize / 2f - panelBounds.left,
                            anchorBounds.center.y - endpointSize / 2f - panelBounds.top,
                            endpointSize, endpointSize,
                        ).outset(padding * endpointSize / size.width, padding * endpointSize / size.height)
                        val panel = DoubaoGenieGeometry.Bounds(0f, 0f, size.width, size.height).outset(padding)
                        DoubaoGenieGeometry.strips(progress.value, anchor, panel, sourceWidth, sourceHeight).forEach { strip ->
                            withTransform({
                                transform(Matrix(strip.matrix))
                                clipRect(0f, 0f, sourceWidth, strip.sourceHeight)
                                translate(0f, -strip.sourceY)
                            }) {
                                drawImage(bitmap)
                            }
                        }
                    }
                }
                .clip(shape)
                .background(if (mini) Color(0xFFFCFAFF) else Color(0xFFFDFBFF))
                .border(1.dp, Color.White, shape),
        ) {
            if (mini) {
                MiniCallContent(picture, clock, interactive, dragHandle, latestActions.onExpand, running = !closing && !morphing, compressionState = compressionState)
            } else {
                ExpandedCallContent(picture, clock, interactive, dragHandle, latestActions, running = !closing && !morphing, compressionState = compressionState, captionScroll = captionScroll, captionFollow = captionFollow, captions = captions)
            }
        }
    }
}

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

private fun DoubaoCallUi.statusDetail(): String = if (compressing) "压缩完成后自动恢复麦克风" else if (searching) "正在查询相关信息" else if (browsing) "正在浏览网页" else reason?.takeIf { it.isNotBlank() } ?: when (visualState) {
    DoubaoCallVisualState.LISTENING -> if (muted) "麦克风已关闭" else "不用着急，我一直在这里"
    DoubaoCallVisualState.RESPONDING -> "正在回应你的话"
    DoubaoCallVisualState.MUTED -> "麦克风已关闭"
    DoubaoCallVisualState.ENDED -> "本次通话已结束"
    DoubaoCallVisualState.ERROR -> "暂时无法继续通话"
}

private fun DoubaoCallUi.statusLabel(dots: Int): String = if (compressing) "正在压缩" + ".".repeat(dots)
    else if (searching) "正在搜索" + ".".repeat(dots) else if (browsing) "正在浏览" + ".".repeat(dots) else when (visualState) {
    DoubaoCallVisualState.LISTENING -> "我在听，你说"
    DoubaoCallVisualState.RESPONDING -> if (modelSelectable) "正在回应" else "豆包正在回应"
    DoubaoCallVisualState.MUTED -> "麦克风已静音"
    DoubaoCallVisualState.ENDED -> "通话已结束"
    DoubaoCallVisualState.ERROR -> errorTitle
}

@Composable
private fun ExpandedCallContent(
    state: DoubaoCallUi,
    clock: String,
    enabled: Boolean,
    dragHandle: Modifier,
    actions: DoubaoCallActions,
    running: Boolean,
    compressionState: VoiceCallCompressionState,
    captionScroll: androidx.compose.foundation.ScrollState,
    captionFollow: CallCaptionFollow,
    captions: Pair<State<String>, State<String>>,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val bottomPadding = if (maxHeight < 312.dp) 4.dp else 12.dp
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = bottomPadding)) {
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
            Row(Modifier.weight(1f).fillMaxHeight().then(if (state.modelSelectable) Modifier else dragHandle),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.testTag("voice-call-select-model").then(if (state.modelSelectable)
                    Modifier.combinedClickable(enabled = enabled, role = Role.Button, onClick = actions.onSelectModel,
                        onLongClickLabel = "调整思考深度", onLongClick = actions.onModelLongPress) else Modifier),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(state.title, Modifier.testTag("voice-call-model-title"), color = CallInk,
                        fontSize = 14.sp, fontWeight = FontWeight(650))
                    if (state.modelSelectable) Canvas(Modifier.padding(start = 4.dp).size(8.dp)
                        .testTag("voice-call-model-chevron")) {
                        val left = Offset(size.width * .15f, size.height * .35f)
                        val middle = Offset(size.width * .5f, size.height * .65f)
                        val right = Offset(size.width * .85f, size.height * .35f)
                        drawLine(CallInk, left, middle, 1.dp.toPx(), StrokeCap.Round)
                        drawLine(CallInk, middle, right, 1.dp.toPx(), StrokeCap.Round)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(4.dp).background(Color(0xFFAC97CD), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(5.dp))
                Text(clock, Modifier.testTag("doubao-call-clock"), color = Color(0xFFAAA1B6), fontSize = 11.sp)
            }
            if (state.modelSelectable || state.compressing || state.searching || state.browsing || compressionState.success) VoiceCallCompressionIndicator(state.compressing || state.searching || state.browsing, state.compressionCompletion, running, compressionState)
            Box(
                Modifier.size(40.dp).testTag("doubao-call-minimize")
                    .semantics { contentDescription = "收起通话" }
                    .clickable(enabled = enabled, role = Role.Button, onClick = actions.onMinimize),
                contentAlignment = Alignment.Center,
            ) { CallGlyph(CallControl.MINIMIZE, Color(0xFFA59AB4), Modifier.size(20.dp)) }
        }
        }
        // Preserve the 374dp layout; compact only the fixed focus spacing on short screens.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val controlsHeight = if (maxHeight < 250.dp) 72.dp else 78.dp
            val captionHeight = (maxHeight - controlsHeight - 142.dp).coerceIn(36.dp, 80.dp)
            val focusHeight = (maxHeight - controlsHeight - captionHeight).coerceAtLeast(0.dp)
            val compact = focusHeight < 142.dp
            Column(Modifier.fillMaxSize().testTag("doubao-call-body")) {
            Column(Modifier.fillMaxWidth().height(focusHeight)
                .padding(top = if (compact) 0.dp else 8.dp, bottom = if (compact) 0.dp else 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                // CSS's 66dp orb has an out-of-layout 7dp halo.
                Box(Modifier.fillMaxWidth().height(if (compact) 80.dp else 66.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(66.dp).testTag("doubao-call-orb"), contentAlignment = Alignment.Center) {
                        CallWave(state.orbState(), Modifier.requiredSize(80.dp), running = running)
                    }
                }
                Spacer(Modifier.height(if (compact) 0.dp else 13.dp))
                Text(state.statusLabel(compressionState.dots), color = Color(0xFF51445F), fontSize = 19.sp, lineHeight = 28.sp,
                    fontWeight = FontWeight(550),
                    modifier = Modifier.height(28.dp).testTag("doubao-call-status"),
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(if (compact) 0.dp else 5.dp))
                Text(state.statusDetail(), color = Color(0xFFAAA0B6), fontSize = 10.sp, lineHeight = 16.sp,
                    modifier = Modifier.height(16.dp).testTag("doubao-call-status-detail"),
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CallCaptionArea(state, enabled, captionScroll, captionFollow, captions, Modifier.height(captionHeight))
            Row(Modifier.fillMaxWidth().height(controlsHeight).testTag("doubao-call-controls"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CallControlButton(CallControl.MIC, "麦克风", "doubao-call-mute", state.muted, enabled, actions.onToggleMute, Modifier.weight(1f))
                CallControlButton(CallControl.SPEAKER, "扬声器", "doubao-call-speaker", state.speaker, enabled, actions.onToggleSpeaker, Modifier.weight(1f))
                CallControlButton(CallControl.CAPTIONS, "字幕", "doubao-call-captions", state.captionsEnabled, enabled, actions.onToggleCaptions, Modifier.weight(1f))
                CallControlButton(CallControl.HANG_UP, "挂断", "doubao-call-hang-up", false, enabled, actions.onHangUp, Modifier.weight(1f))
            }
            }
        }
    }
    }
}

private class CallCaptionFollow {
    var followTail by mutableStateOf(true)
    var dragging by mutableStateOf(false)
    var touching by mutableStateOf(false)
    var userScroll by mutableStateOf(false)
}

@Composable
private fun CallCaptionArea(state: DoubaoCallUi, enabled: Boolean,
    scroll: androidx.compose.foundation.ScrollState, follow: CallCaptionFollow,
    captions: Pair<State<String>, State<String>>, modifier: Modifier) {
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> { follow.dragging = true; follow.userScroll = true; follow.followTail = false }
                is DragInteraction.Stop, is DragInteraction.Cancel -> follow.dragging = false
            }
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { Triple(scroll.value, scroll.isScrollInProgress, follow.dragging || follow.touching) }.collect { (value, moving, held) ->
            if (follow.userScroll && !moving && !held) {
                follow.followTail = value == scroll.maxValue
                follow.userScroll = false
            }
        }
    }
    LaunchedEffect(scroll, state.sessionKey, state.captionEpoch, state.captionsEnabled, enabled) {
        snapshotFlow { Triple(scroll.maxValue, follow.followTail, follow.dragging || follow.touching) }.collectLatest { (end, following, held) ->
            if (enabled && state.captionsEnabled && following && !held) scroll.scrollTo(end)
        }
    }
    val containment = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, available.y)
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, available.y)
        }
    }
    Column(modifier.fillMaxWidth().testTag("doubao-call-caption-area").padding(bottom = 4.dp)
        .drawWithContent {
            drawContent()
            drawLine(Color(0x4DECE6F2), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }.pointerInput(follow, enabled, state.captionsEnabled) {
            if (!enabled || !state.captionsEnabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                follow.touching = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                    } while (event.changes.any { it.pressed })
                } finally {
                    follow.touching = false
                }
            }
        }.padding(top = 7.dp, bottom = 5.dp).nestedScroll(containment).verticalScroll(scroll, enabled = enabled)) {
        if (state.captionsEnabled) {
            if (state.transcript.isNotBlank()) CaptionLine("我", captions.first)
            if (state.reply.isNotBlank()) CaptionLine(state.responseLabel, captions.second, reply = true)
        }
    }
}

@Composable
private fun CaptionLine(who: String, text: State<String>, reply: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.Top) {
        Text(who, Modifier.width(25.dp), color = Color(0xFFB4A8C3), fontSize = 10.sp, lineHeight = 20.sp)
        Spacer(Modifier.width(8.dp))
        Text(text.value, Modifier.weight(1f).testTag(if (reply) "call-caption-reply" else "call-caption-transcript"), color = if (reply) Color(0xFF736180) else Color(0xFF8E839C), fontSize = 11.sp, lineHeight = 20.sp,
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
    running: Boolean,
    compressionState: VoiceCallCompressionState,
) {
    val latestExpand by rememberUpdatedState(hapticClick(enabled, onExpand))
    Row(Modifier.fillMaxSize().padding(start = 13.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1f).fillMaxHeight().then(dragHandle)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { latestExpand() }
            }.testTag("doubao-call-drag-handle"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            CallWave(state.orbState(), Modifier.size(37.dp), compact = true, running = running)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(state.statusLabel(compressionState.dots), color = Color(0xFF675477), fontSize = 12.sp, fontWeight = FontWeight(550), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(4.dp).background(Color(0xFFAE95CA), RoundedCornerShape(2.dp)))
                    Text(clock, Modifier.testTag("doubao-call-clock"), color = Color(0xFFB0A2BD), fontSize = 10.sp)
                    if (state.compressing || state.searching || state.browsing || compressionState.success)
                        VoiceCallCompressionIndicator(state.compressing || state.searching || state.browsing, state.compressionCompletion, running, compressionState)
                    else Text(state.title, color = Color(0xFFB0A2BD), fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(
            Modifier.size(44.dp).testTag("doubao-call-expand")
                .semantics { contentDescription = "展开通话" }
                .clickable(enabled = enabled, role = Role.Button, onClick = onExpand),
            contentAlignment = Alignment.Center,
        ) { CallGlyph(CallControl.EXPAND, Color(0xFFA391B7), Modifier.size(19.dp)) }
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

private fun DoubaoCallUi.orbState(): DoubaoCallVisualState =
    if (muted && visualState == DoubaoCallVisualState.LISTENING)
        DoubaoCallVisualState.MUTED else visualState

/** 按预览的 .voice-orb/.mini-orb 绘制语音球，包含两道细边和内侧高光。 */
@Composable
internal fun CallWave(
    state: DoubaoCallVisualState,
    modifier: Modifier,
    compact: Boolean = false,
    running: Boolean = true,
    phaseMillis: Float? = null,
) {
    var elapsed by remember { mutableFloatStateOf(0f) }
    var reducedMotion by remember { mutableStateOf(false) }
    LaunchedEffect(running, phaseMillis) {
        if (!running || phaseMillis != null) return@LaunchedEffect
        var previous = 0L
        while (true) {
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            reducedMotion = scale == 0f
            if (reducedMotion) break
            // Infinite-animation policy lets Compose tests idle while the real mini keeps moving.
            withInfiniteAnimationFrameNanos { now ->
                if (previous != 0L) elapsed += (now - previous) / 1_000_000f / scale
                previous = now
            }
        }
    }
    val ended = state == DoubaoCallVisualState.ENDED || state == DoubaoCallVisualState.ERROR
    val muted = state == DoubaoCallVisualState.MUTED
    val style = DoubaoGenieGeometry.orbStyle(DoubaoGenieGeometry.OrbMode.valueOf(state.name), compact)
    val frame = DoubaoGenieGeometry.orbFrame(phaseMillis ?: elapsed,
        responding = state == DoubaoCallVisualState.RESPONDING, compact = compact,
        still = muted || ended || reducedMotion)
    Box(modifier.testTag(if (compact) "doubao-call-mini-orb" else "doubao-call-orb-pixels"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val unit = size.minDimension / if (compact) 37f else 80f
            val center = this.center
            val radius = (if (compact) 18.5f else 33f) * unit
            if (!compact) {
                drawCircle(Color(style.halo), 40f * unit, center)
                // :before inset:-7px; CSS scales the 80px border, not the inner 66px ball.
                drawCircle(Color(0xFFE9DFF7).copy(alpha = frame.haloAlpha),
                    39.5f * unit * frame.haloScale, center, style = Stroke(unit * frame.haloScale))
            }
            drawCircle(Color(style.background), radius, center)
            if (!compact) {
                // CSS inset 0 0 17px #f8f4ff: radial approximation, retaining the exact base
                // colour in the centre and adding soft light only in the outer 17dp.
                if (style.glow) drawCircle(Brush.radialGradient(
                    0f to Color.Transparent, .48f to Color.Transparent,
                    1f to Color(0xFFF8F4FF).copy(alpha = .78f), center = center, radius = radius), radius, center)
                // :after inset:7px; 52px border box -> 25.5px stroke-centre radius.
                drawCircle(Color(0x77E0D4EF), 25.5f * unit, center, style = Stroke(unit))
            }
            if (!style.showWave) return@Canvas
            val barScale = if (compact) .65f else 1f
            val barColor = Color(style.bars)
            frame.barHeights.forEachIndexed { index, animatedHeight ->
                val height = if (reducedMotion) listOf(12f, 21f, 29f, 20f, 11f)[index] * barScale else animatedHeight
                val width = 3f * barScale * unit
                val x = center.x + (index - 2) * 6f * barScale * unit
                drawRoundRect(barColor, Offset(x - width / 2f, center.y - height * unit / 2f),
                    androidx.compose.ui.geometry.Size(width, height * unit),
                    androidx.compose.ui.geometry.CornerRadius(width / 2f))
            }
        }
        val iconSize = if (compact) 18.dp else 25.dp
        val iconColor = Color(style.icon)
        when {
            muted -> CallGlyph(CallControl.MIC, iconColor, Modifier.size(iconSize), active = true)
            ended -> CallGlyph(CallControl.HANG_UP, iconColor, Modifier.size(iconSize))
            else -> Unit
        }
    }
}

/** 通话面板使用的小型原生线条图标。 */
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
                CallControl.MINIMIZE, CallControl.EXPAND -> {
                    val svg = if (control == CallControl.MINIMIZE) DoubaoGenieGeometry.COLLAPSE_PATH else DoubaoGenieGeometry.EXPAND_PATH
                    drawPath(PathParser().parsePathString(svg).toPath(), color,
                        style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/** Bounded system-window content: no composer anchor, full-screen scrim or in-app Genie. */
@Composable
internal fun FloatingVoiceCallContent(state: DoubaoCallUi, actions: DoubaoCallActions,
    enabled: Boolean, onDrag: (Offset) -> Unit) {
    key(state.sessionKey) {
        val mini = state.presentation == DoubaoCallPresentation.MINI
        val scroll = rememberScrollState()
        val follow = remember { CallCaptionFollow() }
        LaunchedEffect(state.captionEpoch, state.captionsEnabled) { follow.followTail = true }
        // Do not replay a success receipt that predates moving to this presentation surface.
        val initialCompression = remember { state.compressionCompletion }
        val initialSearch = remember { state.searchCompletion }
        val indicator = rememberVoiceCallCompressionState(state.compressing,
            state.compressionCompletion?.takeUnless { it == initialCompression },
            enabled && state.presentation != DoubaoCallPresentation.CLOSING &&
                state.visualState !in setOf(DoubaoCallVisualState.ERROR, DoubaoCallVisualState.ENDED),
            state.searching, state.searchCompletion?.takeUnless { it == initialSearch }, state.captionEpoch, browsing = state.browsing)
        val captions = key(state.captionEpoch) {
            rememberCallCaptionReveal(state.transcript, state.transcriptTextComplete, enabled && !mini && state.captionsEnabled) to
                rememberCallCaptionReveal(state.reply, state.replyTextComplete, enabled && !mini && state.captionsEnabled)
        }
        val drag by rememberUpdatedState(onDrag)
        val handle = Modifier.pointerInput(enabled) {
            if (enabled) detectDragGestures { change, delta -> change.consume(); drag(delta) }
        }
        val shape = RoundedCornerShape(if (mini) 23.dp else 29.dp)
        Box(Modifier.fillMaxSize().testTag("voice-call-floating-content")
            .clip(shape).background(if (mini) Color(0xFFFCFAFF) else Color(0xFFFDFBFF))
            .border(1.dp, Color.White, shape)) {
            if (mini) MiniCallContent(state, callClock(state), enabled, handle, actions.onExpand,
                running = enabled, compressionState = indicator)
            else ExpandedCallContent(state, callClock(state), enabled, handle, actions,
                running = enabled, compressionState = indicator, captionScroll = scroll,
                captionFollow = follow, captions = captions)
        }
    }
}
