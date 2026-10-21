package app.daiyu.android.ui.components

import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Shared window coordinates for preview overlays; design insets are applied by each surface. */
@Composable
internal fun PreviewDialog(onDismiss: () -> Unit, content: @Composable BoxWithConstraintsScope.() -> Unit) {
    val colors = usagePalette()
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f); window?.setWindowAnimations(0) }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().dialogDimBackground(Color(if (colors.dark) 0x55000000 else 0x2620172d))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
            CompositionLocalProvider(LocalDensity provides density) {
                BoxWithConstraints(Modifier.dialogEnterMotion().fillMaxSize().safeDrawingPadding().imePadding(), content = content)
            }
        }
    }
}
