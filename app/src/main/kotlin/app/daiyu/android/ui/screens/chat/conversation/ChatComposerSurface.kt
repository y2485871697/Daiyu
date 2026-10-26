package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import app.daiyu.android.ui.app.LocalAppearanceSettings
import app.daiyu.android.ui.app.styleColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.captureForTopBar
import app.daiyu.android.ui.components.rememberTopBarBackdrop
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun ChatComposerSurface(backdrop: LayerBackdrop? = null, content: @Composable ColumnScope.() -> Unit) {
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.3f
    val shape = RoundedCornerShape(22.dp)
    val tint = LocalAppearanceSettings.current.styleColor(if (dark) Color(0xFF1F1C29) else Color(0xFFF1EFF5))
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

/** Content-side blank strip only: never an ancestor of text, attachments or voice controls. */
@Composable
internal fun ComposerWorkspacePullUpArea(onOpenWorkspace: () -> Unit) {
    val latestOpenWorkspace by rememberUpdatedState(onOpenWorkspace)
    Box(Modifier.fillMaxWidth().height(12.dp).testTag("chat-workspace-gesture-area")
        .semantics {
            contentDescription = "会话工作区"
            onClick(label = "打开会话工作区") { latestOpenWorkspace(); true }
        }
        .pointerInput(Unit) {
            val threshold = max(48.dp.toPx(), viewConfiguration.touchSlop * 3f)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val origin = down.position
                var upward = false
                var rejected = false
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || event.changes.size != 1 || event.changes.any { it.isConsumed }) rejected = true
                    if (change != null) {
                        val delta = change.position - origin
                        // A horizontal/downward start cannot become an opener later.
                        if (!upward && !rejected && delta.getDistance() > viewConfiguration.touchSlop) {
                            if (-delta.y > abs(delta.x) * 1.5f) upward = true else rejected = true
                        }
                        if (abs(delta.x) > threshold || delta.y > viewConfiguration.touchSlop) rejected = true
                        // Commit only on a normal up. Cancellation does not reach this
                        // branch, and one pointer stream can commit at most once.
                        if (!change.pressed && change.previousPressed && !rejected && upward &&
                            -delta.y >= threshold && -delta.y > abs(delta.x) * 1.5f) {
                            latestOpenWorkspace()
                        }
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        })
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
