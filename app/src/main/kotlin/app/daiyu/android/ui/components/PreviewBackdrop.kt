package app.daiyu.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

private val LocalPreviewBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** The activity layer excludes Dialog windows, avoiding recursive capture of glass surfaces. */
@Composable
internal fun PreviewBackdropHost(content: @Composable () -> Unit) {
    val layer = rememberTopBarBackdrop()
    val backdrop = remember(layer) { layer?.let(::WindowAlignedBackdrop) }
    CompositionLocalProvider(LocalPreviewBackdrop provides backdrop) {
        Box(Modifier.captureForTopBar(layer).onGloballyPositioned { backdrop?.sourceCoordinates = it }) {
            content()
        }
    }
}

@Composable
internal fun Modifier.previewGlassBlur(shape: Shape, radius: Float = 28f, saturation: Float = 1.3f): Modifier {
    val backdrop = LocalPreviewBackdrop.current ?: return this
    return textureBlur(backdrop = backdrop, shape = shape, blurRadius = radius,
        colors = BlurColors(blendColors = emptyList(), saturation = saturation))
}

/** LayerBackdrop's fallback uses window-local offsets; dialogs need screen-origin compensation. */
private class WindowAlignedBackdrop(private val source: LayerBackdrop) : Backdrop by source {
    var sourceCoordinates: LayoutCoordinates? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        val origin = sourceCoordinates?.takeIf { it.isAttached } ?: return
        val target = coordinates?.takeIf { it.isAttached } ?: return
        val sourceOrigin = origin.positionOnScreen() - origin.positionInWindow()
        val targetOrigin = target.positionOnScreen() - target.positionInWindow()
        val correction = (sourceOrigin - targetOrigin) / downscaleFactor.coerceAtLeast(1).toFloat()
        translate(correction.x, correction.y) {
            with(source) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        }
    }
}
