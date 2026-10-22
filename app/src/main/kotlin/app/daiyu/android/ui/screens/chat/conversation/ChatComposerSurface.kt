package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.captureForTopBar
import app.daiyu.android.ui.components.rememberTopBarBackdrop
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChatComposerSurface(backdrop: LayerBackdrop? = null, content: @Composable ColumnScope.() -> Unit) {
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.3f
    val shape = RoundedCornerShape(22.dp)
    val tint = if (dark) Color(0xFF1F1C29) else Color(0xFFE8E3F0)
    val blur = if (backdrop == null) Modifier else Modifier.textureBlur(
        backdrop = backdrop, shape = shape, blurRadius = 24f,
        colors = BlurColors(blendColors = emptyList(), saturation = 1.35f),
    )
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().clip(shape).pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        }.then(blur).background(tint.copy(alpha = if (dark) .36f else .60f)))
        Column(Modifier.fillMaxWidth().testTag("chat-composer-surface").clip(shape), content = content)
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
    val background = MiuixTheme.colorScheme.background
    SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val editor = subcompose("composer") {
            Box(Modifier.fillMaxWidth()) { composer(backdrop) }
        }.single().measure(constraints.copy(minWidth = width, minHeight = 0))
        val bottomInset = editor.height.toDp()
        val content = subcompose("messages") {
            Box(Modifier.fillMaxSize().captureForTopBar(backdrop).background(background)) {
                messages(bottomInset)
            }
        }.single().measure(Constraints.fixed(width, height))
        layout(width, height) {
            content.placeRelative(0, 0)
            editor.placeRelative(0, height - editor.height)
        }
    }
}
