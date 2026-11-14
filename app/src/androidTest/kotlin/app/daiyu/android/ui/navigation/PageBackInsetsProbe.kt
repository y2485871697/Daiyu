package app.daiyu.android.ui.navigation

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Delivers real Android insets to the Compose view, rather than changing a fixture-only number. */
internal class PageBackInsetsProbe {
    private var view: View? = null
    private var original: WindowInsetsCompat? = null
    private var legacyListener: View.OnApplyWindowInsetsListener? = null
    private var retainedInsets: android.view.WindowInsets? = null
    var observed = IntSize.Zero
        private set
    val observations = mutableSetOf<IntSize>()

    @Composable
    fun Observe() {
        val host = LocalView.current
        val density = LocalDensity.current
        val bars = WindowInsets.systemBars
        val value = IntSize(bars.getTop(density), bars.getBottom(density))
        SideEffect {
            if (view == null) original = ViewCompat.getRootWindowInsets(host)
            view = host
            observed = value
            observations.add(value)
        }
    }

    fun apply(value: IntSize) {
        val host = checkNotNull(view)
        val initial = original ?: WindowInsetsCompat.Builder().build()
        val insets = Insets.of(0, value.width, 0, value.height)
        val delivered = WindowInsetsCompat.Builder(initial)
            .setInsets(WindowInsetsCompat.Type.systemBars(), insets)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), insets)
            .setVisible(WindowInsetsCompat.Type.systemBars(), true)
            .build()
        if (android.os.Build.VERSION.SDK_INT < 30) {
            if (legacyListener == null) {
                val listener = checkNotNull(host.getTag(androidx.core.R.id.tag_on_apply_window_listener)
                    as? View.OnApplyWindowInsetsListener) { "Compose Insets listener is not installed" }
                legacyListener = listener
                // Core re-requests platform insets on old APIs; keep the injected input for that pass.
                host.setOnApplyWindowInsetsListener { target, incoming ->
                    listener.onApplyWindowInsets(target, retainedInsets ?: incoming)
                }
            }
            retainedInsets = checkNotNull(delivered.toWindowInsets())
        }
        ViewCompat.dispatchApplyWindowInsets(host, delivered)
    }

    fun restore() {
        val host = view ?: return
        retainedInsets = null
        legacyListener?.let { host.setOnApplyWindowInsetsListener(it) }
        legacyListener = null
        original?.let { ViewCompat.dispatchApplyWindowInsets(host, it) }
            ?: ViewCompat.requestApplyInsets(host)
    }
}
