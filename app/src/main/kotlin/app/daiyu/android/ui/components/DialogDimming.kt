package app.daiyu.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color

internal val LocalDialogDimStack = staticCompositionLocalOf<DialogDimStack?> { null }

internal class DialogDimLayer(val alpha: () -> Float)

internal class DialogDimStack {
    private val layers = mutableStateListOf<DialogDimLayer>()

    fun add(layer: DialogDimLayer) { layers.add(layer) }
    fun remove(layer: DialogDimLayer) { layers.remove(layer) }

    fun effectiveAlpha(layer: DialogDimLayer): Float {
        val index = layers.indexOf(layer)
        if (index < 0) return 0f
        val requested = layer.alpha().coerceIn(0f, 1f)
        var above = 0f
        for (i in index + 1 until layers.size) {
            above = maxOf(above, layers[i].alpha().coerceIn(0f, 1f))
        }
        // The composed background stays at the strongest shade, while upper windows shade lower panels.
        return if (above >= 1f) 0f else ((requested - above) / (1f - above)).coerceIn(0f, 1f)
    }
}

@Composable
internal fun Modifier.previewDialogDimBackground(
    stack: DialogDimStack,
    color: Color,
    motion: BottomSheetMotion?,
): Modifier {
    val currentColor by rememberUpdatedState(color)
    val currentMotion by rememberUpdatedState(motion)
    val layer = remember(stack) {
        DialogDimLayer { currentColor.alpha * (currentMotion?.scrimProgress?.value ?: 1f) }
    }
    DisposableEffect(stack, layer) {
        stack.add(layer)
        onDispose { stack.remove(layer) }
    }
    return drawWithContent {
        drawRect(color.copy(alpha = stack.effectiveAlpha(layer)))
        drawContent()
    }
}
