package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

internal data class WorkflowEdge(val from: String, val to: String)

/** Owned above disclosure and lazy-item content; revisiting an admitted event is silent. */
internal class WorkflowNodeMotions(initial: List<MessageContentUi> = emptyList()) {
    private val appeared = mutableSetOf<String>()
    private val completed = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private val connected = mutableSetOf<WorkflowEdge>()
    private val baselineNodes = mutableSetOf<String>()
    init { suppress(initial) }
    fun appear(key: String) = appeared.add(key)
    fun complete(key: String) = completed.add(key)
    fun fail(key: String) = failed.add(key)
    fun connect(edge: WorkflowEdge) = connected.add(edge) &&
        !(edge.from in baselineNodes && edge.to in baselineNodes)
    fun suppress(items: List<MessageContentUi>) {
        items.forEachIndexed { index, item ->
            if (item is MessageContentUi.Tool || item is MessageContentUi.Reasoning) {
                baselineNodes.add(item.workflowKey(index))
                appeared.add(item.workflowKey(index))
            }
        }
        items.forEachIndexed { index, item ->
            if ((item is MessageContentUi.Tool && item.step.state == WorkStepState.OK) ||
                (item is MessageContentUi.Reasoning && item.reported.activeSinceNanos == null)) {
                completed.add(item.workflowKey(index))
            }
            if (item is MessageContentUi.Tool && item.step.state == WorkStepState.FAILED) {
                failed.add(item.workflowKey(index))
            }
        }
        connected.addAll(workflowMotionEdges(items))
    }
}

internal fun workflowMotionEdges(items: List<MessageContentUi>): List<WorkflowEdge> =
    items.withIndex().filter { it.value is MessageContentUi.Tool || it.value is MessageContentUi.Reasoning }
        .map { it.value.workflowKey(it.index) }.zipWithNext(::WorkflowEdge)

internal val LocalWorkflowNodeMotions = staticCompositionLocalOf<WorkflowNodeMotions?> { null }
internal val LocalWorkflowNodeMotionEnabled = staticCompositionLocalOf { true }

internal data class WorkflowLinePart(val progress: State<Float>, val index: Int, val count: Int) {
    fun fraction() = (progress.value * count - index).coerceIn(0f, 1f)
}

internal data class WorkflowLineMotion(
    val incoming: WorkflowLinePart? = null,
    val outgoing: WorkflowLinePart? = null,
    val through: WorkflowLinePart? = null,
    val spacingAfter: Dp = 0.dp,
)

internal val LocalWorkflowLineMotion = staticCompositionLocalOf { WorkflowLineMotion() }

@Composable
internal fun rememberWorkflowLineMotions(items: List<MessageContentUi>, visible: List<Int>, continuous: Boolean): Map<Int, WorkflowLineMotion> {
    val owner = LocalWorkflowNodeMotions.current ?: return emptyMap()
    val enabled = LocalWorkflowNodeMotionEnabled.current
    val nodes = visible.filter { items[it] is MessageContentUi.Tool || items[it] is MessageContentUi.Reasoning }
    val result = mutableMapOf<Int, WorkflowLineMotion>()
    nodes.zipWithNext().forEach { (from, to) ->
        if (!continuous && to != from + 1) return@forEach
        val edge = WorkflowEdge(items[from].workflowKey(from), items[to].workflowKey(to))
        androidx.compose.runtime.key(edge) {
            val upstream = rememberUpdatedState(result[from]?.incoming)
            val animate = remember(owner, edge) { owner.connect(edge) }
            val progress = remember(owner, edge) { Animatable(if (animate) 0f else 1f) }
            LaunchedEffect(progress, enabled) {
                if (!enabled) progress.snapTo(1f)
                else if (animate && progress.value < 1f) {
                    snapshotFlow { upstream.value?.fraction() ?: 1f }.first { it >= 1f }
                    progress.animateTo(1f, tween(300, easing = LinearEasing))
                }
            }
            val middle = visible.filter { it > from && it < to }
            val count = middle.size + 2
            result[from] = (result[from] ?: WorkflowLineMotion()).copy(outgoing = WorkflowLinePart(progress.asState(), 0, count))
            middle.forEachIndexed { index, item ->
                result[item] = WorkflowLineMotion(through = WorkflowLinePart(progress.asState(), index + 1, count))
            }
            result[to] = (result[to] ?: WorkflowLineMotion()).copy(incoming = WorkflowLinePart(progress.asState(), count - 1, count))
        }
    }
    return result
}

