package app.daiyu.android.ui.media

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.Closeable

/** This lease is deliberately scoped to the preview Dialog's window, never an Activity. */
internal class ChatMediaPreviewStatusBars(private val window: Window) : Closeable {
    private val controller = WindowCompat.getInsetsController(window, window.decorView)
    private val previousBehavior = controller.systemBarsBehavior
    @Suppress("DEPRECATION")
    private val wasVisible = ViewCompat.getRootWindowInsets(window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.statusBars())
        ?: (window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0 &&
            window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN == 0)
    private var closed = false

    init {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    override fun close() {
        if (closed) return
        closed = true
        if (wasVisible) controller.show(WindowInsetsCompat.Type.statusBars())
        else controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = previousBehavior
    }
}

@Composable
internal fun ChatMediaPreviewHideStatusBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val lease = window?.let(::ChatMediaPreviewStatusBars)
        onDispose { lease?.close() }
    }
}
