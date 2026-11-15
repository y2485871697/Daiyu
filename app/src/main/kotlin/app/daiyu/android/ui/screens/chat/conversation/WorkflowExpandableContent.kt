package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics

private const val WorkflowDisclosureDurationMillis = 220

@Composable
internal fun WorkflowExpandableContent(
    visibility: MutableTransitionState<Boolean>,
    motion: WorkflowDisclosureMotion,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expanded = visibility.targetState
    var hasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(expanded) {
        if (!expanded && hasFocus) focusManager.clearFocus(force = true)
    }
    AnimatedVisibility(
        visibleState = visibility,
        modifier = modifier.fillMaxWidth(),
        enter = expandVertically(
            animationSpec = tween(WorkflowDisclosureDurationMillis, easing = FastOutSlowInEasing),
            expandFrom = motion.alignment,
        ) + fadeIn(tween(WorkflowDisclosureDurationMillis)),
        exit = shrinkVertically(
            animationSpec = tween(WorkflowDisclosureDurationMillis, easing = FastOutSlowInEasing),
            shrinkTowards = motion.alignment,
        ) + fadeOut(tween(WorkflowDisclosureDurationMillis)),
    ) {
        // Exiting fields remain drawn, but no longer receive input or accessibility actions.
        Column(Modifier.fillMaxWidth().onFocusChanged { hasFocus = it.hasFocus }
            .focusProperties { onEnter = { if (!expanded) cancelFocusChange() } }.focusGroup()
            .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }), content = content)
    }
}
