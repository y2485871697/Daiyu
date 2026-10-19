package app.daiyu.android

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
        node("chat-menu:${action.name}").performScrollTo().performClick()
    }

    private fun context(action: ChatMenuAction) {
        selectDraft()
        val before = fixture.database.exportBackup()
        menu(action)
        node("context-management").assertExists()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        backToChat(activity)
        assertDraft("menu draft stays unsent")
    }

    fun testNewChatMenuRestoresUnattachedDraftAndPreservesConversationDraft() {
        selectDraft()
        val conversation = fixture.database.conversations().single()
        mainThread { fixture.database.draft("未附着草稿") }
        menu(ChatMenuAction.NEW_CHAT)
        awaitUniqueTag("chat-draft")
        assertDraft("未附着草稿")
        assertEquals("menu draft stays unsent", fixture.database.conversations().single().draft)
        assertEquals(1, fixture.database.conversations().size)
        assertEquals(0, fixture.opens.get())
        mainThread { activity.select(conversation) }
        assertDraft("menu draft stays unsent")
    }

    fun testSearchMenuOpensMessageHistoryAndReturnsWithoutConsumingDraft() {
        selectDraft()
        val before = fixture.database.exportBackup()
        menu(ChatMenuAction.SEARCH)
        awaitUniqueTag("page:${activity.getString(R.string.search_messages_title)}")
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
        node("browser-visual-empty").assertTextEquals("没有打开的标签页")
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
        menu(ChatMenuAction.CONTEXT_STATS)
        node("chat-usage-dialog").assertExists()
        ui.onNodeWithText("关闭").performClick()
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
            node("chat-menu:AUTO_COMPRESS").performScrollTo().performClick()
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
            node("workspace-private-import").assertIsDisplayed().assertIsEnabled()
            node("workspace-pick-file").assertDoesNotExist()
            node("workspace-pick-directory").assertDoesNotExist()
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

    private fun openPathReference(value: String): () -> Unit {
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.ATTACH_PATH) }
        awaitUniqueTag("prompt-input")
        node("prompt-input").performTextReplacement(value)
        return retainedClick(activity, "dialog-confirm")
    }

    fun testPathReferenceRealAttachmentMenuCancelDoesNotReadGrantOrSend() {
        selectDraft()
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.load()
        val grants = PlatformSafGrants(instrumentation.targetContext).grants()
        ui.onNodeWithContentDescription("附件").performClick()
        node("chat-attachment:ATTACH_PATH").assertDoesNotExist()
        node("chat-attachment-path-toggle").performClick()
        node("chat-attachment:ATTACH_PATH").performClick()
        awaitUniqueTag("prompt-input")
        node("prompt-input").performTextReplacement("content://not-granted.invalid/never-read")
        node("dialog-cancel").performClick()
        node("prompt-input").assertDoesNotExist()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(settings, fixture.settings.load())
        assertEquals(grants, PlatformSafGrants(instrumentation.targetContext).grants())
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
    }

    fun testPathReferenceConfirmationAppendsLiteralTextOnceAndSurvivesRecreation() {
        selectDraft()
        val conversation = fixture.database.conversations().single()
        val messages = fixture.database.messages(conversation.id)
        val settings = fixture.settings.load()
        val grants = PlatformSafGrants(instrumentation.targetContext).grants()
        val raw = "/workspace/中文 空格/'\";not-a-command"
        val expected = "menu draft stays unsent\n路径引用（未读取）：$raw"
        val confirm = openPathReference(raw)
        mainThread { confirm(); confirm() }
        assertDraft(expected)
        assertSelection(expected.length, expected.length)
        val reopened = app.daiyu.android.storage.ConversationDatabase(fixture.application)
        try {
            assertEquals(expected, reopened.conversations().single { it.id == conversation.id }.draft)
            assertEquals(messages, reopened.messages(conversation.id))
        } finally {
            reopened.close()
        }
        activity = recreateActivity(activity)
        awaitUniqueTag("chat-draft")
        assertDraft(expected)
        mainThread { confirm() }
        assertDraft(expected)
        assertEquals(messages, fixture.database.messages(conversation.id))
        assertEquals(settings, fixture.settings.load())
        assertEquals(grants, PlatformSafGrants(instrumentation.targetContext).grants())
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
    }

    fun testPathReferenceRejectsBlankLongAndControlCharactersWithoutTruncation() {
        selectDraft()
        val before = fixture.database.exportBackup()
        for (raw in listOf("  ", "x".repeat(2049), "a\nb", "a\tb", "a\u007fb")) {
            mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.ATTACH_PATH) }
            awaitUniqueTag("prompt-input")
            // Invoke the actual host capability: a single-line widget may normalize control input.
            mainThread {
                val prompt = MainActivity::class.java.getDeclaredMethod("getPrompt")
                    .apply { isAccessible = true }.invoke(activity)
                MainActivity::class.java.declaredMethods.single { it.name == "confirmInputPrompt" }
                    .apply { isAccessible = true }.invoke(activity, prompt, raw)
            }
            node("prompt-input").assertDoesNotExist()
            assertDraft("menu draft stays unsent")
            assertEquals(before, fixture.database.exportBackup())
        }
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
    }

    fun testPathReferenceOldMenuAndConfirmationStayRevokedAfterRealPauseResume() {
        selectDraft()
        val menu = retainedControls(activity).onMenuAction
        val confirm = openPathReference("/never-read/pause")
        val before = fixture.database.exportBackup()
        realPauseResume(activity, atPause = { menu(ChatMenuAction.ATTACH_PATH) })
        mainThread { confirm(); menu(ChatMenuAction.ATTACH_PATH) }
        node("prompt-input").assertDoesNotExist()
        assertDraft("menu draft stays unsent")
        assertEquals(before, fixture.database.exportBackup())
        val fresh = openPathReference("/fresh-after-resume")
        mainThread { fresh() }
        assertDraft("menu draft stays unsent\n路径引用（未读取）：/fresh-after-resume")
        assertEquals(0, fixture.opens.get())
    }

    fun testPathReferenceDraftChangeRejectsOldConfirmation() {
        selectDraft()
        val confirm = openPathReference("/never-read/old-draft")
        mainThread { retainedControls(activity).onTextChange("newer unsent draft") }
        val changed = fixture.database.exportBackup()
        mainThread { confirm() }
        node("prompt-input").assertDoesNotExist()
        assertDraft("newer unsent draft")
        assertEquals(changed, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testPathReferenceNavigationAndSameConversationReentryRejectOldCapabilities() {
        selectDraft()
        val conversation = fixture.database.conversations().single()
        val menu = retainedControls(activity).onMenuAction
        val confirm = openPathReference("/never-read/old-page")
        mainThread { menu(ChatMenuAction.SETTINGS); confirm() }
        awaitUniqueTag("page:设置")
        backToChat(activity)
        mainThread { menu(ChatMenuAction.ATTACH_PATH) }
        node("prompt-input").assertDoesNotExist()
        val second = openPathReference("/never-read/old-editor")
        mainThread { activity.select(conversation) }
        val reentered = fixture.database.exportBackup()
        mainThread { second() }
        node("prompt-input").assertDoesNotExist()
        assertDraft("menu draft stays unsent")
        assertEquals(reentered, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
    }

    fun testSettingsVoiceRowEntersExistingConfiguration() {
        selectDraft()
        openSettings()
        node("settings-search").performTextReplacement("语音")
        reveal("settings-row:语音", "设置").performClick()
        node("voice-settings").assertExists()
        node("voice-universal-control").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
        backToPage(activity, "page:设置")
        backToChat(activity)
        assertDraft("menu draft stays unsent")
    }
}
