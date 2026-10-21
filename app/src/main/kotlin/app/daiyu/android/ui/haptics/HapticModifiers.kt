package app.daiyu.android.ui.haptics

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable as foundationClickable
import androidx.compose.foundation.combinedClickable as foundationCombinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable as foundationSelectable
import androidx.compose.foundation.selection.toggleable as foundationToggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.Role

internal fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    clickFeedback: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    foundationClickable(
        enabled = enabled,
        role = role,
        interactionSource = interactionSource,
        onClickLabel = onClickLabel,
        onClick = if (clickFeedback) hapticClick(enabled, onClick) else onClick,
    )
}

internal fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    clickFeedback: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    foundationClickable(
        enabled = enabled,
        role = role,
        interactionSource = interactionSource,
        indication = indication,
        onClickLabel = onClickLabel,
        onClick = if (clickFeedback) hapticClick(enabled, onClick) else onClick,
    )
}

internal fun Modifier.combinedClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    clickFeedback: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    foundationCombinedClickable(
        enabled = enabled,
        role = role,
        interactionSource = interactionSource,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        onClick = if (clickFeedback) hapticClick(enabled, onClick) else onClick,
        onLongClick = onLongClick?.let { hapticLongClick(enabled, it) },
        onDoubleClick = onDoubleClick?.let { hapticClick(enabled, it) },
        hapticFeedbackEnabled = false,
    )
}

internal fun Modifier.combinedClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    clickFeedback: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    foundationCombinedClickable(
        enabled = enabled,
        role = role,
        interactionSource = interactionSource,
        indication = indication,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        onClick = if (clickFeedback) hapticClick(enabled, onClick) else onClick,
        onLongClick = onLongClick?.let { hapticLongClick(enabled, it) },
        onDoubleClick = onDoubleClick?.let { hapticClick(enabled, it) },
        hapticFeedbackEnabled = false,
    )
}

internal fun Modifier.selectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    clickFeedback: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    foundationSelectable(
        selected = selected,
        enabled = enabled,
        role = role,
        interactionSource = interactionSource,
        onClick = if (clickFeedback) hapticClick(enabled, onClick) else onClick,
    )
}

internal fun Modifier.selectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    clickFeedback: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    foundationSelectable(
        selected = selected,
        enabled = enabled,
        role = role,
        interactionSource = interactionSource,
        indication = indication,
        onClick = if (clickFeedback) hapticClick(enabled, onClick) else onClick,
    )
}

internal fun Modifier.toggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onValueChange: (Boolean) -> Unit,
): Modifier = composed {
    foundationToggleable(
        value = value,
        enabled = enabled,
        role = role,
        interactionSource = interactionSource,
        onValueChange = hapticChange(enabled, onValueChange),
    )
}

internal fun Modifier.toggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    onValueChange: (Boolean) -> Unit,
): Modifier = composed {
    foundationToggleable(
        value = value,
        enabled = enabled,
        role = role,
        interactionSource = interactionSource,
        indication = indication,
        onValueChange = hapticChange(enabled, onValueChange),
    )
}
