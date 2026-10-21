package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatAnchoredMenu
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuLayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class ChatMenuPlacementActivityTest : WiredActivityTestCase() {
    fun testMenuFirstDrawUsesMeasuredSizeAndDoesNotJumpAfterOpeningOrReopening() {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for (bottom in listOf(false, true)) {
                val draws = CopyOnWriteArrayList<Rect>()
                val coordinates = AtomicReference<LayoutCoordinates?>()
                var expanded by mutableStateOf(false)
                mainThread {
                    activity.setContent {
                        CompositionLocalProvider(LocalLayoutDirection provides direction) {
                            AgentAppTheme(AppearanceSettings(blurEnabled = false)) {
                                Box(Modifier.width(320.dp).height(480.dp).padding(17.dp, 23.dp, 11.dp, 7.dp)) {
                                    ChatMenuLayer(Modifier.fillMaxSize().testTag("menu-position-host")) {
                                        Box(Modifier.fillMaxSize()) {
                                            Box(Modifier.align(if (bottom) AbsoluteAlignment.BottomRight else AbsoluteAlignment.TopRight)
                                                .size(40.dp).testTag("menu-position-anchor")) {
                                                ChatAnchoredMenu(expanded, onDismiss = { expanded = false },
                                                    modifier = Modifier.testTag("menu-position-content")
                                                        .onGloballyPositioned { coordinates.set(it) }
                                                        .drawWithContent {
                                                            coordinates.get()?.takeIf { it.isAttached }?.let {
                                                                val origin = it.positionInRoot()
                                                                draws += Rect(origin.x, origin.y,
                                                                    origin.x + size.width, origin.y + size.height)
                                                            }
                                                            drawContent()
                                                        }) {
                                                    Box(Modifier.width(180.dp).height(200.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                repeat(2) {
                    draws.clear()
                    mainThread { expanded = true }
                    ui.onNodeWithTag("menu-position-content").assertIsDisplayed()
                    repeat(3) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
                    val stable = ui.onNodeWithTag("menu-position-content").fetchSemanticsNode().boundsInRoot
                    val anchor = ui.onNodeWithTag("menu-position-anchor").fetchSemanticsNode().boundsInRoot
                    val host = ui.onNodeWithTag("menu-position-host").fetchSemanticsNode().boundsInRoot
                    assertTrue("No drawn menu bounds: $direction bottom=$bottom", draws.isNotEmpty())
                    for (draw in draws) {
                        assertEquals(stable.left, draw.left, 1f)
                        assertEquals(stable.top, draw.top, 1f)
                        assertEquals(stable.right, draw.right, 1f)
                        assertEquals(stable.bottom, draw.bottom, 1f)
                    }
                    assertTrue(stable.left >= host.left && stable.right <= host.right)
                    assertTrue(stable.top >= host.top && stable.bottom <= host.bottom)
                    if (bottom) assertTrue(stable.bottom <= anchor.top) else assertTrue(stable.top >= anchor.bottom)
                    if (it == 0) mainThread { activity.onBackPressedDispatcher.onBackPressed() }
                    else ui.onNodeWithTag("menu-position-host").performTouchInput {
                        click(Offset(5f, size.height / 2f))
                    }
                    ui.onNodeWithTag("menu-position-content").assertDoesNotExist()
                }
            }
        }
        assertEquals(0, fixture.opens.get())
    }
}
