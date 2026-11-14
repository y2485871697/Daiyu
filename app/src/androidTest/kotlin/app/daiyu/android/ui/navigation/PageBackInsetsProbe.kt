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
        ViewCompat.dispatchApplyWindowInsets(host, WindowInsetsCompat.Builder(initial)
            .setInsets(WindowInsetsCompat.Type.systemBars(), insets)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), insets)
            .setVisible(WindowInsetsCompat.Type.systemBars(), true)
            .build())
    }

    fun restore() {
        val host = view ?: return
        original?.let { ViewCompat.dispatchApplyWindowInsets(host, it) }
            ?: ViewCompat.requestApplyInsets(host)
    }
}
