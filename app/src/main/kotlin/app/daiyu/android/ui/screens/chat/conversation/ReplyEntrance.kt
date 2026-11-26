package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class ReplyEntrancePart { Identity, FirstWorkflow }

internal val ReplyEntranceProgress = SemanticsPropertyKey<() -> Float>("ReplyEntranceProgress")

@Composable
internal fun Modifier.replyEntrance(
    scope: WorkflowVisualScope?, part: ReplyEntrancePart, eligible: Boolean,
    active: Boolean, ready: Boolean = true,
): Modifier {
    if (scope == null || !ready) return this
    val motion = remember(scope, part) {
        if (scope.claimReplyEntrance(part) && eligible && active) Animatable(0f) else null
    } ?: return this
    LaunchedEffect(motion, active) {
        if (!active) motion.snapTo(1f)
        else if (motion.value < 1f) motion.animateTo(1f, tween(200, easing = LinearOutSlowInEasing))
    }
    val progress = { if (active) motion.value else 1f }
    val layer = semantics { this[ReplyEntranceProgress] = progress }.graphicsLayer {
        alpha = progress()
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    // Move only the header's ink inside its slot. The card must keep its measured anchor.
    return if (part == ReplyEntrancePart.Identity) layer.drawWithContent {
        val offset = 6.dp.toPx() * (1f - progress())
        if (offset == 0f) drawContent()
        else clipRect { translate(top = offset) { this@drawWithContent.drawContent() } }
    } else layer
}
