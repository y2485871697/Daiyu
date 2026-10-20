package app.daiyu.android

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceTopBarBlurStyle
import app.daiyu.android.ui.screens.settings.SettingsCatalog
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.TopBarBackdrop
import app.daiyu.android.ui.components.captureForTopBar
import app.daiyu.android.ui.components.rememberTopBarBackdrop
import app.daiyu.android.ui.components.topBarContainerColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The old FeatureHub entry contract now belongs to the retained Compose settings home. */
class FeatureHubActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private val entries get() = SettingsCatalog.visibleGroups().flatMap { it.entries }
    private fun openHub() { openSettings() }
    private fun entry(id: String): SemanticsNodeInteraction {
        val item = entries.single { it.id == id }
        reveal("settings-search", "设置").performTextReplacement(item.title)
        return reveal("settings-row:${item.title}", "设置")
    }
    private fun settingsScrollPosition(): Float = node("page:设置").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    fun testLowerSettingsRowsKeepScrollOffsetAfterToolbarAndSystemBack() {
        setDraft("设置滚动保留草稿")
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.loadForEditing().settings
        openHub()
        // Exercise a real swipe too, so a collapsed top bar is part of the restored viewport.
        node("page:设置").performTouchInput { swipeUp() }
        for ((title, page) in listOf("震动列表" to "page:触感反馈", "Agent任务偏好" to "page:Agent任务偏好")) {
            val tag = "settings-row:$title"
            node("page:设置").performScrollToNode(hasTestTag(tag))
            node(tag).assertIsDisplayed()
            ui.waitForIdle()
            val position = settingsScrollPosition()
            val bounds = node(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("Entry must be opened after scrolling away from the top", position > 0f)
            for (systemBack in listOf(false, true)) {
                node(tag).performClick()
                awaitUniqueTag(page)
                if (systemBack) backToPage(activity, "page:设置") else {
                    ui.onNodeWithContentDescription(activity.getString(R.string.action_back)).performClick()
                    awaitUniqueTag("page:设置")
                }
                ui.waitForIdle()
                // Do not reveal/scroll the row here: restoration itself must make it visible.
                node(tag).assertIsDisplayed()
                assertEquals(position, settingsScrollPosition(), .00002f)
                val restored = node(tag).fetchSemanticsNode().boundsInRoot
                assertEquals(bounds.top, restored.top, 2f)
                assertEquals(bounds.bottom, restored.bottom, 2f)
            }
        }
        backToChat(activity)
        assertDraft("设置滚动保留草稿")
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(settings, fixture.settings.loadForEditing().settings)
        assertEquals(0, fixture.opens.get())
    }

    fun testSettingsSearchSurvivesChildPageAndOnlyEditsResetTheList() {
        openHub()
        entry("usage").performClick()
        awaitUniqueTag("usage-stats-screen")
        backToPage(activity, "page:设置")
        node("settings-search").assertEditableText("用量统计")
        node("settings-row:用量统计").assertIsDisplayed()
        node("settings-row:模型提供商").assertDoesNotExist()
        node("settings-search-clear").performClick()
        ui.waitForIdle()
        node("settings-search").assertEditableText("")
        assertEquals(0f, settingsScrollPosition(), .00002f)
        node("settings-row:模型提供商").assertIsDisplayed()
        node("page:设置").performScrollToNode(hasTestTag("settings-row:震动列表"))
        val before = settingsScrollPosition()
        node("settings-row:震动列表").performClick()
        awaitUniqueTag("page:触感反馈")
        backToPage(activity, "page:设置")
        assertEquals(before, settingsScrollPosition(), .00002f)
        reveal("settings-search", "设置").performTextReplacement("没有这样的设置")
        dismissIme()
        ui.waitForIdle()
        assertEquals(0f, settingsScrollPosition(), .00002f)
        ui.onNodeWithText("没有匹配的设置").assertIsDisplayed()
        assertEquals(0, fixture.opens.get())
    }

    fun testOriginalSettingsAppearOutsideExistingChatTreeWithoutStateLabels() {
        openHub()
        val expectedIds = listOf(
            "providers",
            "compression", "vision", "subagents", "title_model", "reconnect",
            "pet", "assistants", "skills", "mcp",
            "voice", "appearance", "usage", "backup",
            "workspace", "linux", "task_surface", "haptics",
            "logging", "export_logs", "clear_logs",
            "updates", "telegram", "qq", "source", "relay", "relay_st", "relay_moyu", "donate",
        )
        assertEquals(29, entries.size)
        for (engineering in listOf(false, true)) {
            assertEquals(expectedIds, SettingsCatalog.visibleGroups(engineering).flatMap { it.entries }.map { it.id })
        }
        val hidden = setOf("default_reasoning", "balance", "generation", "platform_tools", "browser", "overlay", "enhancements", "accessibility")
        for (engineering in listOf(false, true)) assertTrue(SettingsCatalog.visibleGroups(engineering)
            .flatMap { it.entries }.none { it.id in hidden })
        entries.forEach { item -> entry(item.id).assertIsEnabled().assert(
            hasText(item.title) or hasAnyDescendant(hasText(item.title))) }
        ui.onAllNodes(SemanticsMatcher("forbidden state marker") { semantics ->
            val texts = semantics.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty()
            texts.any { text -> listOf("尚未接入", "未实现", "占位", "开发中").any { it in text.text } }
        }, useUnmergedTree = true).assertCountEquals(0)
        backToChat(activity); node("chat-draft").assertExists(); node("chat-messages").assertExists()
    }
    fun testUnwiredButtonsDoNothingAndKeepDraftDatabaseSettingsAndWindow() {
        setDraft("Gemini UI 草稿"); val before = fixture.database.exportBackup()
        val settings = fixture.settings.loadForEditing().settings
        openHub()
        val implemented = setOf("updates", "assistants", "subagents", "appearance", "haptics", "voice", "source", "telegram", "qq", "relay_st", "relay_moyu", "relay", "platform_tools", "logging", "export_logs", "clear_logs")
        val placeholder = entries.single { it.id == "donate" }
        val unwired = entries.filter { it.route == null && it.localPage == null && it.id !in implemented && it != placeholder }
        assertEquals(0, unwired.size)
        fun assertUnchanged() {
            assertEquals(before, fixture.database.exportBackup()); assertEquals(settings, fixture.settings.loadForEditing().settings)
            assertNull(fixture.controller.active); assertEquals(0, fixture.opens.get())
        }
        (unwired + placeholder).forEach { item ->
            entry(item.id).performClick(); node("page:设置").assertExists()
            assertUnchanged()
        }
        entry("relay").performClick(); node("page:设置").assertExists(); assertUnchanged()
        for (title in listOf("ST API", "摸鱼")) {
            reveal("settings-search", "设置").performTextReplacement(title)
            reveal("settings-row:$title", "设置").assertExists(); node("page:设置").assertExists()
            assertUnchanged()
        }
        backToChat(activity); assertDraft("Gemini UI 草稿")
    }
    fun testConnectedModelEntryOpensTheExistingSettingsWithoutSaving() {
        setDraft("原草稿"); val before = fixture.settings.loadForEditing().settings
        openHub(); entry("providers").performClick()
        awaitUniqueTag("provider-management")
        reveal("provider-open-primary", "模型提供商").assertIsEnabled().performClick()
        awaitModelEditor()
        modelField("Base URL").assertEditableText(before.endpoint)
        node("settings-field:模型名").assertDoesNotExist()
        backToChat(activity); assertDraft("原草稿")
        assertEquals(0, fixture.opens.get()); assertEquals(before, fixture.settings.loadForEditing().settings)
    }
    fun testIndependentGenerationSettingsNavigationDoesNotSendOrChangeChatConfiguration() {
        setDraft("独立生成设置保留草稿")
        val before = fixture.database.exportBackup()
        val chatSettings = fixture.settings.loadForEditing().settings
        openHub(); openRetainedSettingsEntry("generation", "page:独立生成模型设置")
        node("page:独立生成模型设置").assertExists()
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(chatSettings, fixture.settings.loadForEditing().settings)
        backToPage(activity, "page:设置"); node("page:设置").assertExists()
        backToChat(activity); assertDraft("独立生成设置保留草稿")
    }
    fun testTaskSurfaceSearchAndCategoryOpenRealLocalPageWithoutSendingOrSaving() {
        setDraft("任务位置设置保留草稿")
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.loadForEditing().settings
        val catalogEntry = entries.single { it.id == "task_surface" }
        assertEquals(app.daiyu.android.ui.screens.settings.SettingsLocalPage.TASK_SURFACE, catalogEntry.localPage)
        openHub(); entry("task_surface").performClick()
        node("page:Agent任务偏好").assertExists()
        node("settings-radio:前台执行").assertExists()
        node("settings-radio:后台执行").assertExists()
        node("settings-radio:每次运行询问").assertExists()
        backToPage(activity, "page:设置"); node("page:设置").assertExists()
        reveal("settings-search", "设置").performTextReplacement("")
        reveal("settings-row:Agent任务偏好", "设置").performClick()
        node("page:Agent任务偏好").assertExists()
        backToPage(activity, "page:设置"); node("page:设置").assertExists()
        backToChat(activity); assertDraft("任务位置设置保留草稿")
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(settings, fixture.settings.loadForEditing().settings)
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
    fun testOldGenerationSettingsEntryCannotOpenAfterActivityRecreation() {
        setDraft("设置旧入口保留草稿")
        openHub()
        val oldOpen = retainedSettingsEntry("generation")
        val old = activity; activity = recreateActivity(activity)
        mainThread { oldOpen() }
        node("page:独立生成模型设置").assertDoesNotExist()
        assertTrue(old.isDestroyed)
        assertDraft("设置旧入口保留草稿")
        assertEquals(0, fixture.opens.get())
    }
    fun testRecreationClosesHubAndStaleButtonsCannotOpenTheNewActivity() {
        setDraft("重建保留草稿"); openHub(); entry("providers")
        val oldOpen = retainedClick(activity, "settings-row:模型提供商")
        val old = activity; activity = recreateActivity(activity)
        mainThread { oldOpen() }
        node("page:主模型服务商").assertDoesNotExist(); node("page:设置").assertDoesNotExist()
        assertDraft("重建保留草稿"); assertTrue(old.isDestroyed)
        openHub(); node("page:设置").assertExists(); backToChat(activity)
        assertEquals(0, fixture.opens.get())
    }
    fun testSettingsNavigationDoesNotSendPauseOrStopAnActiveRun() {
        val session = fixture.session(holdSeconds = 120)
        setDraft("运行正文"); node("chat-send").performClick()
        awaitTextSendResult(activity)
        setDraft("保留追加草稿")
        assertTrue(session.entered.await(5, TimeUnit.SECONDS)); val owner = checkNotNull(fixture.controller.active)
        try {
            openHub()
            for (title in listOf("发送消息", "暂停与继续", "停止回复", "交互问答")) {
                reveal("settings-search", "设置").performTextReplacement(title)
                ui.onNodeWithText("没有匹配的设置").assertExists()
                node("settings-row:$title").assertDoesNotExist()
                assertEquals(owner.id, fixture.controller.active!!.id)
                assertFalse(fixture.controller.runState(owner)!!.pauseRequested)
                assertEquals(1L, session.cancelled.count); assertEquals(1, fixture.opens.get())
            }
            val row = entry("logging")
            awaitControl("diagnostic store loaded") { fixture.diagnostics.state.value.loaded }
            row.performClick()
            node("page:设置").assertExists()
            assertEquals(owner.id, fixture.controller.active!!.id)
            assertFalse(fixture.controller.runState(owner)!!.pauseRequested)
            assertEquals(1L, session.cancelled.count); assertEquals(1, fixture.opens.get())
            entry("task_surface").performClick(); node("page:Agent任务偏好").assertExists()
            assertEquals(owner.id, fixture.controller.active!!.id)
            assertFalse(fixture.controller.runState(owner)!!.pauseRequested)
            assertEquals(1L, session.cancelled.count); assertEquals(1, fixture.opens.get())
            backToPage(activity, "page:设置"); node("page:设置").assertExists()
            backToChat(activity); assertDraft("保留追加草稿")
        } finally { session.release.countDown() }
        awaitControl("original finishes") { fixture.controller.active == null }; assertEquals(1, fixture.opens.get())
    }
    fun testButtonsFromPreviousDetailPageAreInert() {
        openHub(); entry("providers"); val oldOpen = retainedClick(activity, "settings-row:模型提供商")
        backToChat(activity); openSettings(); entry("backup").performClick()
        mainThread { oldOpen() }
        node("page:备份与导入").assertExists(); node("page:主模型服务商").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
    fun testInPlaceManageAndUsagePagesPreserveRealChatDraft() {
        setDraft("原聊天草稿")
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.drawer_manage_chats))
            .performTouchInput { click() }
        val managePage = "page:${instrumentation.targetContext.getString(R.string.history_page_title)}"
        ui.onNodeWithText("完成管理").assertIsDisplayed()
        node("drawer-selection-count").assertTextEquals("已选择 0 项")
        node(managePage).assertDoesNotExist()
        backToChat(activity); assertDraft("原聊天草稿")
        openHub(); entry("usage").performClick()
        node("usage-stats-screen").assertExists()
        node("page:${instrumentation.targetContext.getString(R.string.stats_page_title)}").assertExists()
        ui.onNodeWithText("关闭").assertDoesNotExist()
        ui.onNodeWithContentDescription(activity.getString(R.string.action_back)).performClick()
        node("page:设置").assertExists()
        node("usage-stats-screen").assertDoesNotExist()
        backToChat(activity); assertDraft("原聊天草稿")
        assertEquals(0, fixture.opens.get()); assertTopBarBlurCompatibility()
    }
    private fun assertTopBarBlurCompatibility() {
        AppearanceTopBarBlurStyle.entries.forEach { style -> listOf(true, false).forEach { enabled ->
            val drawn = CountDownLatch(1); val observation = AtomicReference<Triple<Boolean, Boolean, Boolean>>()
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(blurEnabled = enabled, topBarBlurStyle = style), applyInterfaceScale = true) {
                        val backdrop = rememberTopBarBackdrop(); val capture = Modifier.captureForTopBar(backdrop)
                        val solid = topBarContainerColor(backdrop) == MiuixTheme.colorScheme.surface
                        SideEffect { observation.set(Triple(backdrop != null, solid, capture === Modifier)) }
                        Box(Modifier.fillMaxSize().drawWithContent { drawContent(); drawn.countDown() }) {
                            Box(Modifier.fillMaxSize().then(capture))
                            TopBarBackdrop(backdrop) { Box(Modifier.fillMaxWidth().height(64.dp)) }
                        }
                    }
                }
            }
            ui.waitForIdle()
            assertTrue("Top bar draw: $style enabled=$enabled", drawn.await(5, TimeUnit.SECONDS))
            val expected = enabled && android.os.Build.VERSION.SDK_INT >= 33; val actual = checkNotNull(observation.get())
            assertEquals(expected, actual.first); assertEquals(!expected, actual.second); assertEquals(!expected, actual.third)
        } }
    }
    fun testOriginalSidebarOpensOverChatAndBackDoesNotLoseOrSendDraft() {
        setDraft("抽屉草稿"); ui.onNodeWithContentDescription("打开侧边栏").performClick()
        ui.waitForIdle()
        mainThread {
            val sidebar = MainActivity::class.java.getDeclaredField("sidebarVisibleState\$delegate").apply { isAccessible = true }.get(activity) as androidx.compose.runtime.State<*>
            assertEquals(true, sidebar.value)
            activity.onBackPressedDispatcher.onBackPressed()
        }
        assertDraft("抽屉草稿"); node("chat-draft").assertIsEnabled()
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
}
