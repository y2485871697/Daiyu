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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
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

/** Shares the editor gesture without making voice or attachment controls descendants. */
@Composable
internal fun ComposerWorkspacePullUpArea(pointers: WorkspacePullUpPointers, onOpenWorkspace: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(12.dp).testTag("chat-workspace-gesture-area")
        .semantics {
            contentDescription = "会话工作区"
            onClick(label = "打开会话工作区") { onOpenWorkspace(); true }
        }.workspacePullUpGesture(pointers, onOpenWorkspace))
}

/** A passive ancestor sees pointers split across the strip, editor and controls. */
internal class WorkspacePullUpPointers {
    var rejected: Boolean = false
        private set
    fun update(previouslyPressed: Int, pointerCount: Int) {
        if (previouslyPressed == 0) rejected = false
        if (pointerCount > 1) rejected = true
    }
}

internal fun Modifier.observeWorkspacePointers(pointers: WorkspacePullUpPointers): Modifier = pointerInput(pointers) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            pointers.update(event.changes.count { it.previousPressed }, event.changes.size)
        }
    }
}

@Composable
internal fun Modifier.workspacePullUpGesture(pointers: WorkspacePullUpPointers, onOpenWorkspace: () -> Unit): Modifier {
    val latestOpenWorkspace by rememberUpdatedState(onOpenWorkspace)
    return pointerInput(pointers) {
        val threshold = max(48.dp.toPx(), viewConfiguration.touchSlop * 3f)
        awaitEachGesture {
            // TextField consumes taps in Main; observe Initial without stealing its down.
            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
            val origin = down.position
            var upward = false
            var rejected = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (pointers.rejected || change == null || event.changes.size != 1 || event.changes.any { it.isConsumed }) rejected = true
                if (change != null) {
                    val delta = change.position - origin
                    if (!upward && change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) rejected = true
                    if (!upward && !rejected && delta.getDistance() > viewConfiguration.touchSlop) {
                        if (-delta.y > abs(delta.x) * 1.5f) upward = true else rejected = true
                    }
                    if (abs(delta.x) > threshold || delta.y > viewConfiguration.touchSlop) rejected = true
                    if (!rejected && upward) {
                        change.consume()
                        if (event.type == PointerEventType.Release && !change.pressed && change.previousPressed &&
                            -delta.y >= threshold && -delta.y > abs(delta.x) * 1.5f) latestOpenWorkspace()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
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
