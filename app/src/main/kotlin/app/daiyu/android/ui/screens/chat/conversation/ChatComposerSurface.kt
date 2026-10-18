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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceTopBarBlurStyle
import app.daiyu.android.ui.app.LocalBlurEnabled
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
internal fun ChatComposerSurface(content: @Composable ColumnScope.() -> Unit) {
    val colors = MiuixTheme.colorScheme
    val dark = colors.background.luminance() < 0.3f
    val glass = LocalBlurEnabled.current
    val backdrop = rememberTopBarBackdrop()
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
        // Capture only the decorative underlay. The editor and controls never enter the blur layer.
        Box(Modifier.matchParentSize().clip(shape).captureForTopBar(backdrop).background(colors.background)
            .drawBehind {
                if (glass) {
                    drawRect(Brush.radialGradient(
                        listOf(colors.primary.copy(alpha = if (dark) 0.22f else 0.18f), Color.Transparent),
                        center = Offset(0f, size.height * 0.2f), radius = size.width * 0.9f))
                    drawRect(Brush.radialGradient(
                        listOf(Color(0xFFB5CBDF).copy(alpha = if (dark) 0.08f else 0.16f), Color.Transparent),
                        center = Offset(size.width, size.height), radius = size.width * 0.75f))
                }
            })
        Box(Modifier.matchParentSize().clip(shape).then(blur).background(Brush.linearGradient(stops)))
        Column(Modifier.fillMaxWidth().testTag("chat-composer-surface").clip(shape)
            .border(1.dp, if (!glass) colors.outline else if (dark) Color(0x29DCCDFF) else Color(0xE6FFFFFF), shape)
            .drawWithContent {
                drawContent()
                if (glass) drawLine(if (dark) Color(0x1AF5EBFF) else Color(0xF5FFFFFF),
                    Offset(22.dp.toPx(), 0.5.dp.toPx()), Offset(size.width - 22.dp.toPx(), 0.5.dp.toPx()), 1.dp.toPx())
            }, content = content)
    }
}
