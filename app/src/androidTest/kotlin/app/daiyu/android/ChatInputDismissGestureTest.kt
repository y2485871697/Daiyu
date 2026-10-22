package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.chatInputDismissTapModifier
import app.daiyu.android.ui.haptics.combinedClickable

class ChatInputDismissGestureTest : WiredActivityTestCase() {
    fun testOutsideObserverDoesNotConsumeClicksDragsLongPressesOrUseStaleCallback() {
        val callbackVersion = mutableIntStateOf(0)
        var oldDismisses = 0
        var dismisses = 0
        var clicks = 0
        var longPresses = 0
        var drags = 0
        var childDowns = 0
        var childUps = 0
        var childSawConsumedInitial = false
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val version = callbackVersion.intValue
                    Box(Modifier.size(240.dp).then(chatInputDismissTapModifier {
                        if (version == 0) oldDismisses++ else dismisses++
                    }).testTag("dismiss-observer")) {
                        Box(Modifier.size(200.dp).testTag("gesture-child")
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { change ->
                                            if (!change.previousPressed && change.pressed) childDowns++
                                            if (change.previousPressed && !change.pressed) childUps++
                                            if (change.isConsumed) childSawConsumedInitial = true
                                        }
                                    }
                                }
                            }
                            .combinedClickable(onClick = { clicks++ }, onLongClick = { longPresses++ })
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ -> change.consume(); drags++ }
                            })
                    }
                }
            }
        }
        node("gesture-child").performTouchInput { click() }
        ui.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(1, oldDismisses)
            assertEquals(0, dismisses)
        }
        mainThread { callbackVersion.intValue = 1 }
        node("gesture-child").performTouchInput { click() }
        ui.runOnIdle {
            assertEquals(2, clicks)
            assertEquals(1, oldDismisses)
            assertEquals(1, dismisses)
        }
        node("gesture-child").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            longClick()
        }
        ui.runOnIdle {
            assertEquals(1, longPresses)
            assertEquals(2, clicks)
            assertEquals(1, dismisses)
        }
        node("gesture-child").performTouchInput { swipeLeft() }
        ui.runOnIdle {
            assertTrue("child must receive real drag events", drags > 0)
            assertEquals(2, clicks)
            assertEquals(1, dismisses)
        }
        val beforeDowns = ui.runOnIdle { childDowns }
        val beforeUps = ui.runOnIdle { childUps }
        node("gesture-child").performTouchInput {
            down(0, centerLeft)
            down(1, centerRight)
            up(1)
            up(0)
        }
        ui.runOnIdle {
            assertEquals("a multi-touch gesture is not an outside tap", 1, dismisses)
            assertEquals(1, oldDismisses)
            assertEquals(beforeDowns + 2, childDowns)
            assertEquals(beforeUps + 2, childUps)
            assertFalse("outside observer must not consume child events", childSawConsumedInitial)
        }
        node("dismiss-observer").performTouchInput {
            val edge = Offset(width.toFloat() - 1f, center.y)
            down(edge)
            moveTo(Offset(width.toFloat() + 1f, center.y))
            moveTo(edge)
            up()
        }
        ui.runOnIdle {
            assertEquals("leaving the bounds permanently cancels the outside tap", 1, dismisses)
            assertEquals(1, oldDismisses)
        }
        assertEquals(0, fixture.opens.get())
    }
}
