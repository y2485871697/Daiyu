package app.daiyu.android.ui.screens.settings

import app.daiyu.android.model.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPresentationTest {
    @Test
    fun retainedSettingsKeepInventoryAndSixVisibleGroups() {
        val entries = SettingsCatalog.entries
        // Current inventory includes independent generation/vision, P05 and platform tools.
        assertEquals(36, entries.size)
        assertTrue(entries.none { it.id in setOf("permission_check", "accessibility_keep", "default_reasoning") })
        assertTrue(entries.map { it.id }.containsAll(setOf("platform_tools", "accessibility", "voice", "donate")))
        assertEquals(app.daiyu.android.ui.FeatureRoute.BALANCE_SETTINGS, SettingsCatalog.entry("balance").route)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertTrue(entries.none { it.id == "running_mode" })
        assertTrue(filterSettingsGroups("运行模式").isEmpty())
        assertEquals(7, SettingsCatalog.groups.size)
        assertEquals(listOf("通用设置", "模型与服务", "扩展能力", "工具与环境", "数据与记录", "关于"),
            SettingsCatalog.visibleGroups().map { it.title })
        assertTrue(entries.none { it.id == "terminal" })
        assertEquals(listOf("platform_tools", "workspace", "browser", "linux", "task_surface"),
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
            "default_reasoning",
        )
        val hiddenTitles = listOf(
            "供应商余额", "独立生成模型", "平台只读", "浏览器", "悬浮窗权限", "Root 与系统增强", "无障碍",
            "默认启用推理",
        )
        for (engineeringUi in listOf(false, true)) {
            val groups = SettingsCatalog.visibleGroups(engineeringUi = engineeringUi)
            val entries = groups.flatMap { it.entries }
            assertEquals(29, entries.size)
            assertTrue(entries.none { it.id in hiddenIds })
            assertTrue(groups.none { it.id == "permissions" })
            assertEquals(listOf("appearance", "assistants", "voice", "haptics"),
                groups.single { it.id == "general" }.entries.map { it.id })
            assertEquals(listOf("providers", "compression", "vision", "subagents", "title_model", "reconnect"),
                groups.single { it.id == "models" }.entries.map { it.id })
            assertEquals(listOf("usage", "backup", "logging", "export_logs", "clear_logs"),
                groups.single { it.id == "data" }.entries.map { it.id })
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
    fun homeDescriptionsAreConciseAndDoNotInventNewControls() {
        val dynamicDescriptions = setOf("logging", "export_logs", "clear_logs", "updates", "donate")
        val entries = SettingsCatalog.visibleGroups().flatMap { it.entries }
        assertTrue(entries.filterNot { it.id in dynamicDescriptions }.all { !it.summary.isNullOrBlank() })
        assertTrue(entries.mapNotNull { it.summary }.all { it.length <= 24 })
        assertTrue(entries.none { it.id in setOf("color_mode", "search_service", "preferences") })
        assertTrue(filterSettingsGroups("个性化").isEmpty()) // Search continues to match entry titles only.
    }

    @Test
    fun chatCommandsAreNotSettingsButtons() {
        val titles = SettingsCatalog.entries.map { it.title }.toSet()
        assertTrue(titles.containsAll(setOf("模型提供商", "MCP 服务器", "导出日志")))
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
    fun connectionDraftOwnerDistinguishesEqualFreshDrafts() {
        val first = ModelSettingsDraftUi(anthropicVersion = "2023-06-01")
        val equalFresh = first.copy()
        assertEquals(first, equalFresh)
        assertEquals(SettingsConnectionDraftOwner(first), SettingsConnectionDraftOwner(first))
        assertEquals(SettingsConnectionDraftOwner(first).hashCode(), SettingsConnectionDraftOwner(first).hashCode())
        assertFalse(SettingsConnectionDraftOwner(first) == SettingsConnectionDraftOwner(equalFresh))
    }

    @Test
    fun leavingMessagesClearsVersionAndReturningDoesNotRestoreIt() {
        val original = ModelSettingsDraftUi(protocol = ProviderProtocol.MESSAGES, apiKey = "test-only-secret",
            anthropicVersion = "2023-06-02", protocolChangeAuthorized = true)
        assertTrue(original === original.selectProtocol(ProviderProtocol.MESSAGES))
        for (target in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)) {
            val switched = original.selectProtocol(target)
            assertEquals("", switched.anthropicVersion); assertEquals("", switched.apiKey)
            assertFalse(switched.protocolChangeAuthorized)
            assertEquals(ProviderProtocol.MESSAGES, switched.savedProtocol)
            val returned = switched.selectProtocol(ProviderProtocol.MESSAGES)
            assertEquals("", returned.anthropicVersion); assertEquals("", returned.apiKey)
            assertFalse(returned.protocolChangeAuthorized)
        }
    }

    @Test
    fun protocolSwitchClearsSecretsAndRequiresFreshAuthorization() {
        val original = ModelSettingsDraftUi(apiKey = "test-only-secret", responsesPath = "/responses",
            protocolChangeAuthorized = true, customHeaders = "private-headers")
        assertTrue(original === original.selectProtocol(ProviderProtocol.RESPONSES))
        val messages = original.selectProtocol(ProviderProtocol.MESSAGES)
        assertEquals("", messages.apiKey)
        assertEquals("", messages.customHeaders)
        assertEquals("", messages.selectProtocol(ProviderProtocol.RESPONSES).customHeaders)
        assertEquals("", messages.responsesPath)
        assertEquals(ProviderProtocol.RESPONSES, messages.savedProtocol)
        assertFalse(messages.protocolChangeAuthorized)
        assertEquals(messages, messages.selectProtocol(ProviderProtocol.MESSAGES))
        assertEquals(ProviderProtocol.RESPONSES, messages.selectProtocol(ProviderProtocol.RESPONSES).savedProtocol)
    }

    @Test
    fun draftStringDoesNotRevealKeyOrCustomJson() {
        val draft = ModelSettingsDraftUi(apiKey = "never-print-key", customBody = "never-print-body", customHeaders = "never-print-headers")
        assertFalse(draft.toString().contains("never-print-key"))
        assertFalse(draft.toString().contains("never-print-body"))
        assertFalse(draft.toString().contains("never-print-headers"))
    }
}
