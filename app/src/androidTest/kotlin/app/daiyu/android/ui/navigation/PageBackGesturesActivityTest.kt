package app.daiyu.android.ui.navigation

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceSettingsStore
import app.daiyu.android.ui.app.AgentAppTheme
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Text

class PageBackGesturesActivityTest : WiredActivityTestCase() {
    private var swipe by mutableStateOf(true)
    private var predictive by mutableStateOf(true)
    private var canBack by mutableStateOf(true)
    private var nested by mutableStateOf(false)
    private var backCount = 0
    private var nestedCount = 0
    private var rowClicks = 0
    private var sliderValue by mutableStateOf(0.2f)
    private var inputText by mutableStateOf("a text field with selectable content")
    private lateinit var horizontal: ScrollState

    private fun mount(horizontalChild: Boolean = false, clickableRows: Boolean = false, sliderChild: Boolean = false, textChild: Boolean = false) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    PageBackGestureHost("test-page", canBack, swipe, predictive,
                        dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() }) {
                        PageBackHandler(enabled = canBack) { backCount++ }
                        if (nested) PageBackHandler {
                            nestedCount++
                            nested = false
                        }
                        if (clickableRows) {
                            LazyColumn(Modifier.fillMaxSize().testTag("clickable-list")) {
                                items((0 until 24).toList()) { index ->
                                    Box(Modifier.fillMaxWidth().height(64.dp).testTag("clickable-row:$index")
                                        .clickable { rowClicks++ }.padding(16.dp)) { Text("设置行 $index") }
                                }
                            }
                        } else if (sliderChild) {
                            app.daiyu.android.ui.haptics.material.Slider(value = sliderValue,
                                onValueChange = { sliderValue = it },
                                modifier = Modifier.width(320.dp).testTag("gesture-slider"))
                        } else if (textChild) {
                            BasicTextField(value = inputText, onValueChange = { inputText = it },
                                modifier = Modifier.width(320.dp).height(120.dp).testTag("gesture-text"))
                        } else if (horizontalChild) {
                            horizontal = rememberScrollState(100)
                            Box(Modifier.width(320.dp).height(120.dp).testTag("horizontal-child")
                                .horizontalScroll(horizontal)) {
                                Box(Modifier.width(900.dp).height(120.dp)) { Text("scroll child") }
                            }
                        } else Text("retained page")
                    }
                }
            }
        }
        dismissIme()
    }

    private fun swipeBack(startFraction: Float = 0.02f, endFraction: Float = 0.80f) {
        ui.onNodeWithTag("page-back-surface").performTouchInput {
            swipe(Offset(width * startFraction, height * 0.6f),
                Offset(width * endFraction, height * 0.6f), 400)
        }
        ui.waitForIdle()
    }

    fun testSwipeCommitsOnceThroughTheInnermostHandlerAndTheSwitchAppliesImmediately() {
        nested = true
        mount()
        swipeBack()
        assertEquals(1, nestedCount)
        assertEquals(0, backCount)
        swipeBack()
        assertEquals(1, backCount)
        mainThread { swipe = false }
        swipeBack()
        assertEquals(1, backCount)
        mainThread { swipe = true; canBack = false }
        swipeBack()
        assertEquals(1, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testShortAndReverseSwipesDoNotNavigateAndRestoreThePage() {
        mount()
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        swipeBack(endFraction = 0.12f)
        swipeBack(startFraction = 0.80f, endFraction = 0.20f)
        assertEquals(0, backCount)
        val restored = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(initial.left - restored.left) <= 1f)
        assertTrue(abs(initial.width - restored.width) <= 1f)
        assertEquals(0, fixture.opens.get())
    }

    fun testMiddleOfPageSwipeNavigatesWithPredictionDisabled() {
        predictive = false
        mount()
        swipeBack(startFraction = 0.40f, endFraction = 0.95f)
        assertEquals(1, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testClickableSettingRowYieldsItsPressToPageSwipeWithoutFiringClick() {
        mount(clickableRows = true)
        ui.onNodeWithTag("clickable-row:2").performTouchInput {
            swipe(Offset(width * 0.40f, height / 2f), Offset(width * 0.95f, height / 2f), 400)
        }
        ui.waitForIdle()
        assertEquals(1, backCount)
        assertEquals(0, rowClicks)
        ui.onNodeWithTag("clickable-row:3").performClick()
        assertEquals(1, rowClicks)
        assertEquals(1, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testVerticalSettingListScrollStaysWithTheList() {
        mount(clickableRows = true)
        ui.onNodeWithTag("clickable-list").performTouchInput { swipeUp() }
        ui.waitForIdle()
        assertEquals(0, backCount)
        assertEquals(0, rowClicks)
        assertEquals(0, fixture.opens.get())
    }

    fun testSliderMovementStaysWithTheControl() {
        mount(sliderChild = true)
        ui.onNodeWithTag("gesture-slider").performTouchInput {
            swipe(Offset(width * 0.20f, height / 2f), Offset(width * 0.90f, height / 2f), 400)
        }
        ui.waitForIdle()
        assertTrue(sliderValue > 0.2f)
        assertEquals(0, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testLongPressTextSelectionDoesNotBecomePageBack() {
        mount(textChild = true)
        ui.onNodeWithTag("gesture-text").performTouchInput {
            down(Offset(width * 0.15f, height * 0.25f))
            advanceEventTime(700)
            moveTo(Offset(width * 0.90f, height * 0.25f), 200)
            up()
        }
        ui.waitForIdle()
        assertEquals("a text field with selectable content", inputText)
        assertEquals(0, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testPointerCancellationBeyondThresholdDoesNotNavigate() {
        mount()
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("page-back-surface").performTouchInput {
            down(Offset(width * 0.35f, height * 0.6f))
            moveTo(Offset(width * 0.80f, height * 0.6f), 200)
            cancel()
        }
        ui.waitForIdle()
        val restored = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(initial.left - restored.left) <= 1f)
        assertEquals(0, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testDisablingSwipeCancelsAnInFlightDrag() {
        mount()
        ui.onNodeWithTag("page-back-surface").performTouchInput {
            down(Offset(width * 0.35f, height * 0.6f))
            moveTo(Offset(width * 0.80f, height * 0.6f), 200)
        }
        mainThread { swipe = false }
        ui.onNodeWithTag("page-back-surface").performTouchInput { up() }
        ui.waitForIdle()
        assertEquals(0, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testHorizontalChildHasFirstRefusalOverPageBack() {
        mount(horizontalChild = true)
        ui.onNodeWithTag("horizontal-child").performTouchInput {
            swipe(Offset(8f, height / 2f), Offset(width * 0.80f, height / 2f), 400)
        }
        ui.waitForIdle()
        assertTrue(horizontal.value < 100)
        assertEquals(0, backCount)
        assertEquals(0, fixture.opens.get())
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testPredictiveProgressCancellationCommitAndLowApiFallback() {
        nested = true
        mount()
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        fun progress(edge: Int) {
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge)) }
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, edge)) }
        }
        progress(BackEventCompat.EDGE_LEFT)
        val moving = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        if (Build.VERSION.SDK_INT >= 34) {
            assertTrue("predictive initial=$initial moving=$moving", moving.width < initial.width)
            assertTrue(moving.center.x > initial.center.x)
        } else {
            assertTrue(abs(moving.width - initial.width) <= 1f)
            assertTrue(abs(moving.left - initial.left) <= 1f)
        }
        assertEquals(0, nestedCount)
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        val restored = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(initial.left - restored.left) <= 1f)
        assertTrue(abs(initial.width - restored.width) <= 1f)
        assertEquals(0, nestedCount)
        progress(BackEventCompat.EDGE_RIGHT)
        val fromRight = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        if (Build.VERSION.SDK_INT >= 34) assertTrue(fromRight.center.x < initial.center.x)
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, nestedCount)
        assertEquals(0, backCount)
        mainThread { predictive = false }
        progress(BackEventCompat.EDGE_LEFT)
        val disabled = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(initial.width - disabled.width) <= 1f)
        assertTrue(abs(initial.left - disabled.left) <= 1f)
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, backCount)
        assertEquals(0, fixture.opens.get())
    }

    fun testGesturePreferencesPersistIndependently() {
        val preferences = activity.getSharedPreferences("page-back-gestures-test", 0)
        assertTrue(preferences.edit().clear().commit())
        try {
            val store = AppearanceSettingsStore(preferences)
            val first = store.save(store.load().copy(swipeDismissEnabled = false, predictiveBackEnabled = true))
            assertEquals(first, AppearanceSettingsStore(preferences).load())
            val second = store.save(first.copy(swipeDismissEnabled = true))
            assertTrue(second.predictiveBackEnabled)
            val third = store.save(second.copy(predictiveBackEnabled = false))
            assertTrue(third.swipeDismissEnabled)
            assertEquals(third, AppearanceSettingsStore(preferences).load())
        } finally { assertTrue(preferences.edit().clear().commit()) }
        assertEquals(0, fixture.opens.get())
    }
}
