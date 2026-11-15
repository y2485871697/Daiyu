package app.daiyu.android.ui.navigation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.daiyu.android.WiredActivityTestCase
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PageImeCompositionActivityTest : WiredActivityTestCase() {
    private class AnimatedImeInsets : WindowInsets {
        var bottom by mutableIntStateOf(0)
        override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getTop(density: Density) = 0
        override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getBottom(density: Density) = bottom
    }

    fun testImePixelsDoNotRecomposeNavigationUntilVisibilityChanges() {
        val insets = AnimatedImeInsets()
        val compositions = AtomicInteger()
        val visible = AtomicBoolean()
        mainThread {
            activity.setContent {
                val shown = rememberPageImeVisible(insets)
                SideEffect {
                    visible.set(shown)
                    compositions.incrementAndGet()
                }
            }
        }
        ui.waitForIdle()
        val hidden = compositions.get()
        assertTrue(hidden > 0)
        assertFalse(visible.get())
        mainThread { insets.bottom = 1 }
        ui.waitForIdle()
        val opened = compositions.get()
        assertTrue(opened > hidden)
        assertTrue(visible.get())
        for (pixels in listOf(20, 80, 160, 280, 360, 210, 90, 1)) {
            mainThread { insets.bottom = pixels }
            ui.waitForIdle()
            assertTrue(visible.get())
            assertEquals("IME pixel frame $pixels must not recompose the navigation host", opened, compositions.get())
        }
        mainThread { insets.bottom = 0 }
        ui.waitForIdle()
        assertFalse(visible.get())
        assertTrue(compositions.get() > opened)
    }
}
