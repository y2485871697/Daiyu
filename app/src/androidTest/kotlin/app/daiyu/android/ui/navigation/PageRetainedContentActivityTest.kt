package app.daiyu.android.ui.navigation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme

class PageRetainedContentActivityTest : WiredActivityTestCase() {
    private class Parent : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private lateinit var parent: Parent
    private var route by mutableStateOf("chat")
    private var scopeKey by mutableStateOf(0)
    private var mounts = 0
    private var disposals = 0
    private var rootBacks = 0
    private var detailBacks = 0
    private var rootActive = false
    private var rootOwner: LifecycleOwner? = null
    private val owners = mutableListOf<LifecycleOwner>()

    private fun mount() {
        mainThread {
            parent = Parent().also { it.registry.currentState = Lifecycle.State.RESUMED }
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalLifecycleOwner provides parent) {
                        val rendered = route
                        PageBackGestureHost(
                            routeKey = rendered, contentKey = rendered,
                            canNavigateBack = rendered != "chat",
                            swipeDismissEnabled = true, predictiveBackEnabled = true,
                            animateBackEnabled = false,
                            dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                            backTargetKey = if (rendered == "chat") null else "chat",
                            retainedRootKey = "chat", retainedRootScopeKey = scopeKey,
                            retainedRootContent = { active ->
                                val owner = LocalLifecycleOwner.current
                                DisposableEffect(Unit) {
                                    mounts++
                                    owners.add(owner)
                                    onDispose { disposals++ }
                                }
                                SideEffect { rootOwner = owner; rootActive = active }
                                PageBackHandler { rootBacks++ }
                                Box(Modifier.fillMaxSize().background(Color.Red).testTag("retained-root"))
                            },
                        ) {
                            if (rendered != "chat") {
                                PageBackHandler { detailBacks++; route = "chat" }
                                Box(Modifier.fillMaxSize().background(Color.Blue).testTag("detail-root"))
                            }
                        }
                    }
                }
            }
        }
        dismissIme()
        ui.waitForIdle()
    }

    fun testRootRemainsOneMeasuredInstanceAcrossHiddenPagesAndImmediateReturn() {
        mount()
        val original = ui.onNodeWithTag("retained-root").fetchSemanticsNode().id
        val owner = checkNotNull(rootOwner)
        assertTrue(rootActive)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        for (next in listOf("settings", "providers", "models")) {
            mainThread { route = next }
            ui.onNodeWithTag("detail-root").assertIsDisplayed()
            ui.onNodeWithTag("retained-root").assertDoesNotExist()
            assertEquals(1, mounts)
            assertEquals(0, disposals)
            assertFalse(rootActive)
            assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
        }
        mainThread {
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals("chat", route)
        }
        ui.onNodeWithTag("retained-root").assertIsDisplayed()
        assertEquals(original, ui.onNodeWithTag("retained-root").fetchSemanticsNode().id)
        assertSame(owner, rootOwner)
        assertEquals(1, mounts)
        assertEquals(0, disposals)
        assertEquals(1, detailBacks)
        assertEquals(0, rootBacks)
        assertTrue(rootActive)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
    }

    fun testHiddenRootReleasesOnPauseAndIsNotRemountedUntilVisited() {
        mount()
        val original = checkNotNull(rootOwner)
        mainThread { route = "settings" }
        mainThread { parent.registry.currentState = Lifecycle.State.CREATED }
        assertEquals(1, mounts)
        assertEquals(1, disposals)
        assertEquals(Lifecycle.State.DESTROYED, original.lifecycle.currentState)
        mainThread { parent.registry.currentState = Lifecycle.State.RESUMED }
        assertEquals(1, mounts)
        ui.onNodeWithTag("retained-root").assertDoesNotExist()
        mainThread { route = "chat" }
        ui.onNodeWithTag("retained-root").assertIsDisplayed()
        assertEquals(2, mounts)
        assertNotSame(original, rootOwner)
        assertEquals(Lifecycle.State.RESUMED, checkNotNull(rootOwner).lifecycle.currentState)
    }

    fun testScopeChangeDisposesTheOldRootRatherThanRetainingTwoInstances() {
        mount()
        val original = checkNotNull(rootOwner)
        mainThread { route = "settings" }
        mainThread { scopeKey++ }
        assertEquals(1, disposals)
        assertEquals(Lifecycle.State.DESTROYED, original.lifecycle.currentState)
        assertEquals(1, mounts)
        mainThread { route = "chat" }
        assertEquals(2, mounts)
        assertEquals(1, disposals)
        assertNotSame(original, rootOwner)
        assertEquals(2, owners.size)
        ui.onNodeWithTag("retained-root").assertIsDisplayed()
    }
}
