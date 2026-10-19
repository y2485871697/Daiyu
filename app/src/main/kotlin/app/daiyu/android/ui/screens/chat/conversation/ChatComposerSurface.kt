package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceTopBarBlurStyle
import app.daiyu.android.ui.app.LocalTopBarBlurStyle
import app.daiyu.android.ui.components.captureForTopBar
import app.daiyu.android.ui.components.rememberTopBarBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChatComposerSurface(backdrop: LayerBackdrop? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MiuixTheme.colorScheme
    val dark = colors.background.luminance() < 0.3f
    val glass = backdrop != null
    val shape = RoundedCornerShape(22.dp)
    val tint = if (dark) Color(0xFF2D293A) else Color(0xFFF8F4FF)
    val stops = if (!glass) listOf(colors.surfaceContainerHigh, colors.surfaceContainerHigh) else if (dark) {
        listOf(Color(0xAA2D293A), Color(0x801D1B27), Color(0x6B1E1C29))
    } else {
        listOf(Color(0xA6FFFFFF), Color(0x70F8F4FF), Color(0x59FAF8FF))
    }
    val blur = when {
        backdrop == null -> Modifier
        LocalTopBarBlurStyle.current == AppearanceTopBarBlurStyle.PROGRESSIVE -> Modifier.progressiveTextureBlur(
            backdrop = backdrop, shape = shape, gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
            blurRadius = 24f, colors = BlurColors(blendColors = listOf(BlendColorEntry(tint.copy(alpha = 0.18f)))),
        )
        else -> Modifier.textureBlur(
            backdrop = backdrop, shape = shape, blurRadius = 24f,
            colors = BlurColors(blendColors = listOf(BlendColorEntry(tint.copy(alpha = 0.18f)))),
        )
    }
    Box(Modifier.fillMaxWidth().shadow(if (glass) 10.dp else 0.dp, shape,
        ambientColor = colors.primary.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.08f))) {
        Box(Modifier.matchParentSize().clip(shape).pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        }.then(blur).background(Brush.linearGradient(stops)))
        Column(Modifier.fillMaxWidth().testTag("chat-composer-surface").clip(shape)
            .border(1.dp, if (!glass) colors.outline else if (dark) Color(0x29DCCDFF) else Color(0xE6FFFFFF), shape)
            .drawWithContent {
                drawContent()
                if (glass) drawLine(if (dark) Color(0x1AF5EBFF) else Color(0xF5FFFFFF),
                    Offset(22.dp.toPx(), 0.5.dp.toPx()), Offset(size.width - 22.dp.toPx(), 0.5.dp.toPx()), 1.dp.toPx())
            }, content = content)
    }
}


/** Capture messages only; the editor is a separate, sharp overlay above the shared backdrop. */
@Composable
internal fun ChatConversationLayers(
    modifier: Modifier = Modifier,
    messages: @Composable (Dp) -> Unit,
    composer: @Composable (LayerBackdrop?) -> Unit,
) {
    val backdrop = rememberTopBarBackdrop()
    var heightPx by remember { mutableIntStateOf(0) }
    val bottomInset = with(LocalDensity.current) { heightPx.toDp() }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().captureForTopBar(backdrop)
            .background(MiuixTheme.colorScheme.background)) { messages(bottomInset) }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { heightPx = it.height }) {
            composer(backdrop)
        }
    }
}
