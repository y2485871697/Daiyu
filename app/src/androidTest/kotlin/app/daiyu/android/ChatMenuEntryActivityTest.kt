package app.daiyu.android

import androidx.compose.ui.test.*
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

    fun testTokenMenuEntersContextWithoutSending() = context(ChatMenuAction.CONTEXT_STATS)
    fun testCompressMenuEntersExistingContextWithoutSending() = context(ChatMenuAction.COMPRESS_CONTEXT)

    fun testAutomaticCompressionMenuEntersExistingSettingsWithoutSending() {
        selectDraft()
        menu(ChatMenuAction.AUTO_COMPRESS)
        node("page:上下文压缩").assertExists()
        assertEquals(0, fixture.opens.get())
        backToPage(activity, "page:设置")
        backToChat(activity)
        assertDraft("menu draft stays unsent")
    }

    fun testInteractiveMenuTogglesExistingStoreAndPreservesDraft() {
        selectDraft()
        val store = fixture.controller.interactiveMode
        val previous = store.enabled
        try {
            menu(ChatMenuAction.INTERACTIVE_MODE)
            assertEquals(!previous, store.enabled)
            node("interactive-mode-toggle").assert(hasStateDescription(if (previous) "已关闭" else "已开启"))
            assertDraft("menu draft stays unsent")
            assertEquals(0, fixture.opens.get())
        } finally { mainThread { store.setEnabled(previous) } }
    }

    fun testVoiceMenuOpensRealControlsWithoutMicrophoneOrProviderRequest() {
        selectDraft()
        ui.onNodeWithContentDescription("语音输入").performTouchInput { longClick() }
        node("chat-menu:VOICE_CHAT").performClick()
        node("voice-universal-control").assertExists()
        assertEquals(0, fixture.opens.get())
        // A selection change permanently loses this open request, not just its composition.
        mainThread { activity.select(null) }
        node("voice-universal-control").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testVoiceMenuRequestFollowsOnlyItsOwnEditorMigrations() {
        selectDraft()
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.VOICE_CHAT) }
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
