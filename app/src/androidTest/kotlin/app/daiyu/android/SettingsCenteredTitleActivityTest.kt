package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.haptics.miuix.IconButton
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.settings.SettingsScaffold
import app.daiyu.android.ui.screens.settings.SettingsScaffoldPage
import top.yukonga.miuix.kmp.basic.Text

class SettingsCenteredTitleActivityTest : WiredActivityTestCase() {
    private fun assertTitleInTopRow(title: String) {
        ui.onAllNodesWithText(title, useUnmergedTree = true).assertCountEquals(1)
        val text = ui.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val host = node("settings-title-host").fetchSemanticsNode().boundsInRoot
        val back = ui.onNodeWithContentDescription(activity.getString(R.string.action_back))
            .assertHasClickAction().fetchSemanticsNode().boundsInRoot
        assertEquals(host.center.x, text.center.x, 2f)
        assertEquals(back.center.y, text.center.y, 2f)
        assertTrue(text.left >= host.left && text.right <= host.right)
        assertTrue(text.right <= back.left || text.left >= back.right)
    }

    fun testListTitlesStayCenteredBesideBackAndActionBeforeAndAfterScrolling() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f))
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                val events = mutableListOf<String>()
                val title = "外观与主题"
                mainThread {
                    activity.setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f),
                            LocalLayoutDirection provides direction) {
                            AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                                themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), true) {
                                Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("settings-title-host")) {
                                    SettingsScaffoldPage(title, onBack = { events.add("back") }, actions = {
                                        IconButton(onClick = { events.add("action") },
                                            modifier = Modifier.size(48.dp).testTag("settings-title-action")) {
                                            DaiyuIcon(DaiyuIcons.Refresh, "刷新")
                                        }
                                    }) {
                                        items(30) { index -> Text("设置项目 $index", Modifier.padding(20.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
                ui.waitForIdle()
                assertTitleInTopRow(title)
                val action = node("settings-title-action").assertHeightIsAtLeast((48f * scale).dp).fetchSemanticsNode().boundsInRoot
                val text = ui.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(text.right <= action.left || text.left >= action.right)
                node("page:$title").performScrollToIndex(29)
                assertTitleInTopRow(title)
                node("settings-title-action").performTouchInput { click() }
                ui.onNodeWithContentDescription(activity.getString(R.string.action_back)).performTouchInput { click() }
                ui.runOnIdle { assertEquals(listOf("action", "back"), events) }
            }
    }

    fun testCustomContentSettingsUseTheSameTopRowWithoutALargeTitleBelow() {
        val events = mutableListOf<String>()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), false) {
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("settings-title-host")) {
                        SettingsScaffold("语音", onBack = { events.add("back") }) { padding, _, _ ->
                            Column(Modifier.padding(padding)) { Text("语音设置内容") }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        assertTitleInTopRow("语音")
        ui.onNodeWithText("语音设置内容").assertIsDisplayed()
        ui.onNodeWithContentDescription(activity.getString(R.string.action_back)).performTouchInput { click() }
        ui.runOnIdle { assertEquals(listOf("back"), events) }
    }
}
