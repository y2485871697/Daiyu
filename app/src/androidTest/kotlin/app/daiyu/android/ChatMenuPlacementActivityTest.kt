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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import app.daiyu.android.ui.components.LocalPopupMotion
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatAnchoredMenu
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuLayer
import app.daiyu.android.ui.screens.chat.conversation.LocalChatMenuPlacementDiagnostic
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class ChatMenuPlacementActivityTest : WiredActivityTestCase() {
    private data class MenuDraw(val bounds: Rect, val fraction: Float, val padding: Float, val diagnostic: String)
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testMenuFirstDrawUsesMeasuredSizeAndDoesNotJumpAfterOpeningOrReopening() {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            for (bottom in listOf(false, true)) {
                val draws = CopyOnWriteArrayList<MenuDraw>()
                val coordinates = AtomicReference<LayoutCoordinates?>()
                val diagnostic = AtomicReference<(() -> String)?>(null)
                var expanded by mutableStateOf(false)
                mainThread {
                    activity.setContent {
                        CompositionLocalProvider(LocalLayoutDirection provides direction,
                            LocalChatMenuPlacementDiagnostic provides { diagnostic.set(it) }) {
                            AgentAppTheme(AppearanceSettings(blurEnabled = false), applyInterfaceScale = false) {
                                Box(Modifier.width(320.dp).height(480.dp).padding(17.dp, 23.dp, 11.dp, 7.dp)) {
                                    ChatMenuLayer(Modifier.fillMaxSize().testTag("menu-position-host")) {
                                        Box(Modifier.fillMaxSize()) {
                                            Box(Modifier.align(if (bottom) AbsoluteAlignment.BottomRight else AbsoluteAlignment.TopRight)
                                                .size(40.dp).testTag("menu-position-anchor")) {
                                                ChatAnchoredMenu(expanded, onDismiss = { expanded = false },
                                                    modifier = Modifier.testTag("menu-position-content")
                                                        .onGloballyPositioned { coordinates.set(it) }
                                                        .composed {
                                                            val motion = checkNotNull(LocalPopupMotion.current)
                                                            val padding = with(LocalDensity.current) { 6.dp.toPx() }
                                                            drawWithContent {
                                                                coordinates.get()?.takeIf { it.isAttached }?.let {
                                                                    val origin = it.positionInRoot()
                                                                    draws += MenuDraw(Rect(origin.x, origin.y,
                                                                        origin.x + size.width, origin.y + size.height),
                                                                        motion.fraction.value, padding,
                                                                        (diagnostic.get()?.invoke() ?: "no placement") + "; contentOrigin=$origin contentSize=${it.size} contentClipped=${it.boundsInRoot()}")
                                                                }
                                                                drawContent()
                                                            }
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
                    ui.mainClock.advanceTimeBy(1000)
                    ui.waitForIdle()
                    val menu = ui.onNodeWithTag("menu-position-content")
                    awaitNodeWindowFocus(menu)
                    val view = (menu.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
                    awaitStableWindowFrame(view) {
                        val current = coordinates.get()
                        if (current != null && current.isAttached && current.size.width > 0 && current.size.height > 0) {
                            val origin = current.positionInRoot()
                            listOf(view.width.toFloat(), view.height.toFloat(), origin.x, origin.y,
                                current.size.width.toFloat(), current.size.height.toFloat())
                        } else null
                    }
                    val stable = menu.fetchSemanticsNode().boundsInRoot
                    val anchor = ui.onNodeWithTag("menu-position-anchor").fetchSemanticsNode().boundsInRoot
                    val host = ui.onNodeWithTag("menu-position-host").fetchSemanticsNode().boundsInRoot
                    assertTrue("No drawn menu bounds: $direction bottom=$bottom", draws.isNotEmpty())
                    val evidence = draws.lastOrNull()?.let {
                        "lastDraw=${it.bounds} fraction=${it.fraction} padding=${it.padding}; ${it.diagnostic}"
                    } ?: diagnostic.get()?.invoke().orEmpty()
                    for (draw in draws) {
                        val drawEvidence = "direction=$direction bottom=$bottom fraction=${draw.fraction}; ${draw.diagnostic}"
                        val scale = 0.15f + 0.85f * draw.fraction
                        val pivotX = stable.right + draw.padding
                        val pivotY = stable.top - draw.padding
                        assertEquals(drawEvidence, pivotX + (stable.left - pivotX) * scale, draw.bounds.left, 1f)
                        assertEquals(drawEvidence, pivotY + (stable.top - pivotY) * scale, draw.bounds.top, 1f)
                        assertEquals(drawEvidence, stable.width, draw.bounds.width, 1f)
                        assertEquals(drawEvidence, stable.height, draw.bounds.height, 1f)
                    }
                    assertTrue("direction=$direction bottom=$bottom stable=$stable host=$host anchor=$anchor; $evidence",
                        stable.left >= host.left && stable.right <= host.right)
                    assertTrue("direction=$direction bottom=$bottom stable=$stable host=$host anchor=$anchor; $evidence",
                        stable.top >= host.top && stable.bottom <= host.bottom)
                    if (bottom) assertTrue("direction=$direction stable=$stable anchor=$anchor; $evidence", stable.bottom <= anchor.top)
                    else assertTrue("direction=$direction stable=$stable anchor=$anchor; $evidence", stable.top >= anchor.bottom)
                    if (it == 0) mainThread { activity.onBackPressedDispatcher.onBackPressed() }
                    else ui.onNodeWithTag("menu-position-host").performTouchInput {
                        click(Offset(5f, center.y))
                    }
                    ui.mainClock.advanceTimeBy(176)
                    ui.waitForIdle()
                    ui.onNodeWithTag("menu-position-content").assertDoesNotExist()
                }
            }
        }
        assertEquals(0, fixture.opens.get())
    }
}
