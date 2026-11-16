package app.daiyu.android.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat

/** Only the call's configuration opts in; ordinary preview sheets keep Activity windows. */
internal val LocalPreviewOverlayWindow = staticCompositionLocalOf { false }

@Composable
internal fun PreviewWindowDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties,
    content: @Composable () -> Unit,
) {
    if (!LocalPreviewOverlayWindow.current) {
        androidx.compose.ui.window.Dialog(onDismissRequest, properties, content)
        return
    }
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val parent = rememberCompositionContext()
    val inheritedSecure = (LocalView.current.rootView.layoutParams as? WindowManager.LayoutParams)
        ?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)?.let { it != 0 } == true
    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val dialog = remember(context, parent, properties, inheritedSecure) {
        PreviewOverlayWindow(context, properties, inheritedSecure, parent,
            onDismiss = { currentDismiss() }, content = { currentContent() })
    }
    DisposableEffect(dialog, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) dialog.dismiss()
        }
        owner.lifecycle.addObserver(observer)
        try {
            dialog.show()
        } catch (_: WindowManager.BadTokenException) { currentDismiss() }
        catch (_: SecurityException) { currentDismiss() }
        onDispose { owner.lifecycle.removeObserver(observer); dialog.dismiss() }
    }
}

/** Same overlay-dialog approach as the pet cupboard; the primary call window stays attached. */
internal class PreviewOverlayWindow(
    context: Context,
    properties: DialogProperties,
    inheritedSecure: Boolean,
    parent: CompositionContext?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) : ComponentDialog(context) {
    init {
        val callWindow = checkNotNull(window)
        callWindow.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        callWindow.setTitle("代鱼通话设置")
        callWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        callWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        callWindow.setDimAmount(.32f)
        WindowCompat.setDecorFitsSystemWindows(callWindow, properties.decorFitsSystemWindows)
        val secure = when (properties.securePolicy) {
            SecureFlagPolicy.SecureOn -> true
            SecureFlagPolicy.SecureOff -> false
            SecureFlagPolicy.Inherit -> inheritedSecure
        }
        if (secure) callWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setCanceledOnTouchOutside(properties.dismissOnClickOutside)
        setOnCancelListener { onDismiss() }
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (properties.dismissOnBackPress) onDismiss() }
        })
        val root = object : FrameLayout(context), DialogWindowProvider {
            override val window: Window get() = callWindow
        }
        root.addView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setParentCompositionContext(parent)
            setContent(content)
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun show() {
        super.show()
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
}