@Composable
internal fun WorkflowLineScope(motion: WorkflowLineMotion?, spacingAfter: Dp, content: @Composable () -> Unit) {
    val visibleMotion = if (LocalWorkflowNodeMotionEnabled.current) motion else null
    CompositionLocalProvider(LocalWorkflowLineMotion provides (visibleMotion ?: WorkflowLineMotion()).copy(spacingAfter = spacingAfter), content = content)
}

internal class WorkflowToolAppearance(animate: Boolean) {
    val progress = Animatable(if (animate) 0f else 1f)
    var arrived by mutableStateOf(!animate)
    var settled by mutableStateOf(!animate)
}

@Composable
internal fun rememberWorkflowToolAppearance(key: String): WorkflowToolAppearance? {
    val owner = LocalWorkflowNodeMotions.current ?: return null
    val enabled = LocalWorkflowNodeMotionEnabled.current
    val incoming = rememberUpdatedState(LocalWorkflowLineMotion.current.incoming)
    val appearance = remember(owner, key) { WorkflowToolAppearance(owner.appear(key) && enabled) }
    LocalWorkflowItemLayout.current?.appearance = appearance
    LaunchedEffect(appearance, enabled) {
        if (!appearance.settled) {
            if (enabled) {
                snapshotFlow { incoming.value?.fraction() ?: 1f }.first { it >= 1f }
                appearance.arrived = true
                // A connected row is ready to paint now, not after another zero-alpha spring frame.
                if (incoming.value != null) appearance.progress.snapTo(.12f)
                appearance.progress.animateTo(1f, spring(dampingRatio = .7f, stiffness = 600f))
            } else appearance.progress.snapTo(1f)
            appearance.arrived = true
            appearance.settled = true
        }
    }
    return appearance
}

internal fun Modifier.workflowToolAppearance(appearance: WorkflowToolAppearance?): Modifier {
    if (appearance == null) return this
    return graphicsLayer {
        val fraction = appearance.progress.value.coerceIn(0f, 1f)
        alpha = fraction
    }
}

@Composable
internal fun Modifier.workflowCompletion(key: String, complete: Boolean, failed: Boolean = false,
    appearance: WorkflowToolAppearance? = null): Modifier {
    val owner = LocalWorkflowNodeMotions.current ?: return this
    val enabled = LocalWorkflowNodeMotionEnabled.current
    val incoming = rememberUpdatedState(LocalWorkflowLineMotion.current.incoming)
    val animate = remember(owner, key, complete, failed, enabled) {
        val fresh = if (failed) owner.fail(key) else complete && owner.complete(key)
        fresh && enabled
    }
    val sharedAppearance = remember(owner, key, complete, failed, enabled) { appearance?.takeUnless { it.settled } }
    val progress = remember(owner, key, complete, failed, enabled) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(progress) {
        if (animate && sharedAppearance == null) {
            snapshotFlow { incoming.value?.fraction() ?: 1f }.first { it >= 1f }
            progress.animateTo(1f, spring(dampingRatio = .7f, stiffness = 600f))
        }
    }
    return graphicsLayer {
        val fraction = sharedAppearance?.progress?.value ?: progress.value
        alpha = fraction.coerceIn(0f, 1f)
        scaleX = .65f + .35f * fraction
        scaleY = scaleX
    }
}

@Composable
internal fun WorkflowWaitingIndicator(key: String, tint: Color) {
    CircularProgressIndicator(Modifier.size(10.dp).testTag("workflow-waiting:$key"),
        color = tint, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
}
