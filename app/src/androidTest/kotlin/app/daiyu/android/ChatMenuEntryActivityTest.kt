package app.daiyu.android

import androidx.compose.ui.semantics.SemanticsProperties
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import app.daiyu.android.platform.PlatformSafGrants
import java.util.concurrent.atomic.AtomicInteger
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction

/** Real menu callbacks enter existing owners; opening a page must not send a request. */
class ChatMenuEntryActivityTest : WiredActivityTestCase() {
    private fun selectDraft() {
        val conversation = fixture.database.create("default")
        fixture.database.draft(conversation.id, "menu draft stays unsent")
        mainThread { activity.select(conversation) }
        awaitControl("menu readers idle") { !fixture.controller.dataBusy }
        assertDraft("menu draft stays unsent")
    }

    private fun menu(action: ChatMenuAction) {
        ui.onNodeWithContentDescription("更多").performClick()
        if (action == ChatMenuAction.OPEN_TERMINAL) node("chat-menu:${action.name}").assertTextContains("打开终端")
        if (action == ChatMenuAction.OPEN_BROWSER) node("chat-menu:${action.name}").assertTextContains("打开浏览器")
        node("chat-menu:${action.name}").scrollToVisible().performClick()
    }

    private fun context(action: ChatMenuAction) {
        selectDraft()
        val before = fixture.database.exportBackup()
        menu(action)
        node("context-management").assertExists()
        node("compression-dialog-custom").assertExists()
        node("context-compress").assertIsDisplayed().assertTextContains("确定")
        node("context-close").assertIsDisplayed().assertTextContains("取消")
        node("context-refresh").assertDoesNotExist()
        node("context-advanced").assertDoesNotExist()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        backToChat(activity)
        assertDraft("menu draft stays unsent")
    }

    fun testNewChatMenuCreatesEmptyConversationAndPreservesBothPreviousDrafts() {
        selectDraft()
        val conversation = fixture.database.conversations().single()
        mainThread { fixture.database.draft("未附着草稿") }
        menu(ChatMenuAction.NEW_CHAT)
        awaitUniqueTag("chat-draft")
        assertDraft("")
        val conversations = fixture.database.conversations()
        assertEquals("menu draft stays unsent", conversations.single { it.id == conversation.id }.draft)
        val created = conversations.single { it.id != conversation.id }
        assertEquals("", created.draft)
        assertTrue(fixture.database.messages(created.id).isEmpty())
        assertEquals(created.id.value, fixture.controller.preferences.getString("selected", null))
        assertEquals("未附着草稿", fixture.database.draft())
        assertEquals(2, conversations.size)
        assertEquals(0, fixture.opens.get())
        mainThread { activity.select(conversation) }
        assertDraft("menu draft stays unsent")
    }

    fun testSearchMenuOpensMessageHistoryAndReturnsWithoutConsumingDraft() {
        selectDraft()
        val before = fixture.database.exportBackup()
        menu(ChatMenuAction.SEARCH)
        awaitUniqueTag("search-history-dialog")
        node("chat-messages").assertExists()
        node("page:${activity.getString(R.string.search_messages_title)}").assertDoesNotExist()
        node("search:query").assertIsDisplayed()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        backToChat(activity)
        assertDraft("menu draft stays unsent")
    }

