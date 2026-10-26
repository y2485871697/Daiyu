package app.daiyu.android.ui.components

import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Shared window coordinates for preview overlays; design insets are applied by each surface. */
@Composable
internal fun PreviewDialog(onDismiss: () -> Unit, scrimColor: Color? = null, scrimBlurRadius: Float = 0f,
    bottomSheet: Boolean = false, onOutsideDismiss: () -> Unit = onDismiss, dismissEnabled: Boolean = true,
    content: @Composable BoxWithConstraintsScope.() -> Unit) {
    val colors = usagePalette()
    val density = LocalDensity.current
    val motion = remember(bottomSheet) { if (bottomSheet) BottomSheetMotion() else null }
    Dialog(onDismissRequest = {
        if (dismissEnabled) motion?.requestDismiss(BottomSheetDismissSource.System) ?: onDismiss()
    },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnBackPress = dismissEnabled, dismissOnClickOutside = dismissEnabled)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            if (bottomSheet) window?.setWindowAnimations(0) else window?.usePlatformDialogFade()
        }
        DialogMotionProvider(motion, onDismiss, onOutsideDismiss = onOutsideDismiss) {
            Box(Modifier.fillMaxSize().testTag("preview-dialog-viewport").dialogDismissInputGuard()) {
                Box(Modifier.matchParentSize().testTag("preview-dialog-scrim")
                    .then(if (scrimBlurRadius > 0f) Modifier.previewGlassBlur(androidx.compose.ui.graphics.RectangleShape, radius = scrimBlurRadius, saturation = 1f) else Modifier)
                    .dialogDimBackground(scrimColor ?: Color(if (colors.dark) 0x55000000 else 0x2620172d), animate = bottomSheet)
                    .clickable(enabled = dismissEnabled, interactionSource = remember { MutableInteractionSource() }, indication = null,
                        clickFeedback = false, onClick = {
                        if (dismissEnabled) motion?.requestDismiss(BottomSheetDismissSource.Outside) ?: onOutsideDismiss()
                    }))
                CompositionLocalProvider(LocalDensity provides density) {
                    val motion = if (bottomSheet) Modifier.dialogEnterMotion() else Modifier
                    BoxWithConstraints(motion.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), content = content)
                }
            }
        }
    }
}
