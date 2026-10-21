package app.daiyu.android.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import app.daiyu.android.domain.haptics.HapticKind

@Composable
internal fun hapticClick(enabled: Boolean, onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    return { if (enabled) TouchHaptics.action(view, block = onClick) }
}

@Composable
internal fun hapticLongClick(enabled: Boolean, onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    return { if (enabled) TouchHaptics.action(view, HapticKind.LONG_PRESS, onClick) }
}

@Composable
internal fun <T> hapticChange(enabled: Boolean, onChange: (T) -> Unit): (T) -> Unit {
    val view = LocalView.current
    return { value -> if (enabled) TouchHaptics.action(view) { onChange(value) } }
}
