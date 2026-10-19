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
    val tint = if (dark) Color(0xFF1F1C29) else Color(0xFFFAF7FF)
    val blur = if (backdrop == null) Modifier else Modifier.textureBlur(
        backdrop = backdrop, shape = shape, blurRadius = 24f,
        colors = BlurColors(blendColors = emptyList(), saturation = 1.35f),
    )
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().clip(shape).pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        }.then(blur).background(tint.copy(alpha = if (dark) .36f else .32f)))
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
