package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.DaiyuChoiceSheet
import app.daiyu.android.ui.components.DialogDismissing
import app.daiyu.android.ui.components.DialogMotionProgress
import app.daiyu.android.ui.components.PreviewDialog
import app.daiyu.android.ui.components.subagent.ConversationAgentSheet
import app.daiyu.android.ui.haptics.material.TextButton

class BottomSheetDismissMotionActivityTest : WiredActivityTestCase() {
    private val open = mutableStateOf(true)
    private val acceptDismiss = mutableStateOf(true)
    private val callbackRevision = mutableStateOf(0)
    private var dismissals = 0
    private var outsideDismissals = 0
    private var disposals = 0
    private var confirmationClicks = 0
    private var deliveredRevision = -1

    private fun show(kind: Int = 0) {
        mainThread {
            open.value = true
            acceptDismiss.value = true
            callbackRevision.value = 0
            dismissals = 0
            outsideDismissals = 0
            disposals = 0
            confirmationClicks = 0
            deliveredRevision = -1
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                    Box(Modifier.fillMaxSize()) {
                        if (open.value) {
                            DisposableEffect(Unit) { onDispose { disposals++ } }
                            val revision = callbackRevision.value
                            val dismiss = {
                                dismissals++
                                deliveredRevision = revision
                                if (acceptDismiss.value) open.value = false
                            }
                            val outside = { outsideDismissals++; open.value = false }
                            val body: @Composable () -> Unit = {
                                TextButton(onClick = { confirmationClicks++ }, modifier = Modifier.testTag("motion-confirm")) {
                                    Text("确认")
                                }
                            }
                            when (kind) {
                                0 -> ConversationAgentSheet(dismiss) { body() }
                                1 -> PreviewDialog(dismiss, bottomSheet = true, onOutsideDismiss = outside) { body() }
                                else -> DaiyuChoiceSheet("动画选择", listOf("一"), { confirmationClicks++ }, dismiss)
                            }
                        }
                    }
                }
            }
        }
        val tag = if (kind == 2) "daiyu-choice-sheet" else "motion-confirm"
        node(tag).assertIsDisplayed()
        awaitNodeWindowFocus(node(tag))
    }

    private fun sendBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
    }

    private fun exitingProgress(): Float = ui.onNode(
        SemanticsMatcher.expectValue(DialogDismissing, true), useUnmergedTree = true,
    ).fetchSemanticsNode().config[DialogMotionProgress]

    fun testBackRetainsEachSharedSheetThroughIntermediateFramesAndOnlyClosesOnce() {
        for (kind in 0..2) {
            show(kind)
            val previousAutoAdvance = ui.mainClock.autoAdvance
            try {
                ui.mainClock.autoAdvance = false
                sendBack()
                assertEquals(0, dismissals)
                assertEquals(0, disposals)
                ui.mainClock.advanceTimeBy(80)
                val progress = exitingProgress()
                assertTrue("Sheet has a real intermediate exit frame: $progress", progress > 0f && progress < 1f)
                if (kind != 2) node("motion-confirm").assertDoesNotExist()
                sendBack()
                assertEquals(0, dismissals)
                ui.mainClock.advanceTimeBy(3_000)
                ui.waitForIdle()
                assertEquals(1, dismissals)
                assertEquals(1, disposals)
                assertEquals(0, outsideDismissals)
                assertEquals(0, confirmationClicks)
                assertEquals(0, fixture.opens.get())
            } finally {
                ui.mainClock.autoAdvance = previousAutoAdvance
            }
        }
    }

    fun testOutsideAndSystemKeepFirstSourceAndUseLatestOwnerCallback() {
        show(1)
        val previousAutoAdvance = ui.mainClock.autoAdvance
        try {
            ui.mainClock.autoAdvance = false
            node("preview-dialog-scrim").performClick()
            ui.mainClock.advanceTimeByFrame()
            sendBack()
            ui.mainClock.advanceTimeBy(3_000)
            ui.waitForIdle()
            assertEquals(1, outsideDismissals)
            assertEquals(0, dismissals)
            assertEquals(1, disposals)
        } finally {
            ui.mainClock.autoAdvance = previousAutoAdvance
        }
        show()
        try {
            ui.mainClock.autoAdvance = false
            sendBack()
            instrumentation.runOnMainSync { callbackRevision.value = 1 }
            ui.mainClock.advanceTimeBy(3_000)
            ui.waitForIdle()
            assertEquals(1, dismissals)
            assertEquals(1, deliveredRevision)
            assertEquals(1, disposals)
        } finally {
            ui.mainClock.autoAdvance = previousAutoAdvance
        }
    }

    fun testVetoRestoresTheSheetAndForcedOwnerRemovalCancelsDelayedDismissal() {
        show()
        val previousAutoAdvance = ui.mainClock.autoAdvance
        try {
            ui.mainClock.autoAdvance = false
            instrumentation.runOnMainSync { acceptDismiss.value = false }
            sendBack()
            ui.mainClock.advanceTimeBy(4_000)
            ui.waitForIdle()
            assertEquals(1, dismissals)
            assertEquals(0, disposals)
            node("motion-confirm").assertIsDisplayed()
            instrumentation.runOnMainSync { acceptDismiss.value = true }
            sendBack()
            ui.mainClock.advanceTimeBy(3_000)
            ui.waitForIdle()
            assertEquals(2, dismissals)
            assertEquals(1, disposals)
        } finally {
            ui.mainClock.autoAdvance = previousAutoAdvance
        }
        show()
        try {
            ui.mainClock.autoAdvance = false
            sendBack()
            instrumentation.runOnMainSync { open.value = false }
            ui.mainClock.advanceTimeBy(3_000)
            ui.waitForIdle()
            assertEquals(0, dismissals)
            assertEquals(1, disposals)
            assertEquals(0, fixture.opens.get())
        } finally {
            ui.mainClock.autoAdvance = previousAutoAdvance
        }
    }
}
