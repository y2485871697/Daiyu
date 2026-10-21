package app.daiyu.android.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

@Composable
internal fun ProvideTouchHaptics(content: @Composable () -> Unit) {
    val view = LocalView.current
    val feedback = remember(view) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                when (hapticFeedbackType) {
                    HapticFeedbackType.LongPress -> TouchHaptics.longPress(view)
                    HapticFeedbackType.TextHandleMove -> TouchHaptics.tick(view)
                    else -> TouchHaptics.click(view)
                }
            }
        }
    }
    CompositionLocalProvider(LocalHapticFeedback provides feedback, content = content)
}
