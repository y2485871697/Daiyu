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

    private fun show(dark: Boolean, bottomSheet: Boolean, seed: Int? = null) {
        mainThread {
            appearance.value = AppearanceSettings(
                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                paletteSeedColor = seed,
            )
            dismissals = 0
            outerOpen.value = true
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
                    Box(Modifier.fillMaxSize().background(resolveAppearanceColors(appearance.value, darkTheme).background)
                        .testTag("modal-page")) {
                        if (outerOpen.value) PreviewDialog(
                            onDismiss = { dismissals++; outerOpen.value = false }, bottomSheet = bottomSheet,
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

    private fun assertWhiteDialogIcons(tag: String) {
        awaitNodeWindowFocus(node(tag))
        val root = node(tag).assertIsDisplayed().fetchSemanticsNode().root as ViewRootForTest
        mainThread {
            val window = (root.view.parent as DialogWindowProvider).window
            assertNotSame(activity.window, window)
            assertFalse("Dimmed dialog must keep white status icons",
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
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

    fun testCenteredAndBottomOverlaysKeepWhiteIconsAcrossThemesAndExactBackgrounds() {
        for (dark in listOf(false, true)) for (seed in listOf(null, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            for (bottomSheet in listOf(false, true)) {
                show(dark, bottomSheet, seed)
                assertWhiteDialogIcons("modal-outer")
                closeOuter()
            }
        }
    }

    fun testNestedSheetReturnsToOuterWithoutBlackStatusIcons() {
        show(dark = false, bottomSheet = true)
        assertWhiteDialogIcons("modal-outer")
        node("modal-outer").performClick()
        assertWhiteDialogIcons("modal-inner")
        Espresso.pressBack()
        ui.waitUntil(5_000) { !innerOpen.value }
        node("modal-inner").assertDoesNotExist()
        assertEquals(0, dismissals)
        assertWhiteDialogIcons("modal-outer")
        closeOuter()
    }

    fun testThemeChangeWhileOpenKeepsWhiteDialogAndRestoresCurrentPageAppearance() {
        show(dark = true, bottomSheet = true)
        assertWhiteDialogIcons("modal-outer")
        mainThread { appearance.value = appearance.value.copy(themeMode = AppearanceThemeMode.LIGHT) }
        ui.waitForIdle()
        assertWhiteDialogIcons("modal-outer")
        closeOuter()
    }
}
