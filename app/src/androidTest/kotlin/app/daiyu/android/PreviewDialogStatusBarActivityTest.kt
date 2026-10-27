package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.abs
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
    private var dismissals = 0

    private fun show(dark: Boolean, bottomSheet: Boolean, seed: Int? = null,
        initiallyOpen: Boolean = true, backgroundOverride: Color? = null, scrim: Color? = null) {
        mainThread {
            appearance.value = AppearanceSettings(
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                paletteSeedColor = seed,
            )
            dismissals = 0
            outerOpen.value = initiallyOpen
            innerOpen.value = false
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
                            onDismiss = { dismissals++; outerOpen.value = false }, bottomSheet = bottomSheet, scrimColor = scrim,
                        ) {
                            TextButton(onClick = { innerOpen.value = true },
                                modifier = Modifier.align(Alignment.Center).testTag("modal-outer")) { Text("打开第二层") }
                            if (innerOpen.value) PreviewDialog(
                                onDismiss = { innerOpen.value = false }, bottomSheet = true,
                            ) {
                                Text("第二层", modifier = Modifier.align(Alignment.Center).testTag("modal-inner"))
                            }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
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
        for (dark in listOf(false, true)) for (seed in listOf(null, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            for (bottomSheet in listOf(false, true)) {
                show(dark, bottomSheet, seed)
                assertDialogWindowAndHostBars("modal-outer")
                closeOuter()
            }
        }
    }

    fun testNestedSheetReturnsToOuterThenRestoresHostBars() {
        show(dark = false, bottomSheet = true)
        assertDialogWindowAndHostBars("modal-outer")
        node("modal-outer").performClick()
        assertDialogWindowAndHostBars("modal-inner")
        Espresso.pressBack()
        ui.waitUntil(5_000) { !innerOpen.value }
        node("modal-inner").assertDoesNotExist()
        assertEquals(0, dismissals)
        assertDialogWindowAndHostBars("modal-outer")
        closeOuter()
    }

    fun testThemeChangeWhileOpenRestoresCurrentPageAppearance() {
        show(dark = true, bottomSheet = true)
        assertDialogWindowAndHostBars("modal-outer")
        mainThread { appearance.value = appearance.value.copy(themeMode = AppearanceThemeMode.LIGHT) }
        ui.waitForIdle()
        assertDialogWindowAndHostBars("modal-outer")
        closeOuter()
    }

    private fun assertCompositedShade(tag: String, expected: Float) {
        var sampled = emptyList<Color>()
        ui.waitUntil(5_000) {
            val pixels = captureScreenNode(node(tag)).toPixelMap()
            val x = pixels.width / 2
            val y = pixels.height / 5
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
            val root = node("modal-outer").fetchSemanticsNode().root as ViewRootForTest
            var originalAlpha = -1f
            mainThread {
                val window = (root.view.parent as DialogWindowProvider).window
                val attributes = window.context.obtainStyledAttributes(intArrayOf(android.R.attr.backgroundDimAmount))
                originalAlpha = try { attributes.getFloat(0, -1f) } finally { attributes.recycle() }
                assertTrue("Original Dialog theme supplies its dim amount", originalAlpha > 0f && originalAlpha <= 1f)
                assertEquals("Scrim owns dimming; native dim must not compound it", 0f, window.attributes.dimAmount, 0f)
            }
            assertCompositedShade("preview-dialog-viewport", 1f - if (custom) customAlpha else originalAlpha)
            closeOuter()
            assertCompositedShade("modal-page", 1f)
        }
    }
}