    fun testNewAndSearchRetainedCallbacksCannotLeaveSettingsOrReplaceAnotherEditor() {
        selectDraft()
        val oldMenu = retainedControls(activity).onMenuAction
        val before = fixture.database.exportBackup()
        openSettings()
        mainThread {
            oldMenu(ChatMenuAction.NEW_CHAT)
            oldMenu(ChatMenuAction.SEARCH)
        }
        node("page:设置").assertExists()
        backToChat(activity)
        mainThread { activity.select(fixture.database.conversations().single()) }
        mainThread {
            oldMenu(ChatMenuAction.NEW_CHAT)
            oldMenu(ChatMenuAction.SEARCH)
        }
        assertDraft("menu draft stays unsent")
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testBrowserMenuOpensVisiblePageWithoutCreatingRunOrConsumingDraft() {
        selectDraft()
        val before = fixture.database.exportBackup()
        menu(ChatMenuAction.OPEN_BROWSER)
        node("browser-visual-page").assertExists()
        ui.onNodeWithTag("browser-visual-empty", useUnmergedTree = true).assertIsDisplayed().assertTextEquals(activity.getString(R.string.om_browser_enter_url_hint))
        node("browser-visual-tabs").assertTextEquals("1/3")
        node("browser-visual-new-tab").assertIsEnabled()
        node("browser-visual-takeover").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
        assertEquals(before, fixture.database.exportBackup())
        node("browser-visual-back").performClick()
        assertDraft("menu draft stays unsent")
        assertEquals(0, fixture.opens.get())
    }

    fun testTokenMenuShowsUsageWithoutSending() {
        selectDraft()
        val before = fixture.database.exportBackup()
        node("chat-context").assertIsDisplayed().assertHasClickAction()
        node("chat-context").performTouchInput { click() }
        node("chat-usage-dialog").assertDoesNotExist()
        node("chat-context").performTouchInput { longClick() }
        node("chat-usage-dialog").assertDoesNotExist()
        menu(ChatMenuAction.CONTEXT_STATS)
        awaitUniqueTag("chat-usage-dialog")
        node("chat-usage-dialog").assertExists()
        awaitUniqueTag("chat-usage-empty")
        ui.onNodeWithText("暂无用量").assertIsDisplayed()
        for (label in listOf("关闭", "刷新", "统计对象：本会话", "统计对象：全部会话", "详细用量")) {
            ui.onNodeWithText(label).assertDoesNotExist()
        }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node("chat-usage-dialog").assertDoesNotExist()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        assertDraft("menu draft stays unsent")
    }
    fun testCompressMenuEntersExistingContextWithoutSending() = context(ChatMenuAction.COMPRESS_CONTEXT)

    fun testAutomaticCompressionMenuTogglesSavedConfigurationWithoutSending() {
        val preferences = instrumentation.targetContext.getSharedPreferences("context-compression", 0)
        val previous = preferences.all.toMap()
        val store = app.daiyu.android.context.ProductionCompressionSettingsStore(preferences)
        try {
            store.save(app.daiyu.android.context.ProductionCompressionSettings(
                automatic = true, contextWindowTokens = 10000,
                summaryModel = app.daiyu.android.context.SummaryModelBinding("menu-fixture", "menu-model")))
            activity = recreateActivity(activity)
            selectDraft()
            val before = fixture.database.exportBackup()
            ui.onNodeWithContentDescription("更多").performClick()
            ui.waitUntil(5_000) {
                ui.onAllNodes(hasTestTag("chat-menu:AUTO_COMPRESS") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            node("chat-menu:AUTO_COMPRESS").scrollToVisible().performClick()
            awaitControl("automatic compression saved") { !store.snapshot().automatic }
            node("chat-overflow-menu").assertIsDisplayed()
            node("page:上下文压缩").assertDoesNotExist()
            node("context-management").assertDoesNotExist()
            assertEquals(before, fixture.database.exportBackup())
            assertEquals(0, fixture.opens.get())
            assertDraft("menu draft stays unsent")
        } finally {
            val editor = preferences.edit().clear()
            previous.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> error("unsupported preference type")
                }
            }
            check(editor.commit())
        }
    }

    fun testInteractiveMenuTogglesExistingStoreAndPreservesDraft() {
        selectDraft()
        val store = fixture.controller.interactiveMode
        val previous = store.enabled
        try {
            menu(ChatMenuAction.INTERACTIVE_MODE)
            assertEquals(!previous, store.enabled)
            node("chat-overflow-menu").assertIsDisplayed()
            node("chat-menu:INTERACTIVE_MODE").assertExists()
            assertDraft("menu draft stays unsent")
            assertEquals(0, fixture.opens.get())
        } finally { mainThread { store.setEnabled(previous) } }
    }

    fun testVoiceMenuOpensRealControlsWithoutMicrophoneOrProviderRequest() {
        kotlinx.coroutines.runBlocking {
            fixture.controller.voiceRuntime.save(VoiceConfiguration(dictation = "DOUBAO", asrKey = "fixture-asr"), VoiceProfile.CONVERSATION)
        }
        selectDraft()
        openVoiceConversationPanel()
        node("voice-universal-control").assertExists()
        assertEquals(0, fixture.opens.get())
        // A selection change permanently loses this open request, not just its composition.
        mainThread { activity.select(null) }
        node("voice-universal-control").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testVoiceMenuRequestFollowsOnlyItsOwnEditorMigrations() {
        kotlinx.coroutines.runBlocking {
            fixture.controller.voiceRuntime.save(VoiceConfiguration(dictation = "DOUBAO", asrKey = "fixture-asr"), VoiceProfile.CONVERSATION)
        }
        selectDraft()
        openVoiceConversationPanel()
        node("voice-universal-control").assertExists()
        val ownerField = MainActivity::class.java.getDeclaredField("voiceConversationDialogOwner\$delegate")
            .apply { isAccessible = true }
        fun owner() = (ownerField.get(activity) as androidx.compose.runtime.State<*>).value as? ChatController.TextSendOwner
        val migrate = MainActivity::class.java.getDeclaredMethod("migrateUniversalEditor",
            ChatController.TextSendOwner::class.java, ChatController.TextSendOwner::class.java).apply { isAccessible = true }
        mainThread {
            val before = checkNotNull(owner())
            val after = before.copy(draftRevision = before.draftRevision + 1)
            migrate.invoke(activity, before, after)
            assertEquals(after, owner())
            migrate.invoke(activity, before, after.copy(draftRevision = after.draftRevision + 1))
            assertEquals(after, owner())
        }
        mainThread { activity.select(null) }
        mainThread {
            assertNull(owner())
            val old = ChatController.TextSendOwner(null, -1, -1)
            migrate.invoke(activity, old, old.copy(draftRevision = 0))
            assertNull(owner())
        }
        node("voice-universal-control").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testWorkspaceMenuOpensExistingReadOnlyPageWithoutPickerOrModelAndReturnsDirectlyToChat() {
        selectDraft()
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.load()
        val grants = PlatformSafGrants(instrumentation.targetContext).grants()
        val launches = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action !in setOf(Intent.ACTION_OPEN_DOCUMENT_TREE, Intent.ACTION_OPEN_DOCUMENT)) return null
                launches.incrementAndGet()
                return Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            menu(ChatMenuAction.OPEN_WORKSPACE)
            awaitUniqueTag("page:工作区")
            node("linux-files:workspace").assertIsSelected()
            node("linux-files:rootfs").assertIsDisplayed()
            node("linux-files:shared").assertIsDisplayed()
            node("linux-bottom-navigation").assertDoesNotExist()
            node("linux-tab:basic").assertDoesNotExist()
            node("linux-tab:files").assertDoesNotExist()
            ui.onNodeWithText("私有工作区").assertDoesNotExist()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithTag("workspace-private-import").fetchSemanticsNodes().singleOrNull()?.let {
                    it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
                        !it.config.contains(SemanticsProperties.Disabled)
                } == true
            }
            node("workspace-private-import").assertIsDisplayed().assertIsEnabled()
            node("workspace-pick-file").assertDoesNotExist()
            node("workspace-pick-directory").assertDoesNotExist()
            val application = ((instrumentation.targetContext.applicationContext as ChatApplication).terminalProvider.state.value
                as app.daiyu.android.terminal.TerminalApplicationState.Ready).application
            assertFalse(application.terminalOwner.host.state.value.ownsResources)
            assertTrue(application.terminalSessions.state.value.sessions.isEmpty())
            assertEquals(0, launches.get())
            assertEquals(grants, PlatformSafGrants(instrumentation.targetContext).grants())
            assertEquals(before, fixture.database.exportBackup())
            assertEquals(settings, fixture.settings.load())
            assertNull(fixture.controller.active)
            assertEquals(0, fixture.opens.get())
            backToChat(activity)
            assertDraft("menu draft stays unsent")
            openSettings()
            node("page:设置").assertExists()
            node("page:工作区").assertDoesNotExist()
            backToChat(activity)
            assertEquals(0, launches.get())
        } finally { instrumentation.removeMonitor(monitor) }
    }

