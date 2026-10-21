package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import app.daiyu.android.ui.components.LocalPopupMotion
import app.daiyu.android.ui.components.PopupExitRetentionMillis
import app.daiyu.android.ui.components.rememberPopupMotion
import app.daiyu.android.ui.components.etaMenuClipReveal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.captureForTopBar
import app.daiyu.android.ui.components.rememberTopBarBackdrop
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class MenuOverlay(
    val expanded: () -> Boolean,
    val anchor: () -> Rect,
    val onDismiss: () -> Unit,
    val maxHeight: Dp?,
    val offset: DpOffset,
    val screenMargin: Dp,
    val content: @Composable () -> Unit,
)

private class MenuLayerState {
    var active by mutableStateOf<MenuOverlay?>(null)
}

private val LocalMenuLayer = staticCompositionLocalOf<MenuLayerState?> { null }

/** Menus share the captured page's coordinates, rather than sampling across Popup windows. */
@Composable
internal fun ChatMenuLayer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val backdrop = rememberTopBarBackdrop()
    val state = remember { MenuLayerState() }
    Box(modifier) {
        CompositionLocalProvider(LocalMenuLayer provides state) {
            Box(Modifier.fillMaxSize().captureForTopBar(backdrop)) { content() }
        }
        var host by remember { mutableStateOf(Rect.Zero) }
        Box(Modifier.matchParentSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
            .onGloballyPositioned { host = it.boundsInRoot() }) {
            state.active?.let { menu ->
                key(menu) {
                    val motion = rememberPopupMotion(menu.expanded())
                    BackHandler(enabled = menu.expanded(), onBack = menu.onDismiss)
                    Layout(
                        modifier = Modifier.fillMaxSize().pointerInput(menu) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (!menu.expanded()) event.changes.forEach { it.consume() }
                                }
                            }
                        },
                        content = {
                            Box(Modifier.fillMaxSize().conversationClickable { if (menu.expanded()) menu.onDismiss() })
                            ChatGlassMenuSurface(backdrop, Modifier
                                .graphicsLayer {
                                    val scale = 0.15f + 0.85f * motion.fraction.value
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = motion.alpha.value
                                    transformOrigin = TransformOrigin(1f, 0f)
                                }
                                .etaMenuClipReveal(
                                    fractionProgress = { if (menu.expanded()) motion.fraction.value else 1f },
                                    preferAbove = false, cornerRadius = 19.dp, squircleEnabled = false,
                                    clipWhenFullyRevealed = false,
                                )
                                .then(if (menu.expanded()) Modifier.semantics { paneTitle = "会话菜单" }
                                else Modifier.clearAndSetSemantics {})) {
                                CompositionLocalProvider(LocalPopupMotion provides motion) { menu.content() }
                            }
                        },
                    ) { measurables, constraints ->
                        val width = constraints.maxWidth
                        val height = constraints.maxHeight
                        val margin = 9.dp.roundToPx()
                        val widthMargin = menu.screenMargin.roundToPx()
                        val maxWidth = (width - widthMargin * 2).coerceAtLeast(1)
                        val availableHeight = (height - margin * 2).coerceAtLeast(1)
                        val maxHeight = menu.maxHeight?.roundToPx()?.coerceAtMost(availableHeight) ?: availableHeight
                        val dismiss = measurables[0].measure(Constraints.fixed(width, height))
                        val surface = measurables[1].measure(Constraints(
                            minWidth = minOf(156.dp.roundToPx(), maxWidth),
                            maxWidth = maxWidth,
                            maxHeight = maxHeight,
                        ))
                        val offsetX = menu.offset.x.roundToPx()
                        val offsetY = menu.offset.y.roundToPx()
                        layout(width, height) {
                            dismiss.place(0, 0)
                            val anchor = menu.anchor().translate(-host.left, -host.top)
                            val y = (if (anchor.bottom + offsetY + surface.height + margin <= height) anchor.bottom.toInt() + offsetY
                                else anchor.top.toInt() - offsetY - surface.height).coerceIn(margin,
                                    (height - surface.height - margin).coerceAtLeast(margin))
                            val x = (anchor.right.toInt() + offsetX - surface.width).coerceIn(margin,
                                (width - surface.width - margin).coerceAtLeast(margin))
                            surface.place(x, y)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChatAnchoredMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = 336.dp,
    offset: DpOffset = DpOffset.Zero,
    screenMargin: Dp = 9.dp,
    content: @Composable () -> Unit,
) {
    val layer = LocalMenuLayer.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestExpanded by rememberUpdatedState(expanded)
    var mounted by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded) mounted = true
        else if (mounted) {
            delay(PopupExitRetentionMillis)
            mounted = false
        }
    }
    Box(Modifier.onGloballyPositioned { coordinates ->
        anchor = coordinates.parentLayoutCoordinates?.boundsInRoot() ?: coordinates.boundsInRoot()
    })
    if ((expanded || mounted) && layer != null) {
        val menu = remember(maxHeight, offset, screenMargin) { MenuOverlay({ latestExpanded }, { anchor }, { if (latestExpanded) latestDismiss() }, maxHeight, offset, screenMargin) { Box(modifier) { latestContent() } } }
        LaunchedEffect(layer, menu, expanded) {
            if (expanded) {
                layer.active?.takeIf { it !== menu }?.onDismiss?.invoke()
                layer.active = menu
            }
        }
        DisposableEffect(layer, menu) {
            onDispose { if (layer.active === menu) layer.active = null }
        }
    } else if (layer == null && expanded) {
        // Isolated component previews have no page host; production uses ChatMenuLayer.
        androidx.compose.material3.DropdownMenu(expanded = true, onDismissRequest = onDismiss,
            modifier = modifier, containerColor = MiuixTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(19.dp)) { content() }
    }
}

