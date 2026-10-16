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
        assertEquals(37, entries.size)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertEquals(9, SettingsCatalog.groups.size)
        assertEquals(listOf("workspace", "terminal", "browser", "linux", "task_surface"),
            SettingsCatalog.groups.single { it.id == "tools" }.entries.map { it.id })
        assertEquals(listOf("extensions"), SettingsCatalog.groups.filter { group ->
            group.entries.any { it.id == "skills" }
        }.map { it.id })
        assertTrue(SettingsCatalog.groups.none { it.id in setOf("system", "vendor", "gemini", "circle") })
        for (removed in listOf("系统助手", "小布", "Gemini", "一圈即搜", "Hey Google")) {
            assertTrue(filterSettingsGroups(removed).isEmpty())
        }
        assertTrue(entries.all { it.title.isNotBlank() && it.icon.name.isNotBlank() })
        assertEquals(SettingsCatalog.groups, filterSettingsGroups("  "))
    }

    @Test
    fun chatCommandsAreNotSettingsButtons() {
        val titles = SettingsCatalog.entries.map { it.title }.toSet()
        assertTrue(titles.containsAll(setOf("模型提供商", "默认启用推理", "MCP 服务器", "权限检查", "导出日志")))
        assertTrue(titles.intersect(setOf("发送消息", "暂停与继续", "停止回复", "追加与追问",
            "发送图片", "发送视频", "侧边栏", "聊天记录搜索", "搜索会话")).isEmpty())
    }

    @Test
    fun searchMatchesOriginalTitlesAndPreservesTheirOrder() {
        assertEquals(listOf("mcp"), filterSettingsGroups(" McP ").flatMap { it.entries }.map { it.id })
        assertEquals(SettingsCatalog.entries.filter { it.title.contains("模型") },
            filterSettingsGroups("模型").flatMap { it.entries })
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
