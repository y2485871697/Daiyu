package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import android.content.res.Configuration
import app.daiyu.android.data.model.AppearanceAccentColor
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.AppearanceSettingsScreen
import app.daiyu.android.ui.screens.settings.DaiyuSettingsScreen
import java.io.File

class SettingsChromeActivityTest : WiredActivityTestCase() {
    private fun showHome(events: MutableList<String>, dark: Boolean = false) {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                        Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("settings-host")) {
                            DaiyuSettingsScreen(onBack = { events.add("back") }, onRoute = { events.add(it.name) },
                                onOpenSubAgents = { events.add("subagents") }, onOpenAssistants = { events.add("assistants") },
                                onOpenAppearance = { events.add("appearance") })
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun capture(name: String) {
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(checkNotNull(activity.getExternalFilesDir(null)), "settings-ui")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    fun testHomeAt320DpKeepsPrimaryActionsReadableInBothThemes() {
        val events = mutableListOf<String>()
        for (dark in listOf(false, true)) {
            showHome(events, dark)
            val host = node("settings-host").assertWidthIsEqualTo(320.dp).fetchSemanticsNode().boundsInRoot
            for (title in listOf("模型提供商", "外观与主题", "助手", "子代理")) {
                val row = reveal("settings-row:$title", "设置").assertHasClickAction().assertIsEnabled()
                val bounds = row.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= host.left - 1 && bounds.right <= host.right + 1)
                assertTrue(bounds.height >= 48 * activity.resources.displayMetrics.density)
            }
            node("page:设置").performScrollToIndex(0)
            capture(if (dark) "settings-dark" else "settings-light")
        }
        reveal("settings-row:模型提供商", "设置").performClick()
        reveal("settings-row:外观与主题", "设置").performClick()
        reveal("settings-row:助手", "设置").performClick()
        reveal("settings-row:子代理", "设置").performClick()
        ui.runOnIdle { assertEquals(listOf("MODEL_SETTINGS", "appearance", "assistants", "subagents"), events) }
    }

    fun testSettingsCardsHaveNoOutlineOrRowDividersInBothThemes() {
        for (dark in listOf(false, true)) {
            showHome(mutableListOf(), dark)
            val first = node("settings-row:模型提供商").fetchSemanticsNode().boundsInRoot
            val second = node("settings-row:外观与主题").fetchSemanticsNode().boundsInRoot
            assertEquals(first.bottom, second.top, 0.1f)
            val pixels = ui.onRoot().captureToImage().toPixelMap()
            val density = activity.resources.displayMetrics.density
            val x = (first.left + 18 * density).toInt()
            val boundary = first.bottom.toInt()
            val surface = pixels[x, boundary - 4]
            for (y in boundary - 3..boundary + 3) {
                assertEquals("Row boundary must remain one continuous surface", surface, pixels[x, y])
            }
            val middle = ((first.left + first.right) / 2).toInt()
            val top = first.top.toInt()
            assertEquals("Card top must not draw an outline", pixels[middle, top + 4], pixels[middle, top])
        }
    }

    fun testSearchUsesOriginalButtonsAndUnwiredTapIsANoOp() {
        val events = mutableListOf<String>()
        showHome(events)
        node("settings-search").performTextReplacement("MCP")
        val mcp = reveal("settings-row:MCP 服务器", "设置").assertIsEnabled().assertHasClickAction()
        mcp.performClick()
        ui.runOnIdle { assertTrue(events.isEmpty()) }
        ui.onNodeWithText("待接入").assertDoesNotExist()
        node("settings-search-clear").performClick()
        node("settings-search").performTextReplacement("发送消息")
        ui.onNodeWithText("没有匹配的设置").assertExists()
        node("settings-search").performTextReplacement("Skills")
        reveal("settings-row:Skills", "设置").performClick()
        ui.runOnIdle { assertEquals(listOf("SKILLS"), events) }
    }

    fun testCategoryBackReturnsHomeAndKeepsOriginalReasoningEntry() {
        val events = mutableListOf<String>()
        showHome(events)
        reveal("settings-row:LLM 提供商", "设置").performClick()
        node("page:LLM 提供商").assertExists()
        reveal("settings-row:默认启用推理", "LLM 提供商").assertIsEnabled().performClick()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        node("page:设置").assertExists()
        ui.runOnIdle { assertTrue(events.isEmpty()) }
    }

    fun testAppearanceSelectionAndWholeRowSwitchUpdateControlledState() {
        var state by mutableStateOf(AppearanceSettings())
        mainThread {
            activity.setContent {
                AgentAppTheme(state, false) {
                    AppearanceSettingsScreen(state, onChange = { state = it }, onBack = {})
                }
            }
        }
        node("settings-radio:深色").performClick().assertIsSelected()
        reveal("settings-switch:纯黑背景", "外观与主题").performClick().assertIsOn()
        ui.runOnIdle {
            assertEquals(AppearanceThemeMode.DARK, state.themeMode)
            assertTrue(state.pureBlackEnabled)
        }
        reveal("settings-row:强调色", "外观与主题").performClick()
        ui.onNodeWithText("绿色").performClick()
        ui.runOnIdle { assertEquals(AppearanceAccentColor.GREEN, state.accentColor); assertTrue(state.monetEnabled) }
        reveal("settings-switch:消息时间戳", "外观与主题").performClick().assertIsOn()
        reveal("settings-switch:圆形加载指示器", "外观与主题").performClick().assertIsOff()
        reveal("settings-switch:仅消息生成前", "外观与主题").performClick().assertIsOn()
        ui.runOnIdle {
            assertFalse(state.morphLoadingIndicator)
            assertTrue(state.morphLoadingBeforeResponseOnly)
        }
        reveal("settings-switch:圆形加载指示器", "外观与主题").performClick().assertIsOn()
        ui.runOnIdle { assertTrue(state.morphLoadingBeforeResponseOnly) }
        node("page:外观与主题").performScrollToIndex(0)
        capture("appearance-dark")
    }

    fun testToolsKeepOnlyRequestedEntriesAndSkillsLivesInExtensions() {
        val events = mutableListOf<String>()
        showHome(events)
        reveal("settings-row:数据备份", "设置").assertExists()
        node("settings-row:Skills").assertDoesNotExist()
        reveal("settings-row:工具", "设置").performClick()
        val rows = ui.onAllNodes(SemanticsMatcher("settings rows") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("settings-row:") == true
        }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix("settings-row:") }
        assertEquals(listOf("工作区与文件", "终端", "浏览器", "Linux 工具环境", "Agent任务偏好"), rows)
        capture("tools-refined")
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        reveal("settings-row:扩展", "设置").performClick()
        reveal("settings-row:Skills", "扩展").performClick()
        ui.runOnIdle { assertEquals(listOf("SKILLS"), events) }
    }

