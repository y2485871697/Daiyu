package app.daiyu.android.ui.screens.chat.conversation

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import app.daiyu.android.ComposeActivityTestCase

@Suppress("DEPRECATION")
class DoubaoCallOverlayZeroDurationTest : ComposeActivityTestCase(object : MotionDurationScale {
    override val scaleFactor: Float = 0f
}) {
    fun testZeroScaleClosesWithoutWaitingForFourHundredTwentyMilliseconds() {
        val activity = startMain()
        var call by mutableStateOf<DoubaoCallUi?>(DoubaoCallUi(501L, null,
            startedAtMillis = SystemClock.elapsedRealtime()))
        var completions = 0
        val motion = DoubaoCallMotionState()
        ui.mainClock.autoAdvance = false
        try {
            mainThread {
                activity.setContent {
                    MaterialTheme {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val w = constraints.maxWidth.toFloat()
                            val h = constraints.maxHeight.toFloat()
                            call?.let { state ->
                                DoubaoCallOverlay(state, DoubaoCallActions(
                                    onHangUp = { call = call?.copy(presentation = DoubaoCallPresentation.CLOSING) },
                                    onCloseAnimationFinished = { key ->
                                        if (call?.sessionKey == key) { completions++; call = null }
                                    },
                                ), Rect(w / 2 - 16f, h - 40f, w / 2 + 16f, h - 8f), motion = motion)
                            }
                            if (call == null) Text("入口已恢复", Modifier.testTag("zero-scale-entry"))
                        }
                    }
                }
            }
            ui.waitUntil(10_000L) {
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
                ui.runOnIdle { !motion.transforming }
            }
            val closingAt = ui.mainClock.currentTime
            ui.onNodeWithTag("doubao-call-hang-up").assertIsEnabled().performClick()
            ui.waitUntil(10_000L) {
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
                ui.runOnIdle { call == null }
            }
            assertTrue(ui.mainClock.currentTime - closingAt < DoubaoGenieGeometry.DURATION_MILLIS)
            ui.onNodeWithTag("zero-scale-entry").assertExists()
            ui.runOnIdle { assertEquals(1, completions) }
        } finally {
            mainThread { activity.finish() }
        }
    }
}
