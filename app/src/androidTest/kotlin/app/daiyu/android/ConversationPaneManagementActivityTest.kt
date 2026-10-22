package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import app.daiyu.android.domain.Conversation
import app.daiyu.android.ui.model.ConversationPaneUiState

/** Ordinary selection changes the owner in place; management only changes its checkbox selection. */
class ConversationPaneManagementActivityTest : WiredActivityTestCase() {
    private fun hostValue(name: String): Any? = MainActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    @Suppress("UNCHECKED_CAST")
    private fun paneState() = (MainActivity::class.java.getDeclaredField("paneState\$delegate")
        .apply { isAccessible = true }.get(activity) as MutableState<ConversationPaneUiState>).value

    private fun selectedConversation() = MainActivity::class.java.getDeclaredField("selected")
        .apply { isAccessible = true }.get(activity) as Conversation?

    private fun assertSelectedWithSidebarOpen(conversation: Conversation, expectedDraft: String) {
        awaitControl("selected owner published without closing sidebar") {
            selectedConversation()?.id == conversation.id && paneState().selectedConversationId == conversation.id.value
        }
        mainThread {
            assertEquals(true, hostValue("getSidebarVisible"))
            assertEquals("chat", hostValue("getRetainedPage"))
            assertEquals("保留侧栏", paneState().searchQuery)
            assertEquals(conversation.id.value, fixture.controller.preferences.getString("selected", null))
            assertEquals(expectedDraft, hostValue("getDraft"))
        }
        node("conversation-drawer").assertIsDisplayed()
        node("drawer-conversation:${conversation.id.value}").assertIsDisplayed()
        node("drawer-search-input").assertEditableText("保留侧栏")
        node("chat-draft").assertIsNotEnabled()
    }

    fun testSelectingConversationKeepsSidebarAndSearchUntilExplicitBackOrScrimDismissal() {
        lateinit var first: Conversation
        lateinit var second: Conversation
        mainThread {
            first = fixture.database.create()
            second = fixture.database.create()
            fixture.database.rename(first.id, "保留侧栏甲")
            fixture.database.rename(second.id, "保留侧栏乙")
            fixture.database.draft(first.id, "甲原草稿")
            fixture.database.draft(second.id, "乙草稿")
            activity.select(first)
        }
        setDraft("甲编辑后的草稿")
        setSelection(1, 3)
        val before = fixture.database.exportBackup()
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        awaitUniqueTag("drawer-conversation:${second.id.value}")
        node("drawer-search").performClick()
        node("drawer-search-input").performTextReplacement("保留侧栏")
        dismissIme()
        for (conversation in listOf(second, second, first)) {
            node("drawer-conversation:${conversation.id.value}").performClick()
            assertSelectedWithSidebarOpen(conversation, if (conversation.id == first.id) "甲编辑后的草稿" else "乙草稿")
        }
        assertEquals(before, fixture.database.exportBackup())
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        mainThread {
            assertEquals(false, hostValue("getSidebarVisible"))
            assertEquals(first.id, selectedConversation()?.id)
            assertFalse(activity.isFinishing); assertFalse(activity.isDestroyed)
        }
        assertDraft("甲编辑后的草稿")
        assertSelection(1, 3)
        node("chat-draft").assertIsEnabled()

        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        node("drawer-search-input").assertEditableText("保留侧栏")
        node("drawer-conversation:${second.id.value}").performClick()
        assertSelectedWithSidebarOpen(second, "乙草稿")
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        val pane = node("conversation-drawer").fetchSemanticsNode().boundsInRoot
        assertTrue("Scrim is exposed to the right of the pane", root.right > pane.right)
        ui.onRoot().performTouchInput {
            click(Offset((pane.right + root.right) / 2f - root.left, root.center.y - root.top))
        }
        ui.waitForIdle()
        mainThread {
            assertEquals(false, hostValue("getSidebarVisible"))
            assertEquals(second.id, selectedConversation()?.id)
        }
        assertDraft("乙草稿")
        node("chat-draft").assertIsEnabled()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testManageSelectsInPlacePreservesOwnerDraftAndReopensWithoutLosingSelection() {
        lateinit var first: Conversation
        lateinit var second: Conversation
        mainThread {
            first = fixture.database.create()
            second = fixture.database.create()
            fixture.database.rename(first.id, "侧栏选择甲")
            fixture.database.rename(second.id, "侧栏选择乙")
        }
        setDraft("管理时保留草稿")
        val before = fixture.database.exportBackup()
        val owner = mainThreadValue { MainActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(activity) }
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        awaitUniqueTag("drawer-conversation:${first.id.value}")
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.drawer_manage_chats)).assertIsDisplayed().assertIsEnabled().performClick()
        ui.onNodeWithText("完成管理").assertIsDisplayed()
        node("drawer-selection-count").assertTextEquals("已选择 0 项")
        node("drawer-conversation:${first.id.value}").performClick().assertIsSelected()
        node("drawer-conversation:${second.id.value}").performClick().assertIsSelected()
        node("drawer-selection-count").assertTextEquals("已选择 2 项")
        node("drawer-conversation:${second.id.value}").performTouchInput { longClick() }
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.action_rename)).assertIsDisplayed()
        node("drawer-selection-count").assertTextEquals("已选择 2 项")
        node("drawer-conversation:${first.id.value}").assertIsSelected()
        node("drawer-conversation:${second.id.value}").assertIsSelected()
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.action_rename)).assertDoesNotExist()
        ui.onNodeWithText("完成管理").assertIsDisplayed()
        node("drawer-selection-count").assertTextEquals("已选择 2 项")
        assertEquals(owner, mainThreadValue { MainActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(activity) })
        assertEquals("chat", mainThreadValue { hostValue("getRetainedPage") })
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        assertDraft("管理时保留草稿")
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        node("drawer-selection-count").assertTextEquals("已选择 2 项")
        node("drawer-conversation:${first.id.value}").performClick().assertIsNotSelected()
        node("drawer-selection-count").assertTextEquals("已选择 1 项")
        ui.onNodeWithText("完成管理").performClick()
        node("drawer-selection-count").assertDoesNotExist()
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.drawer_manage_chats)).assertIsDisplayed().assertIsEnabled().performClick()
        node("drawer-selection-count").assertTextEquals("已选择 0 项")
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    private fun mainThreadValue(read: () -> Any?): Any? {
        var value: Any? = null
        mainThread { value = read() }
        return value
    }
}
