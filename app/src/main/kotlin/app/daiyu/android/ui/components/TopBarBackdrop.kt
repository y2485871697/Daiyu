package app.daiyu.android.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.data.model.AppearanceTopBarBlurStyle
import app.daiyu.android.ui.app.LocalBlurEnabled
import app.daiyu.android.ui.app.LocalTopBarBlurStyle
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberTopBarBackdrop(surfaceColor: Color = MiuixTheme.colorScheme.surface): LayerBackdrop? {
    if (Build.VERSION.SDK_INT < 33 || !LocalBlurEnabled.current || !isRuntimeShaderSupported()) return null
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
internal fun TopBarBackdrop(
    backdrop: LayerBackdrop?,
    surfaceColor: Color = MiuixTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val modifier = when {
        Build.VERSION.SDK_INT < 33 || backdrop == null -> Modifier.background(surfaceColor)
        LocalTopBarBlurStyle.current == AppearanceTopBarBlurStyle.PROGRESSIVE -> {
            Modifier.progressiveTextureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                blurRadius = ProgressiveTopBarBlurRadius,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(surfaceColor.copy(alpha = ProgressiveTopBarSurfaceAlpha)),
                    ),
                ),
            )
        }
        else -> {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = TopBarBlurRadius,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(surfaceColor.copy(alpha = TopBarSurfaceAlpha)),
                    ),
                ),
            )
        }
    }
    Box(modifier = modifier) { content() }
}

internal fun Modifier.captureForTopBar(backdrop: LayerBackdrop?): Modifier =
    // The capture node draws content twice; replay one display list instead of the subtree.
    if (Build.VERSION.SDK_INT < 33 || backdrop == null) this else layerBackdrop(backdrop).graphicsLayer().then(ResumeBackdropCaptureElement)

private data object ResumeBackdropCaptureElement : ModifierNodeElement<ResumeBackdropCaptureNode>() {
    override fun create() = ResumeBackdropCaptureNode()
    override fun update(node: ResumeBackdropCaptureNode) = Unit
    override fun InspectorInfo.inspectableProperties() { name = "resumeBackdropCapture" }
}

private class ResumeBackdropCaptureNode : Modifier.Node(), DrawModifierNode,
    CompositionLocalConsumerModifierNode, ObserverModifierNode, LifecycleEventObserver {
    private var lifecycle: Lifecycle? = null

    override fun onAttach() = observeLifecycle()
    override fun onObservedReadsChanged() = observeLifecycle()

    private fun observeLifecycle() {
        observeReads {
            val current = currentValueOf(LocalLifecycleOwner).lifecycle
            if (current !== lifecycle) {
                lifecycle?.removeObserver(this)
                lifecycle = current
                current.addObserver(this)
            }
        }
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        // The inner display list must be recorded again before resumed blur captures replay it.
        if (event == Lifecycle.Event.ON_RESUME) invalidateDraw()
    }

    override fun ContentDrawScope.draw() = drawContent()

    override fun onDetach() {
        lifecycle?.removeObserver(this)
        lifecycle = null
    }
}

@Composable
internal fun topBarContainerColor(backdrop: LayerBackdrop?, surfaceColor: Color = MiuixTheme.colorScheme.surface): Color =
    if (Build.VERSION.SDK_INT < 33 || backdrop == null) surfaceColor else Color.Transparent

private const val TopBarBlurRadius = 25f
private const val TopBarSurfaceAlpha = 0.8f
private const val ProgressiveTopBarBlurRadius = 10f
private const val ProgressiveTopBarSurfaceAlpha = 0.3f
