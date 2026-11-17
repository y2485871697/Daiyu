package app.daiyu.android.ui.navigation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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

    fun testRetainedRootKeepsInputOwnershipButNotActivityWhileParentPaused() {
        val parent = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        var route by mutableStateOf("chat")
        var contentActive = false
        var inputOwned = false
        var retainedOwner: androidx.lifecycle.LifecycleOwner? = null
        var mounts = 0
        var disposals = 0
        mainThread {
            parent.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            activity.setContent {
                app.daiyu.android.ui.app.AgentAppTheme(app.daiyu.android.data.model.AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides parent) {
                        PageBackGestureHost(routeKey = route, contentKey = route,
                            canNavigateBack = route != "chat", swipeDismissEnabled = true,
                            predictiveBackEnabled = true, animateBackEnabled = false,
                            dispatchBack = {}, backTargetKey = if (route == "chat") null else "chat",
                            retainedRootKey = "chat", retainedRootContent = { active ->
                                val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                                val ownsInput = LocalPageInputOwned.current
                                val activeLocal = LocalPageContentActive.current
                                SideEffect {
                                    retainedOwner = owner
                                    contentActive = active && activeLocal
                                    inputOwned = ownsInput
                                }
                                DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                            }) {}
                    }
                }
            }
        }
        val owner = checkNotNull(retainedOwner)
        assertTrue(inputOwned)
        assertTrue(contentActive)
        for (state in listOf(androidx.lifecycle.Lifecycle.State.STARTED, androidx.lifecycle.Lifecycle.State.CREATED)) {
            mainThread { parent.registry.currentState = state }
            assertSame(owner, retainedOwner)
            assertTrue(inputOwned)
            assertFalse(contentActive)
            assertEquals(androidx.lifecycle.Lifecycle.State.CREATED, owner.lifecycle.currentState)
        }
        mainThread { parent.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        assertTrue(inputOwned)
        assertTrue(contentActive)
        mainThread { route = "settings" }
        assertFalse(inputOwned)
        assertFalse(contentActive)
        assertEquals(androidx.lifecycle.Lifecycle.State.CREATED, owner.lifecycle.currentState)
        mainThread { route = "chat" }
        assertTrue(inputOwned)
        assertTrue(contentActive)
        assertSame(owner, retainedOwner)
        assertEquals(1, mounts)
        assertEquals(0, disposals)
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
