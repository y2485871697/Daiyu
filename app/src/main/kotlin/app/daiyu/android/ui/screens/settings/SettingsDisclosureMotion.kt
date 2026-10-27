package app.daiyu.android.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics

private const val SettingsDisclosureDurationMillis = 220

@Composable
internal fun SettingsExpandableContent(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(expanded) {
        if (!expanded && hasFocus) focusManager.clearFocus(force = true)
    }
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier.fillMaxWidth(),
        enter = expandVertically(
            animationSpec = tween(SettingsDisclosureDurationMillis, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top,
        ) + fadeIn(tween(SettingsDisclosureDurationMillis)),
        exit = shrinkVertically(
            animationSpec = tween(SettingsDisclosureDurationMillis, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(tween(SettingsDisclosureDurationMillis)),
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

@Composable
internal fun Modifier.settingsDisclosureRotation(expanded: Boolean, expandedDegrees: Float = 90f): Modifier {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) expandedDegrees else 0f,
        animationSpec = tween(SettingsDisclosureDurationMillis, easing = FastOutSlowInEasing),
        label = "settings-disclosure-chevron",
    )
    return rotate(rotation)
}
