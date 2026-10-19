package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.captureForTopBar
import app.daiyu.android.ui.components.rememberTopBarBackdrop
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class MenuOverlay(
    val anchor: () -> Rect,
    val onDismiss: () -> Unit,
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
        state.active?.let { menu ->
            BackHandler(onBack = menu.onDismiss)
            Box(Modifier.matchParentSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))) {
                var host by remember { mutableStateOf(Rect.Zero) }
                Box(Modifier.fillMaxSize().onGloballyPositioned { host = it.boundsInRoot() }) {
                    Box(Modifier.matchParentSize().conversationClickable(onClick = menu.onDismiss))
                    var menuSize by remember(menu) { mutableStateOf(IntSize.Zero) }
                    val density = LocalDensity.current
                    val margin = with(density) { 9.dp.roundToPx() }
                    val anchor = menu.anchor().translate(-host.left, -host.top)
                    val maxWidth = with(density) { (host.width - margin * 2).coerceAtLeast(1f).toDp() }
                    val maxHeight = minOf(336.dp, with(density) { (host.height - margin * 2).coerceAtLeast(1f).toDp() })
                    val y = (if (anchor.bottom + menuSize.height + margin <= host.height) anchor.bottom.toInt()
                        else anchor.top.toInt() - menuSize.height).coerceIn(margin,
                            (host.height.toInt() - menuSize.height - margin).coerceAtLeast(margin))
                    val x = (anchor.right.toInt() - menuSize.width).coerceIn(margin,
                        (host.width.toInt() - menuSize.width - margin).coerceAtLeast(margin))
                    ChatGlassMenuSurface(backdrop, Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset(x, y) }
                        .widthIn(min = minOf(156.dp, maxWidth), max = maxWidth).heightIn(max = maxHeight)
                        .onSizeChanged { menuSize = it }
                        .semantics { paneTitle = "会话菜单" }) { menu.content() }
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
    content: @Composable () -> Unit,
) {
    val layer = LocalMenuLayer.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    Box(Modifier.onGloballyPositioned { coordinates ->
        anchor = coordinates.parentLayoutCoordinates?.boundsInRoot() ?: coordinates.boundsInRoot()
    })
    if (expanded && layer != null) {
        val menu = remember { MenuOverlay({ anchor }, { latestDismiss() }) { Box(modifier) { latestContent() } } }
        DisposableEffect(layer, menu) {
            layer.active = menu
            onDispose { if (layer.active === menu) layer.active = null }
        }
    } else if (expanded) {
        // Isolated component previews have no page host; production uses ChatMenuLayer.
        androidx.compose.material3.DropdownMenu(expanded = true, onDismissRequest = onDismiss,
            modifier = modifier, containerColor = MiuixTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(19.dp)) { content() }
    }
}

@Composable
private fun ChatGlassMenuSurface(backdrop: LayerBackdrop?, modifier: Modifier, content: @Composable () -> Unit) {
    val dark = MiuixTheme.colorScheme.background.luminance() < .3f
    val shape = RoundedCornerShape(19.dp)
    val blur = if (backdrop == null) Modifier else Modifier.textureBlur(
        backdrop = backdrop, shape = shape, blurRadius = 24f,
        colors = BlurColors(blendColors = emptyList(), saturation = 1.35f),
    )
    val shades = if (dark) listOf(Color(0xE62C2739), Color(0xDB1B1924))
        else listOf(Color(0xE6FFFFFF), Color(0xD4FAF7FF))
    Column(modifier.width(IntrinsicSize.Max).clip(shape).then(blur)
        .pointerInput(Unit) { detectTapGestures(onTap = {}) }
        .background(Brush.linearGradient(shades))
        .border(1.dp, if (dark) Color(0x26DCCDFF) else Color(0xF0FFFFFF), shape)
        .verticalScroll(rememberScrollState()).padding(6.dp)) { content() }
}
