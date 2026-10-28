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
        assertTrue(entries.none { it.id in setOf("terminal", "workspace") })
        assertTrue(filterSettingsGroups("工作区与文件").isEmpty())
        assertEquals("运行环境与可选工具", SettingsCatalog.entry("linux").summary)
        assertEquals(listOf("linux", "task_surface"),
            SettingsCatalog.visibleGroups().single { it.id == "tools" }.entries.map { it.id })
        assertEquals(listOf("platform_tools", "browser", "linux", "task_surface"),
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
            assertEquals(21, entries.size)
            assertTrue(entries.none { it.id in hiddenIds })
            assertTrue(groups.none { it.id == "permissions" })
            assertEquals(listOf("appearance", "assistants", "voice", "haptics"),
                groups.single { it.id == "general" }.entries.map { it.id })
            assertEquals(listOf("providers", "compression", "vision", "subagents", "title_model", "reconnect"),
                groups.single { it.id == "models" }.entries.map { it.id })
            assertEquals(listOf("usage", "backup", "logging", "export_logs", "clear_logs"),
                groups.single { it.id == "data" }.entries.map { it.id })
            assertTrue(entries.map { it.id }.containsAll(setOf(
                "task_surface", "linux", "voice", "app_info",
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
    fun aboutHomeContainsOnlyAppInfoWhileOriginalActionsStayInItsSubpage() {
        assertEquals(SettingsLocalPage.APP_INFO, SettingsCatalog.entry("app_info").localPage)
        assertEquals(SettingControl.PAGE, SettingsCatalog.entry("app_info").control)
        for (engineeringUi in listOf(false, true)) {
            assertEquals(listOf("app_info"), SettingsCatalog.visibleGroups(engineeringUi)
                .single { it.id == "about" }.entries.map { it.id })
        }
        assertEquals(listOf("updates", "telegram", "qq", "source", "relay", "relay_st", "relay_moyu", "donate"),
            SettingsCatalog.appInfoEntries.map { it.id })
        assertTrue(SettingsCatalog.appInfoEntries.all { SettingsCatalog.entry(it.id) == it })
        assertTrue(SettingsCatalog.appInfoEntries.none { it.id == "app_info" })
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
    fun leavingMessagesRetainsVersionAndReturningRestoresIt() {
        val original = ModelSettingsDraftUi(protocol = ProviderProtocol.MESSAGES, apiKey = "test-only-secret",
            anthropicVersion = "2023-06-02", protocolChangeAuthorized = true)
        assertTrue(original === original.selectProtocol(ProviderProtocol.MESSAGES))
        for (target in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)) {
            val switched = original.selectProtocol(target)
            assertEquals(original.anthropicVersion, switched.anthropicVersion); assertEquals(original.apiKey, switched.apiKey)
            assertFalse(switched.protocolChangeAuthorized)
            assertEquals(ProviderProtocol.MESSAGES, switched.savedProtocol)
            val returned = switched.selectProtocol(ProviderProtocol.MESSAGES)
            assertEquals(original.anthropicVersion, returned.anthropicVersion); assertEquals(original.apiKey, returned.apiKey)
            assertFalse(returned.protocolChangeAuthorized)
        }
    }

    @Test
    fun protocolSwitchRetainsSecretsAndRevokesPreviousAuthorization() {
        val original = ModelSettingsDraftUi(apiKey = "test-only-secret", responsesPath = "/responses",
            protocolChangeAuthorized = true, customHeaders = "private-headers")
        assertTrue(original === original.selectProtocol(ProviderProtocol.RESPONSES))
        val messages = original.selectProtocol(ProviderProtocol.MESSAGES)
        assertEquals(original.apiKey, messages.apiKey)
        assertEquals(original.customHeaders, messages.customHeaders)
        assertEquals(original.customHeaders, messages.selectProtocol(ProviderProtocol.RESPONSES).customHeaders)
        assertEquals(original.responsesPath, messages.responsesPath)
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
    @Test
    fun sponsorEntryUsesHeartAndRetainsTheLocalActionIdentity() {
        val entry = SettingsCatalog.entry("donate")
        assertEquals("赞助", entry.title)
        assertEquals(SettingControl.ACTION, entry.control)
        assertEquals("Daiyu.Sponsor", entry.icon.name)
        assertEquals(app.daiyu.android.ui.icons.DaiyuFeatureColor.ROSE,
            app.daiyu.android.ui.icons.featureColorFor(entry.icon))
        assertEquals(null, entry.route)
        assertEquals(null, entry.summary)
    }

    @Test
    fun communityEntriesConstructTheirOwnBrandVectors() {
        for ((id, name) in listOf("telegram" to "Brand.Telegram", "qq" to "Brand.TencentQQ", "source" to "Brand.GitHub")) {
            val entry = SettingsCatalog.entry(id)
            assertEquals(name, entry.icon.name)
            assertEquals(24f, entry.icon.viewportWidth)
            assertEquals(24f, entry.icon.viewportHeight)
            assertTrue(entry.icon.root.size > 0)
            assertEquals(SettingControl.ACTION, entry.control)
        }
    }
}