    fun testWorkspaceTerminalRoundTripAndRecreationReturnToFilesOnlyWithFreshOwner() {
        selectDraft()
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.load()
        menu(ChatMenuAction.OPEN_WORKSPACE)
        awaitUniqueTag("page:工作区")
        lateinit var previousOwner: app.daiyu.android.terminal.LinuxSettingsPageOwner
        mainThread {
            previousOwner = invokeHost(activity, "getLinuxSettingsPageOwner") as app.daiyu.android.terminal.LinuxSettingsPageOwner
        }
        val terminalOwner = (instrumentation.targetContext.applicationContext as ChatApplication).terminalOwner
        assertFalse(terminalOwner.host.state.value.ownsResources)
        val application = ((instrumentation.targetContext.applicationContext as ChatApplication).terminalProvider.state.value
            as app.daiyu.android.terminal.TerminalApplicationState.Ready).application
        assertTrue(application.terminalSessions.state.value.sessions.isEmpty())
        node("linux-open-terminal").assertIsDisplayed().performClick()
        awaitUniqueTag("terminal-screen")
        assertTrue(previousOwner.state.value.revoked)
        assertFalse(terminalOwner.host.state.value.ownsResources)
        activity = recreateActivity(activity)
        awaitUniqueTag("terminal-screen")
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        awaitUniqueTag("page:工作区")
        lateinit var currentOwner: app.daiyu.android.terminal.LinuxSettingsPageOwner
        mainThread {
            currentOwner = invokeHost(activity, "getLinuxSettingsPageOwner") as app.daiyu.android.terminal.LinuxSettingsPageOwner
        }
        assertNotSame(previousOwner, currentOwner)
        assertFalse(currentOwner.state.value.revoked)
        node("linux-files:workspace").assertIsSelected()
        node("linux-bottom-navigation").assertDoesNotExist()
        node("linux-tab:basic").assertDoesNotExist()
        node("linux-tab:files").assertDoesNotExist()
        assertFalse(terminalOwner.host.state.value.ownsResources)
        backToChat(activity)
        assertDraft("menu draft stays unsent")
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
    }