@Composable
internal fun ChatGlassMenuSurface(backdrop: LayerBackdrop?, modifier: Modifier, content: @Composable () -> Unit) {
    val dark = MiuixTheme.colorScheme.background.luminance() < .3f
    val shape = RoundedCornerShape(19.dp)
    val blur = if (backdrop == null) Modifier else Modifier.textureBlur(
        backdrop = backdrop, shape = shape, blurRadius = 24f,
        colors = BlurColors(blendColors = emptyList(), saturation = 1.35f),
    )
    val shades = if (dark) listOf(Color(0xE62C2739), Color(0xDB1B1924))
        else listOf(Color(0xE6FFFFFF), Color(0xD4FAF7FF))
    Column(modifier.width(IntrinsicSize.Max)
        .dropShadow(shape, Shadow(radius = if (dark) 35.dp else 34.dp,
            offset = DpOffset(0.dp, if (dark) 14.dp else 12.dp),
            color = if (dark) Color(0x55000000) else Color(0x164C3A6B)))
        .dropShadow(shape, Shadow(radius = 6.dp, offset = DpOffset(0.dp, 2.dp),
            color = if (dark) Color(0x22000000) else Color(0x05342048)))
        .clip(shape).then(blur)
        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
        .background(Brush.linearGradient(shades))
        .border(1.dp, if (dark) Color(0x26DCCDFF) else Color(0xF0FFFFFF), shape)
        .drawWithCache {
            val edge = 1.dp.toPx()
            val innerBounds = Rect(edge, edge, size.width - edge, size.height - edge)
            val innerRadius = CornerRadius(18.dp.toPx())
            val inner = Path().apply { addRoundRect(RoundRect(innerBounds, innerRadius)) }
            val shifted = Path().apply {
                addRoundRect(RoundRect(innerBounds.translate(0f, edge), innerRadius))
            }
            // CSS inset 0 1px 0: only the top-facing part of the inner rounded edge.
            val highlight = Path.combine(PathOperation.Difference, inner, shifted)
            onDrawWithContent {
                drawContent()
                drawRoundRect(
                    color = if (dark) Color(0x06D7C5FF) else Color(0x0E9581B3),
                    topLeft = Offset(edge * 1.5f, edge * 1.5f),
                    size = Size(size.width - edge * 3, size.height - edge * 3),
                    cornerRadius = CornerRadius(17.5.dp.toPx()), style = Stroke(edge),
                )
                drawPath(highlight, if (dark) Color(0x14F5EBFF) else Color.White)
            }
        }
        .verticalScroll(rememberScrollState()).padding(6.dp)) { content() }
}
