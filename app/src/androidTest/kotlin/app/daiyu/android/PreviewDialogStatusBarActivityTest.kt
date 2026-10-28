package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.colorContentColor
import app.daiyu.android.ui.app.resolveAppearanceColors
import app.daiyu.android.ui.components.PreviewDialog
import app.daiyu.android.ui.haptics.material.TextButton

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class PreviewDialogStatusBarActivityTest : WiredActivityTestCase() {
    private val appearance = mutableStateOf(AppearanceSettings())
    private val outerOpen = mutableStateOf(false)
    private val innerOpen = mutableStateOf(false)
    private val thirdOpen = mutableStateOf(false)
    private val outerBottomSheet = mutableStateOf(false)
    private var dismissals = 0

    private fun show(dark: Boolean, bottomSheet: Boolean, pureBlack: Boolean = false,
        initiallyOpen: Boolean = true, backgroundOverride: Color? = null, scrim: Color? = null,
        siblingWindows: Boolean = false, nestedInnerTheme: Boolean = false) {
        mainThread {
            appearance.value = AppearanceSettings(
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                pureBlackEnabled = pureBlack,

            )
            dismissals = 0
            outerBottomSheet.value = bottomSheet
            outerOpen.value = initiallyOpen
            innerOpen.value = false
            thirdOpen.value = false
            activity.setContent {
                AgentAppTheme(appearance.value, applyInterfaceScale = true, onResolvedDarkModeChange = { resolvedDark ->
                    val background = resolveAppearanceColors(appearance.value, resolvedDark).background
                    val lightBars = colorContentColor(background) == Color.Black
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }) {
                    val darkTheme = appearance.value.themeMode == AppearanceThemeMode.DARK
                    Box(Modifier.fillMaxSize().background(backgroundOverride ?: resolveAppearanceColors(appearance.value, darkTheme).background)
                        .testTag("modal-page")) {
                        if (outerOpen.value) PreviewDialog(
                            onDismiss = { dismissals++; outerOpen.value = false }, bottomSheet = outerBottomSheet.value, scrimColor = scrim,
                        ) {
                            TextButton(onClick = { innerOpen.value = true },
                                modifier = Modifier.align(Alignment.Center).testTag("modal-outer")) { Text("打开第二层") }
                            Box(Modifier.align(Alignment.CenterStart).size(40.dp).background(Color.White)
                                .testTag("modal-parent-patch"))
                            if (!siblingWindows && innerOpen.value) InnerDialog()
                        }
                        if (siblingWindows && innerOpen.value) {
                            if (nestedInnerTheme) AgentAppTheme(appearance.value, applyInterfaceScale = true) { InnerDialog() }
                            else InnerDialog()
                        }
                        if (thirdOpen.value) PreviewDialog(onDismiss = { thirdOpen.value = false }, bottomSheet = true) {
                            Text("第三层", modifier = Modifier.align(Alignment.Center).testTag("modal-third"))
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    @Composable
    private fun InnerDialog() {
        PreviewDialog(onDismiss = { innerOpen.value = false }, bottomSheet = true) {
            Box(Modifier.align(Alignment.CenterEnd).size(40.dp).background(Color.White)
                .testTag("modal-inner-patch"))
            TextButton(onClick = { thirdOpen.value = true },
                modifier = Modifier.align(Alignment.Center).testTag("modal-inner")) { Text("第二层") }
        }
    }

    private fun originalDimAmount(tag: String): Float {
        val root = node(tag).fetchSemanticsNode().root as ViewRootForTest
        var amount = -1f
        mainThread {
            val window = (root.view.parent as DialogWindowProvider).window
            val attributes = window.context.obtainStyledAttributes(intArrayOf(android.R.attr.backgroundDimAmount))
            amount = try { attributes.getFloat(0, -1f) } finally { attributes.recycle() }
            assertTrue("Original Dialog theme supplies its dim amount", amount > 0f && amount <= 1f)
            assertEquals("Scrim owns dimming; native dim must not compound it", 0f, window.attributes.dimAmount, 0f)
        }
        return amount
    }

    private fun assertHostBars() {
        mainThread {
            val dark = appearance.value.themeMode == AppearanceThemeMode.DARK
            val lightBars = colorContentColor(resolveAppearanceColors(appearance.value, dark).background) == Color.Black
            val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertEquals(lightBars, bars.isAppearanceLightStatusBars)
            assertEquals(lightBars, bars.isAppearanceLightNavigationBars)
        }
    }

    private fun assertDialogWindowAndHostBars(tag: String) {
        awaitNodeWindowFocus(node(tag))
        val root = node(tag).assertIsDisplayed().fetchSemanticsNode().root as ViewRootForTest
        mainThread {
            val window = (root.view.parent as DialogWindowProvider).window
            assertNotSame(activity.window, window)
        }
        assertHostBars()
    }

    private fun closeOuter() {
        Espresso.pressBack()
        ui.waitUntil(5_000) { !outerOpen.value }
        node("modal-outer").assertDoesNotExist()
        awaitNodeWindowFocus(node("modal-page"))
        assertHostBars()
        assertEquals(1, dismissals)
        assertEquals(0, fixture.opens.get())
    }

    fun testCenteredAndBottomOverlaysRetainHostBarsAcrossThemesAndExactBackgrounds() {
        for (dark in listOf(false, true)) for (pureBlack in listOf(false, true)) {
            for (bottomSheet in listOf(false, true)) {
                show(dark, bottomSheet, pureBlack)
                assertDialogWindowAndHostBars("modal-outer")
                closeOuter()
            }
        }
    }

    fun testNestedSheetReturnsToOuterThenRestoresHostBars() {
        show(dark = false, bottomSheet = true, backgroundOverride = Color.White)
        assertDialogWindowAndHostBars("modal-outer")
        val alpha = originalDimAmount("modal-outer")
        assertCompositedShade("modal-page", 1f - alpha, topTag = "modal-outer")
        assertCompositedShade("modal-parent-patch", 1f, topTag = "modal-outer")
        node("modal-outer").performClick()
        assertDialogWindowAndHostBars("modal-inner")
        awaitDialogEnter(node("modal-inner"))
        assertCompositedShade("modal-page", 1f - alpha, topTag = "modal-inner")
        assertCompositedShade("modal-parent-patch", 1f - alpha, topTag = "modal-inner")
        Espresso.pressBack()
        ui.waitUntil(5_000) { !innerOpen.value }
        node("modal-inner").assertDoesNotExist()
        assertEquals(0, dismissals)
        assertDialogWindowAndHostBars("modal-outer")
        assertCompositedShade("modal-page", 1f - alpha, topTag = "modal-outer")
        assertCompositedShade("modal-parent-patch", 1f, topTag = "modal-outer")
        closeOuter()
    }

    fun testSiblingWindowsShareShadeAcrossThreeLayersAndNestedThemes() {
        for (bottomSheet in listOf(false, true)) for (nestedTheme in listOf(false, true)) {
            show(dark = false, bottomSheet = bottomSheet, backgroundOverride = Color.White,
                siblingWindows = true, nestedInnerTheme = nestedTheme)
            assertDialogWindowAndHostBars("modal-outer")
            if (bottomSheet) awaitDialogEnter(node("modal-outer"))
            val alpha = originalDimAmount("modal-outer")
            assertCompositedShade("modal-page", 1f - alpha, topTag = "modal-outer")
            node("modal-outer").performClick()
            assertDialogWindowAndHostBars("modal-inner")
            awaitDialogEnter(node("modal-inner"))
            assertCompositedShade("modal-page", 1f - alpha, topTag = "modal-inner")
            assertCompositedShade("modal-parent-patch", 1f - alpha, topTag = "modal-inner")
            if (bottomSheet) {
                mainThread { outerBottomSheet.value = false }
                assertCompositedShade("modal-parent-patch", 1f - alpha, topTag = "modal-inner")
            }
            assertCompositedShade("modal-inner-patch", 1f, topTag = "modal-inner")
            node("modal-inner").performClick()
            assertDialogWindowAndHostBars("modal-third")
            awaitDialogEnter(node("modal-third"))
            assertCompositedShade("modal-parent-patch", 1f - alpha, topTag = "modal-third")
            assertCompositedShade("modal-inner-patch", 1f - alpha, topTag = "modal-third")
            assertCompositedShade("modal-page", 1f - alpha, topTag = "modal-third")
            Espresso.pressBack()
            ui.waitUntil(5_000) { !thirdOpen.value }
            assertDialogWindowAndHostBars("modal-inner")
            assertCompositedShade("modal-parent-patch", 1f - alpha, topTag = "modal-inner")
            assertCompositedShade("modal-inner-patch", 1f, topTag = "modal-inner")
            assertCompositedShade("modal-page", 1f - alpha, topTag = "modal-inner")
            Espresso.pressBack()
            ui.waitUntil(5_000) { !innerOpen.value }
            assertDialogWindowAndHostBars("modal-outer")
            assertCompositedShade("modal-page", 1f - alpha, topTag = "modal-outer")
            assertCompositedShade("modal-parent-patch", 1f, topTag = "modal-outer")
            closeOuter()
            assertCompositedShade("modal-page", 1f)
        }
    }

    fun testRemovingMiddleOrAllWindowsLeavesNoResidualShade() {
        show(dark = false, bottomSheet = true, backgroundOverride = Color.White, siblingWindows = true)
        assertDialogWindowAndHostBars("modal-outer")
        awaitDialogEnter(node("modal-outer"))
        val alpha = originalDimAmount("modal-outer")
        node("modal-outer").performClick()
        assertDialogWindowAndHostBars("modal-inner")
        awaitDialogEnter(node("modal-inner"))
        node("modal-inner").performClick()
        assertDialogWindowAndHostBars("modal-third")
        awaitDialogEnter(node("modal-third"))
        mainThread { innerOpen.value = false }
        ui.waitForIdle()
        node("modal-inner").assertDoesNotExist()
        assertCompositedShade("modal-parent-patch", 1f - alpha, topTag = "modal-third")
        assertCompositedShade("modal-page", 1f - alpha, topTag = "modal-third")
        mainThread { outerOpen.value = false }
        ui.waitForIdle()
        node("modal-outer").assertDoesNotExist()
        assertCompositedShade("modal-page", 1f - alpha, topTag = "modal-third")
        mainThread { thirdOpen.value = false }
        ui.waitForIdle()
        awaitNodeWindowFocus(node("modal-page"))
        assertCompositedShade("modal-page", 1f)
        assertEquals(0, dismissals)
        assertEquals(0, fixture.opens.get())
    }

    fun testThemeChangeWhileOpenRestoresCurrentPageAppearance() {
        show(dark = true, bottomSheet = true)
        assertDialogWindowAndHostBars("modal-outer")
        mainThread { appearance.value = appearance.value.copy(themeMode = AppearanceThemeMode.LIGHT) }
        ui.waitForIdle()
        assertDialogWindowAndHostBars("modal-outer")
        closeOuter()
    }

    private fun assertCompositedShade(tag: String, expected: Float, topTag: String = tag) {
        var sampled = emptyList<Color>()
        ui.waitUntil(5_000) {
            val capture = if (topTag == tag) node(tag) else {
                val root = node(topTag).fetchSemanticsNode().root
                ui.onNode(hasTestTag("preview-dialog-viewport") and
                    SemanticsMatcher("viewport in the focused window") { it.root === root }, useUnmergedTree = true)
            }
            // Capture the focused top window, but sample the requested lower window's screen coordinates.
            val pixels = captureScreenNode(capture).toPixelMap()
            val target = node(tag).fetchSemanticsNode()
            val viewport = capture.fetchSemanticsNode()
            val targetBounds = target.boundsInRoot.translate(target.positionOnScreen - target.positionInRoot)
            val viewportBounds = viewport.boundsInRoot.translate(viewport.positionOnScreen - viewport.positionInRoot)
            val x = (targetBounds.center.x - viewportBounds.left).roundToInt()
            val y = (targetBounds.top + targetBounds.height / 5f - viewportBounds.top).roundToInt()
            assertTrue("Sample stays in the captured window", x in 1 until pixels.width - 1 && y in 1 until pixels.height - 1)
            sampled = (-1..1).flatMap { dy -> (-1..1).map { dx -> pixels[x + dx, y + dy] } }
            sampled.all { abs(it.red - expected) < .025f && abs(it.green - expected) < .025f &&
                abs(it.blue - expected) < .025f }
        }
        for (color in sampled) {
            assertEquals(expected, color.red, .025f)
            assertEquals(expected, color.green, .025f)
            assertEquals(expected, color.blue, .025f)
        }
    }

    fun testDefaultScrimMatchesOriginalDialogThemeAndCustomScrimRemainsIndependent() {
        for (bottomSheet in listOf(false, true)) for (custom in listOf(false, true)) {
            val customAlpha = .35f
            show(dark = false, bottomSheet = bottomSheet, initiallyOpen = false,
                backgroundOverride = Color.White, scrim = if (custom) Color.Black.copy(alpha = customAlpha) else null)
            assertCompositedShade("modal-page", 1f)
            mainThread { outerOpen.value = true }
            assertDialogWindowAndHostBars("modal-outer")
            if (bottomSheet) awaitDialogEnter(node("modal-outer"))
            val originalAlpha = originalDimAmount("modal-outer")
            assertCompositedShade("preview-dialog-viewport", 1f - if (custom) customAlpha else originalAlpha)
            closeOuter()
            assertCompositedShade("modal-page", 1f)
        }
    }
}