    fun testWorkspaceMenuRetainedCallbackCannotNavigateWhilePausedOnAnotherPageOrAfterEditorReplacement() {
        selectDraft()
        val menu = retainedControls(activity).onMenuAction
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.load()
        realPauseResume(activity, atPause = { menu(ChatMenuAction.OPEN_WORKSPACE) })
        awaitUniqueTag("chat-draft")
        node("page:工作区").assertDoesNotExist()
        openSettings()
        mainThread { menu(ChatMenuAction.OPEN_WORKSPACE) }
        node("page:设置").assertExists()
        node("page:工作区").assertDoesNotExist()
        backToChat(activity)
        assertEquals(before, fixture.database.exportBackup())
        val conversation = fixture.database.conversations().single()
        mainThread { activity.select(conversation) }
        val reentered = fixture.database.exportBackup()
        mainThread { menu(ChatMenuAction.OPEN_WORKSPACE) }
        node("chat-draft").assertExists()
        node("page:工作区").assertDoesNotExist()
        assertDraft("menu draft stays unsent")
        assertEquals(reentered, fixture.database.exportBackup())
        assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
    }

    fun testAttachmentMenuContainsOnlyPickersAndBackPreservesDraftConfigurationAndGrants() {
        selectDraft()
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.load()
        val grants = PlatformSafGrants(instrumentation.targetContext).grants()
        ui.onNodeWithContentDescription("附件").performClick()
        for (action in listOf(ChatMenuAction.ATTACH_IMAGE, ChatMenuAction.ATTACH_VIDEO,
            ChatMenuAction.ATTACH_FILE, ChatMenuAction.ATTACH_FOLDER)) {
            node("chat-attachment:${action.name}").assertIsDisplayed()
        }
        ui.onAllNodes(SemanticsMatcher("attachment picker entry") {
            it.config.contains(SemanticsProperties.TestTag) &&
                it.config[SemanticsProperties.TestTag].startsWith("chat-attachment:")
        }).assertCountEquals(4)
        node("image-host-authorize").assertDoesNotExist()
        node("chat-attachment-path-toggle").assertDoesNotExist()
        ui.onNodeWithText("声明图片能力").assertDoesNotExist()
        ui.onNodeWithText("输入文件路径").assertDoesNotExist()
        node("prompt-input").assertDoesNotExist()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node("chat-attachment:ATTACH_IMAGE").assertDoesNotExist()
        assertDraft("menu draft stays unsent")
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(settings, fixture.settings.load())
        assertEquals(grants, PlatformSafGrants(instrumentation.targetContext).grants())
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
    }

    fun testSettingsVoiceRowEntersExistingConfiguration() {
        selectDraft()
        openSettings()
        node("settings-search").assertDoesNotExist()
        reveal("settings-row:语音", "设置").performClick()
        node("voice-settings").assertExists()
        node("voice-universal-control").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
        backToPage(activity, "page:设置")
        backToChat(activity)
        assertDraft("menu draft stays unsent")
    }
}