    fun testSystemBarIconsFollowThemeAndActivityRecreation() {
        val preferences = activity.getSharedPreferences("appearance-settings", android.content.Context.MODE_PRIVATE)
        val hadTheme = preferences.contains("theme")
        val oldTheme = preferences.getString("theme", null)
        fun assertBars(dark: Boolean) {
            ui.waitForIdle()
            mainThread {
                val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertEquals(!dark, bars.isAppearanceLightStatusBars)
                assertEquals(!dark, bars.isAppearanceLightNavigationBars)
            }
        }
        try {
            openSettings()
            reveal("settings-row:外观与主题", "设置").performClick()
            for ((label, dark) in listOf("浅色" to false, "深色" to true)) {
                node("settings-radio:$label").performClick().assertIsSelected()
                assertBars(dark)
            }
            activity = recreateActivity(activity)
            assertBars(true)
            openSettings()
            reveal("settings-row:外观与主题", "设置").performClick()
            node("settings-radio:跟随系统").performClick().assertIsSelected()
            val systemDark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            assertBars(systemDark)
            node("settings-radio:浅色").performClick()
            backToChat(activity); backToChat(activity)
            assertBars(false)
        } finally {
            val editor = preferences.edit()
            if (hadTheme) editor.putString("theme", oldTheme) else editor.remove("theme")
            check(editor.commit())
        }
    }

    fun testAppearanceSurvivesActivityRecreation() {
        val preferences = activity.getSharedPreferences("appearance-settings", android.content.Context.MODE_PRIVATE)
        val previous = preferences.all.toMap()
        try {
            openSettings()
            reveal("settings-row:外观与主题", "设置").performClick()
            node("settings-radio:深色").performClick()
            val black = reveal("settings-switch:纯黑背景", "外观与主题")
            if (preferences.getBoolean("pure_black", false)) black.performClick()
            black.performClick().assertIsOn()
            activity = recreateActivity(activity)
            assertEquals("dark", preferences.getString("theme", null))
            assertTrue(preferences.getBoolean("pure_black", false))
            openSettings()
            reveal("settings-row:外观与主题", "设置").performClick()
            node("settings-radio:深色").assertIsSelected()
            reveal("settings-switch:纯黑背景", "外观与主题").assertIsOn()
        } finally {
            val editor = preferences.edit().clear()
            previous.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            check(editor.commit())
        }
    }
}
