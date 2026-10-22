package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

class LinuxSettingsNavigationTest : WiredActivityTestCase() {
    private fun show(dark: Boolean = false, fontScale: Float = 1f, locationsEnabled: Boolean = true) {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        var tab by remember { mutableStateOf(LinuxSettingsTab.BASIC) }
                        var location by remember { mutableStateOf(LinuxFileLocation.WORKSPACE) }
                        Column(Modifier.width(320.dp)) {
                            LinuxFileLocationTabs(location, locationsEnabled) { location = it }
                            LinuxSettingsTabs(tab) { tab = it }
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
