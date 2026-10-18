package app.daiyu.android.ui.screens.settings

import app.daiyu.android.model.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPresentationTest {
    @Test
    fun retainedSettingsHaveUniqueIdsAndNineGroups() {
        val entries = SettingsCatalog.entries
        // Current inventory includes independent generation/vision, P05 and platform tools.
        assertEquals(38, entries.size)
        assertTrue(entries.none { it.id in setOf("permission_check", "accessibility_keep") })
        assertTrue(entries.map { it.id }.containsAll(setOf("platform_tools", "accessibility", "voice", "donate")))
        assertEquals(app.daiyu.android.ui.FeatureRoute.BALANCE_SETTINGS, SettingsCatalog.entry("balance").route)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertEquals(9, SettingsCatalog.groups.size)
        assertEquals(listOf("platform_tools", "workspace", "terminal", "browser", "linux", "task_surface"),
            SettingsCatalog.groups.single { it.id == "tools" }.entries.map { it.id })
        assertEquals(listOf("extensions"), SettingsCatalog.groups.filter { group ->
            group.entries.any { it.id == "skills" }
        }.map { it.id })
        assertTrue(SettingsCatalog.groups.none { it.id in setOf("system", "vendor", "gemini", "circle") })
        for (removed in listOf("系统助手", "小布", "Gemini", "一圈即搜", "Hey Google")) {
            assertTrue(filterSettingsGroups(removed).isEmpty())
        }
        assertTrue(entries.all { it.title.isNotBlank() && it.icon.name.isNotBlank() })
        assertEquals(SettingsCatalog.visibleGroups(), filterSettingsGroups("  "))
    }

    @Test
    fun productSettingsHideRemovedEntriesInAllModes() {
        val hiddenIds = setOf(
            "balance", "generation", "platform_tools", "browser", "overlay", "enhancements", "accessibility",
        )
        val hiddenTitles = listOf(
            "供应商余额", "独立生成模型", "平台只读", "浏览器", "悬浮窗权限", "Root 与系统增强", "无障碍",
        )
        for (engineeringUi in listOf(false, true)) {
            val groups = SettingsCatalog.visibleGroups(engineeringUi = engineeringUi)
            val entries = groups.flatMap { it.entries }
            assertEquals(31, entries.size)
            assertTrue(entries.none { it.id in hiddenIds })
            assertTrue(groups.none { it.id == "permissions" })
            assertTrue(entries.map { it.id }.containsAll(setOf(
                "task_surface", "linux", "voice", "donate",
            )))
            for (title in hiddenTitles) {
                assertTrue(filterSettingsGroups(title, groups).isEmpty())
            }
        }
        for (title in hiddenTitles) {
            assertTrue(filterSettingsGroups(title).isEmpty())
        }
        assertEquals(SettingsCatalog.visibleGroups(), SettingsCatalog.visibleGroups(engineeringUi = true))
    }

    @Test
    fun chatCommandsAreNotSettingsButtons() {
        val titles = SettingsCatalog.entries.map { it.title }.toSet()
        assertTrue(titles.containsAll(setOf("模型提供商", "默认启用推理", "MCP 服务器", "导出日志")))
        assertTrue(titles.intersect(setOf("发送消息", "暂停与继续", "停止回复", "追加与追问",
            "发送图片", "发送视频", "侧边栏", "聊天记录搜索", "搜索会话")).isEmpty())
    }

    @Test
    fun searchMatchesOriginalTitlesAndPreservesTheirOrder() {
        assertEquals(listOf("mcp"), filterSettingsGroups(" McP ").flatMap { it.entries }.map { it.id })
        assertEquals(listOf("providers", "vision", "title_model"),
            filterSettingsGroups("模型").flatMap { it.entries }.map { it.id })
        assertTrue(filterSettingsGroups("R03").isEmpty())
        assertTrue(filterSettingsGroups("not-a-setting").isEmpty())
    }

    @Test
    fun protocolSwitchClearsSecretsAndRequiresFreshAuthorization() {
        val original = ModelSettingsDraftUi(apiKey = "test-only-secret", responsesPath = "/responses",
            protocolChangeAuthorized = true)
        val messages = original.selectProtocol(ProviderProtocol.MESSAGES)
        assertEquals("", messages.apiKey)
        assertEquals("", messages.responsesPath)
        assertEquals(ProviderProtocol.RESPONSES, messages.savedProtocol)
        assertFalse(messages.protocolChangeAuthorized)
        assertEquals(messages, messages.selectProtocol(ProviderProtocol.MESSAGES))
        assertEquals(ProviderProtocol.RESPONSES, messages.selectProtocol(ProviderProtocol.RESPONSES).savedProtocol)
    }

    @Test
    fun draftStringDoesNotRevealKeyOrCustomJson() {
        val draft = ModelSettingsDraftUi(apiKey = "never-print-key", customBody = "never-print-body")
        assertFalse(draft.toString().contains("never-print-key"))
        assertFalse(draft.toString().contains("never-print-body"))
    }
}
