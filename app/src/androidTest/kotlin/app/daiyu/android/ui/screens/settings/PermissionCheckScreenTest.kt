package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme

class PermissionCheckScreenTest : WiredActivityTestCase() {
    private class Access : PermissionCheckAccess {
        var allowed = false
        var fail = false
        val opened = mutableListOf<PermissionCheckItem>()
        override fun granted(item: PermissionCheckItem): Boolean {
            check(!fail)
            return allowed
        }
        override fun open(item: PermissionCheckItem) { opened += item }
    }
    private fun show(access: Access, dark: Boolean = false, scale: Float = 1f) {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    key(dark, scale) {
                        AgentAppTheme(AppearanceSettings(
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                            interfaceScale = scale), true) {
                            Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("permission-host")) {
                                PermissionCheckScreen({}, access = access)
                            }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }
    private fun permission(item: PermissionCheckItem) = reveal("permission-check:${item.name.lowercase()}", "权限检查")

    fun testUsesDaiyuPageAndKeepsAllRowsReachableInBothThemesAndScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            val access = Access()
            show(access, dark, scale)
            val host = node("permission-host").fetchSemanticsNode().boundsInRoot
            for (item in PermissionCheckItem.entries) {
                val row = permission(item).assertHasClickAction().assertIsEnabled()
                val bounds = row.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= host.left && bounds.right <= host.right)
                row.performClick()
            }
            ui.runOnIdle { assertEquals(PermissionCheckItem.entries.toList(), access.opened) }
            reveal("permission-check:running-mode", "权限检查").assertHasClickAction().performClick()
            node("page:运行模式").assertExists()
            backToPage(activity, "page:权限检查")
            ui.onNodeWithText("Root 与系统增强").assertDoesNotExist()
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testResumeRefreshesSystemStatusWithoutOpeningSettingsOrReportingFailuresAsGrants() {
        val access = Access()
        show(access)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("未授权").fetchSemanticsNodes().isNotEmpty() }
        permission(PermissionCheckItem.BACKGROUND).assertTextContains("未授权")
        realPauseResume(activity, atPause = { access.allowed = true })
        ui.waitUntil(5_000) { ui.onAllNodesWithText("已授权").fetchSemanticsNodes().isNotEmpty() }
        permission(PermissionCheckItem.BACKGROUND).assertTextContains("已授权")
        realPauseResume(activity, atPause = { access.fail = true })
        ui.waitUntil(5_000) { ui.onAllNodesWithText("未确认").fetchSemanticsNodes().isNotEmpty() }
        permission(PermissionCheckItem.BACKGROUND).assertTextContains("未确认")
        assertTrue(access.opened.isEmpty())
        assertEquals(0, fixture.opens.get())
    }
}
