package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

class LinuxSettingsNavigationTest : WiredActivityTestCase() {
    private lateinit var colors: Colors
    private lateinit var materialColors: ColorScheme

    private fun show(dark: Boolean = false, fontScale: Float = 1f, locationsEnabled: Boolean = true,
        primaryAccentColor: Int? = null) {
        val showKey = Any()
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                        primaryAccentColor = primaryAccentColor),
                        applyInterfaceScale = false) {
                        LinuxSettingsTheme {
                        val themeColors = MiuixTheme.colorScheme
                        val materialThemeColors = MaterialTheme.colorScheme
                        SideEffect { colors = themeColors; materialColors = materialThemeColors }
                        var tab by remember(showKey) { mutableStateOf(LinuxSettingsTab.BASIC) }
                        var location by remember(showKey) { mutableStateOf(LinuxFileLocation.WORKSPACE) }
                        Column(Modifier.width(320.dp)) {
                            LinuxFileLocationTabs(location, locationsEnabled) { location = it }
                            LinuxSettingsTabs(tab) { tab = it }
                        }
                        }
                    }
                }
            }
        }
    }

    fun testBottomNavigationHasTwoTabTargetsAndOneStableSelectionIndicator() {
        show()
        node("linux-tab:basic").assertIsSelected().assertTextContains("基本")
        node("linux-tab:files").assertIsNotSelected().assertTextContains("文件")
        ui.onNodeWithTag("linux-tab-indicator:basic", useUnmergedTree = true).assertExists()
        ui.onNodeWithTag("linux-tab-indicator:files", useUnmergedTree = true).assertDoesNotExist()
        val before = node("linux-tab:files").fetchSemanticsNode().boundsInRoot
        node("linux-tab:files").performClick().assertIsSelected()
        node("linux-tab:basic").assertIsNotSelected()
        ui.onNodeWithTag("linux-tab-indicator:files", useUnmergedTree = true).assertExists()
        ui.onNodeWithTag("linux-tab-indicator:basic", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(before, node("linux-tab:files").fetchSemanticsNode().boundsInRoot)
        for (tag in listOf("linux-tab:basic", "linux-tab:files")) {
            node(tag).assertHeightIsAtLeast(48.dp).assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        }
    }

    fun testFileLocationTabsPreserveSelectionAndDisabledState() {
        show(locationsEnabled = false)
        for (location in LinuxFileLocation.entries) {
            node("linux-files:${location.name.lowercase()}").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        }
        node("linux-files:workspace").assertIsSelected()
        show()
        node("linux-files:rootfs").performClick().assertIsSelected()
        node("linux-files:workspace").assertIsNotSelected()
        ui.onNodeWithTag("linux-files-indicator:rootfs", useUnmergedTree = true).assertExists()
        node("linux-files:shared").performClick().assertIsSelected()
    }

    fun testNavigationUsesThemeColorPairsBeforeAndAfterSelectionInBothThemes() {
        for (dark in listOf(false, true)) {
            show(dark = dark)
            ui.waitForIdle()
            assertEquals(Color(0xff82728f), colors.primary)
            assertEquals(Color.White, colors.onPrimary)
            assertPrimaryThemePairsMatch()
            assertColorPair(ui.onNodeWithTag("linux-tab-indicator:basic", useUnmergedTree = true),
                colors.primary, colors.onPrimary)
            assertColorPair(node("linux-tab:files"), colors.primaryContainer, colors.onPrimaryContainer)
            assertColorPair(node("linux-files:workspace"), colors.primary, colors.onPrimary)
            assertColorPair(node("linux-files:rootfs"), colors.primaryContainer, colors.onPrimaryContainer)

            node("linux-tab:files").performClick().assertIsSelected()
            node("linux-files:rootfs").performClick().assertIsSelected()
            assertColorPair(ui.onNodeWithTag("linux-tab-indicator:files", useUnmergedTree = true),
                colors.primary, colors.onPrimary)
            assertColorPair(node("linux-tab:basic"), colors.primaryContainer, colors.onPrimaryContainer)
            assertColorPair(node("linux-files:rootfs"), colors.primary, colors.onPrimary)
            assertColorPair(node("linux-files:workspace"), colors.primaryContainer, colors.onPrimaryContainer)
        }
    }

    fun testCustomPrimaryKeepsOriginalWhiteForegroundInBothThemes() {
        for (dark in listOf(false, true)) {
            for (accent in listOf(0xff000000.toInt(), 0xffe6c7f2.toInt(), 0xffffffff.toInt())) {
                val foreground = Color.White
                show(dark = dark, primaryAccentColor = accent)
                ui.waitForIdle()
                assertEquals(Color(accent), colors.primary)
                assertEquals(foreground, colors.onPrimary)
                assertPrimaryThemePairsMatch()
                assertColorPair(ui.onNodeWithTag("linux-tab-indicator:basic", useUnmergedTree = true),
                    colors.primary, foreground)
                assertColorPair(node("linux-files:workspace"), colors.primary, foreground)
                node("linux-tab:files").performClick().assertIsSelected()
                node("linux-files:rootfs").performClick().assertIsSelected()
                assertColorPair(ui.onNodeWithTag("linux-tab-indicator:files", useUnmergedTree = true),
                    colors.primary, foreground)
                assertColorPair(node("linux-files:rootfs"), colors.primary, foreground)
            }
        }
    }

    private fun assertPrimaryThemePairsMatch() {
        assertEquals(colors.primary, colors.primaryVariant)
        assertEquals(colors.onPrimary, colors.onPrimaryVariant)
        assertEquals(colors.primary, materialColors.primary)
        assertEquals(colors.onPrimary, materialColors.onPrimary)
        assertEquals(colors.primaryContainer, materialColors.primaryContainer)
        assertEquals(colors.onPrimaryContainer, materialColors.onPrimaryContainer)
    }

    private fun assertColorPair(target: SemanticsNodeInteraction, background: Color, foreground: Color) {
        val pixels = target.captureToImage().toPixelMap()
        fun Color.matches(expected: Color) = abs(red - expected.red) < .035f &&
            abs(green - expected.green) < .035f && abs(blue - expected.blue) < .035f
        var backgroundCount = 0
        var foregroundCount = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (pixel.matches(background)) backgroundCount++
            if (pixel.matches(foreground)) foregroundCount++
        }
        assertTrue("Missing theme background $background", backgroundCount > pixels.width * pixels.height * .04f)
        assertTrue("Missing paired theme foreground $foreground", foregroundCount > 8)
    }

    fun testCompactNavigationRemainsVisibleAt320DpWithLargeLabelsInBothThemes() {
        for (dark in listOf(false, true)) {
            show(dark = dark, fontScale = 1.3f)
            node("linux-tab:basic").assertIsDisplayed().assertTextContains("基本")
            node("linux-tab:files").assertIsDisplayed().assertTextContains("文件")
            node("linux-files:workspace").assertIsDisplayed().assertTextContains("工作区")
            node("linux-files:rootfs").assertIsDisplayed().assertTextContains("Rootfs")
            node("linux-files:shared").assertIsDisplayed().assertTextContains("共享文件夹")
            ui.onAllNodesWithText("基本").assertCountEquals(1)
            ui.onAllNodesWithText("文件").assertCountEquals(1)
        }
    }
}
