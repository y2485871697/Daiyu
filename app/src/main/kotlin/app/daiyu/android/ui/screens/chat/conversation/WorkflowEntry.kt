package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Lives above disclosure content, so reopening a workflow never replays an item's entrance. */
internal class WorkflowEntrances(initiallyVisible: Collection<String> = emptyList()) {
    private val shown = initiallyVisible.toMutableSet()
    fun enter(key: String, animate: Boolean): Boolean = shown.add(key) && animate
    fun suppress(keys: Collection<String>) { shown.addAll(keys) }
}

@Composable
internal fun WorkflowEntry(key: String, entrances: WorkflowEntrances, animate: Boolean,
    stopped: Boolean = false, content: @Composable () -> Unit) {
    val enter = remember(key) { entrances.enter(key, animate && !stopped) }
    val visibility = remember(key, stopped) {
        MutableTransitionState(!enter || stopped).apply { targetState = true }
    }
    AnimatedVisibility(visibility, Modifier.testTag("workflow-entry:$key"),
        enter = expandVertically(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(220)),
        exit = ExitTransition.None) { content() }
}
