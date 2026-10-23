package app.daiyu.android.ui.navigation

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
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
import androidx.compose.runtime.DisposableEffect
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
    private var route by mutableStateOf("previous-page")
    private var previewAllowed by mutableStateOf(true)
    private var navigateOnBack = false
    private var nested by mutableStateOf(false)
    private var nestedPreview = true
    private var backCount = 0
    private var nestedCount = 0
    private var rowClicks = 0
    private var sliderValue by mutableStateOf(0.2f)
    private var inputText by mutableStateOf("a text field with selectable content")
    private lateinit var horizontal: ScrollState

    private fun mount(horizontalChild: Boolean = false, clickableRows: Boolean = false, sliderChild: Boolean = false, textChild: Boolean = false, capturePreviousPage: Boolean = false) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val renderedRoute = route
                    PageBackGestureHost(renderedRoute, canBack, swipe, predictive,
                        dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                        backTargetKey = if (renderedRoute == "test-page") "previous-page" else null) {
                        PageBackHandler(enabled = canBack, previewEnabled = previewAllowed) {
                            backCount++
                            if (navigateOnBack) route = "previous-page"
                        }
                        if (nested) PageBackHandler(previewEnabled = nestedPreview) {
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
                        } else Box(Modifier.fillMaxSize().background(if (renderedRoute == "previous-page") Color.Red else Color.Blue)) {
                            Text(renderedRoute)
                        }
                    }
                }
            }
        }
        dismissIme()
        ui.waitForIdle()
        if (capturePreviousPage) {
            val sourceImage = captureScreenNode(ui.onNodeWithTag("page-back-surface"))
            logPreviewPixels("source", sourceImage)
            val sourcePixels = sourceImage.toPixelMap()
            val source = sourcePixels[2, sourcePixels.height / 2]
            assertTrue("source previous page must be red, actual=$source", source.red > 0.9f && source.blue < 0.1f)
        }
        mainThread { route = "test-page" }
        ui.waitForIdle()
    }

    fun testExplicitContentKeyKeepsLiveCompositionUntilContentActuallyChanges() {
        var mounts = 0
        var disposals = 0
        mainThread {
            route = "settings"
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val renderedRoute = route
                    PageBackGestureHost(
                        routeKey = renderedRoute,
                        canNavigateBack = true,
                        swipeDismissEnabled = true,
                        predictiveBackEnabled = true,
                        dispatchBack = { activity.onBackPressedDispatcher.onBackPressed() },
                        contentKey = if (renderedRoute == "assistant-edit") "settings" else renderedRoute,
                    ) {
                        DisposableEffect(Unit) {
                            mounts++
                            onDispose { disposals++ }
                        }
                        Box(Modifier.fillMaxSize().testTag("keyed-live-content")) { Text(renderedRoute) }
                    }
                }
            }
        }
        dismissIme()
        ui.waitForIdle()
        val original = ui.onNodeWithTag("keyed-live-content").fetchSemanticsNode().id
        assertEquals(1, mounts)
        assertEquals(0, disposals)
        for (next in listOf("assistant-edit", "settings", "assistant-edit", "settings")) {
            mainThread { route = next }
            ui.waitForIdle()
            assertEquals(original, ui.onNodeWithTag("keyed-live-content").fetchSemanticsNode().id)
            assertEquals(1, mounts)
            assertEquals(0, disposals)
        }
        mainThread { route = "other-page" }
        ui.waitForIdle()
        assertTrue(original != ui.onNodeWithTag("keyed-live-content").fetchSemanticsNode().id)
        assertEquals(2, mounts)
        assertEquals(1, disposals)
        assertEquals(0, fixture.opens.get())
    }

    fun testDefaultContentKeyStillFollowsRouteKey() {
        mount()
        val original = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().id
        mainThread { route = "other-page" }
        ui.waitForIdle()
        assertTrue(original != ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().id)
        assertEquals(0, fixture.opens.get())
    }

    private fun logPreviewPixels(phase: String, image: ImageBitmap) {
        val pixels = image.toPixelMap()
        var red = 0
        var blue = 0
        var white = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > 0.9f && color.blue < 0.1f) red++
            if (color.blue > 0.9f && color.red < 0.1f) blue++
            if (color.red > 0.9f && color.green > 0.9f && color.blue > 0.9f) white++
        }
        android.util.Log.i("DaiyuBackPreview", "$phase route=$route image=${pixels.width}x${pixels.height} " +
            "red=$red blue=$blue white=$white edge=${pixels[2, pixels.height / 2]}")
        val directory = java.io.File(checkNotNull(activity.getExternalFilesDir(null)), "settings-ui")
        check(directory.isDirectory || directory.mkdirs())
        java.io.File(directory, "page-back-$phase.png").outputStream().use {
            check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
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

    @OptIn(ExperimentalActivityApi::class)
    fun testStateChangeRejectsAnInFlightPredictionAndNextGestureStillCommits() {
        if (Build.VERSION.SDK_INT < 34) return
        mount()
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, BackEventCompat.EDGE_LEFT)) }
        mainThread { swipe = false }
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(0, backCount)
        ui.onNodeWithTag("page-back-target").assertDoesNotExist()
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, BackEventCompat.EDGE_LEFT)) }
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, backCount)
        assertEquals(0, fixture.opens.get())
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testPreviousPagePreviewCancelAndCommittedDeparture() {
        if (Build.VERSION.SDK_INT < 34) return
        val drawingEnabled = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        try {
            // Keep the recorded source alive across the route change and both previews.
            if (!drawingEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
            navigateOnBack = true
            mount(capturePreviousPage = true)
            ui.mainClock.autoAdvance = false
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, BackEventCompat.EDGE_LEFT)) }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithTag("page-back-target").assertExists()
            val targetNode = ui.onNodeWithTag("page-back-target")
            val previewImage = captureScreenNode(targetNode)
            logPreviewPixels("target", previewImage)
            android.util.Log.i("DaiyuBackPreview", "target=${targetNode.fetchSemanticsNode().boundsInRoot} " +
                "surface=${ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot}")
            val previewPixels = previewImage.toPixelMap()
            val backdrop = previewPixels[2, previewPixels.height / 2]
            assertTrue("recorded previous page must be red, actual=$backdrop", backdrop.red > 0.9f && backdrop.blue < 0.1f)
            assertEquals("test-page", route)
            assertEquals(0, backCount)
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            ui.mainClock.advanceTimeBy(1000)
            ui.onNodeWithTag("page-back-target").assertDoesNotExist()
            assertEquals(0, backCount)
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_RIGHT)) }
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, BackEventCompat.EDGE_RIGHT)) }
            ui.mainClock.advanceTimeBy(32)
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            ui.mainClock.advanceTimeByFrame()
            ui.onNodeWithTag("page-back-departure").assertExists()
            assertEquals("previous-page", route)
            assertEquals(1, backCount)
            ui.mainClock.advanceTimeBy(240)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = true
            if (!drawingEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    @OptIn(ExperimentalActivityApi::class)
    fun testLocalPreviewSuppressionUnregistersWhenNestedHandlerLeaves() {
        if (Build.VERSION.SDK_INT < 34) return
        nested = true
        nestedPreview = false
        mount()
        val initial = ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot
        fun begin() {
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)) }
            mainThread { activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, 0.6f, BackEventCompat.EDGE_LEFT)) }
        }
        begin()
        ui.onNodeWithTag("page-back-target").assertDoesNotExist()
        assertEquals(initial, ui.onNodeWithTag("page-back-surface").fetchSemanticsNode().boundsInRoot)
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        mainThread { nested = false }
        ui.waitForIdle()
        begin()
        ui.onNodeWithTag("page-back-target").assertExists()
        mainThread { activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        assertEquals(0, backCount)
        assertEquals(0, nestedCount)
    }

    fun testHorizontalSwipeCommitsTheRecordedDepartureWithPredictionDisabled() {
        predictive = false
        navigateOnBack = true
        val drawingEnabled = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        try {
            if (!drawingEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
            mount(capturePreviousPage = true)
            ui.mainClock.autoAdvance = false
            ui.onNodeWithTag("page-back-surface").performTouchInput {
                down(Offset(width * 0.35f, height * 0.6f))
                moveTo(Offset(width * 0.90f, height * 0.6f), 200)
            }
            ui.mainClock.advanceTimeBy(32)
            val target = ui.onNodeWithTag("page-back-target").assertExists()
            val targetPixels = captureScreenNode(target).toPixelMap()
            val backdrop = targetPixels[2, targetPixels.height / 2]
            assertTrue("swipe previous page must be red, actual=$backdrop", backdrop.red > 0.9f && backdrop.blue < 0.1f)
            ui.onNodeWithTag("page-back-surface").performTouchInput { up() }
            ui.mainClock.advanceTimeByFrame()
            assertEquals("previous-page", route)
            assertEquals(1, backCount)
            val departure = ui.onNodeWithTag("page-back-departure").assertExists()
            val departurePixels = captureScreenNode(departure).toPixelMap()
            val outgoing = departurePixels[departurePixels.width / 2, departurePixels.height / 2]
            assertTrue("swipe departure must be blue, actual=$outgoing", outgoing.blue > 0.9f && outgoing.red < 0.1f)
            ui.mainClock.advanceTimeBy(240)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = true
            if (!drawingEnabled) androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    fun testHorizontalSwipeWithPredictionEnabledKeepsItsCommitUntilDispatch() {
        predictive = true
        navigateOnBack = true
        mount()
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag("page-back-surface").performTouchInput {
                down(Offset(width * 0.35f, height * 0.6f))
                moveTo(Offset(width * 0.90f, height * 0.6f), 200)
            }
            ui.mainClock.advanceTimeBy(32)
            ui.onNodeWithTag("page-back-target").assertExists()
            ui.onNodeWithTag("page-back-surface").performTouchInput { up() }
            ui.mainClock.advanceTimeByFrame()
            assertEquals("previous-page", route)
            assertEquals(1, backCount)
            ui.onNodeWithTag("page-back-departure").assertExists()
            ui.mainClock.advanceTimeBy(240)
            ui.onNodeWithTag("page-back-departure").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
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
