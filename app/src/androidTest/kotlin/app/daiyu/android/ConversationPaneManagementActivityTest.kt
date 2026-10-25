package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import app.daiyu.android.domain.Conversation
import app.daiyu.android.ui.model.ConversationPaneUiState
import app.daiyu.android.ui.model.searchResults

/** Ordinary selection changes the owner in place; management only changes its checkbox selection. */
class ConversationPaneManagementActivityTest : WiredActivityTestCase() {
    private fun hostValue(name: String): Any? = MainActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    @Suppress("UNCHECKED_CAST")
    private fun paneState() = (MainActivity::class.java.getDeclaredField("paneState\$delegate")
        .apply { isAccessible = true }.get(activity) as MutableState<ConversationPaneUiState>).value

    private fun editorGeneration() = MainActivity::class.java.getDeclaredField("editorGeneration")
        .apply { isAccessible = true }.getLong(activity)

    private fun selectedConversation() = MainActivity::class.java.getDeclaredField("selected")
        .apply { isAccessible = true }.get(activity) as Conversation?

    private fun revealConversation(conversation: Conversation) {
        val tag = "drawer-conversation:${conversation.id.value}"
        node("drawer-list").performScrollToKey(conversation.id.value)
        ui.mainClock.advanceTimeByFrame()
        awaitUniqueTag(tag)
        node(tag).assertIsDisplayed().performClick()
    }

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
        val previousEditor = retainedControls(activity)
        mainThread {
            assertEquals(1, MainActivity::class.java.getDeclaredField("selectionStart").apply { isAccessible = true }.getInt(activity)); assertEquals(3, MainActivity::class.java.getDeclaredField("selectionEnd").apply { isAccessible = true }.getInt(activity))
        }
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        mainThread {
            previousEditor.onSelectionChange(0, 0)
            assertEquals(1, MainActivity::class.java.getDeclaredField("selectionStart").apply { isAccessible = true }.getInt(activity)); assertEquals(3, MainActivity::class.java.getDeclaredField("selectionEnd").apply { isAccessible = true }.getInt(activity))
        }
        awaitUniqueTag("drawer-conversation:${second.id.value}")
        node("drawer-search").performClick()
        node("drawer-search-input").performTextReplacement("保留侧栏")
        dismissIme()
        val expectedResults = setOf(first.id.value, second.id.value)
        awaitControl("both filtered conversations published") {
            paneState().conversationsLoaded && paneState().searchQuery == "保留侧栏" &&
                paneState().searchResults().map { it.id }.toSet() == expectedResults
        }
        for (conversation in listOf(second, second, first)) {
            val generation = editorGeneration()
            revealConversation(conversation)
            awaitControl("each selection including reselection gets a fresh editor") {
                editorGeneration() > generation
            }
            assertSelectedWithSidebarOpen(conversation, if (conversation.id == first.id) "甲编辑后的草稿" else "乙草稿")
            mainThread { assertEquals(expectedResults, paneState().searchResults().map { it.id }.toSet()) }
        }
        assertEquals(before, fixture.database.exportBackup())
        awaitNodeWindowFocus(node("conversation-drawer"))
        dismissIme()
        awaitImeFrame(node("drawer-search-input"), visible = false)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitControl("explicit hardware Back dismisses sidebar") {
            hostValue("getSidebarVisible") == false
        }
        mainThread {
            assertEquals(false, hostValue("getSidebarVisible"))
            assertEquals(first.id, selectedConversation()?.id)
            assertFalse(activity.isFinishing); assertFalse(activity.isDestroyed)
        }
        assertDraft("甲编辑后的草稿")
        assertSelection(1, 3)
        node("chat-draft").assertIsEnabled()
        ui.onNodeWithTag("drawer-search-input", useUnmergedTree = true)
            .assertIsNotEnabled().assert(isFocused().not())

        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        node("drawer-search-input").assertEditableText("保留侧栏")
        revealConversation(second)
        assertSelectedWithSidebarOpen(second, "乙草稿")
        val drawer = node("conversation-drawer")
        awaitNodeWindowFocus(drawer)
        val drawerRoot = ui.onNode(isRoot() and hasAnyDescendant(hasTestTag("conversation-drawer")), useUnmergedTree = true)
        val root = drawerRoot.fetchSemanticsNode().boundsInRoot
        val pane = drawer.fetchSemanticsNode().boundsInRoot
        assertTrue("Scrim is exposed to the right of the pane", root.right > pane.right)
        drawerRoot.performTouchInput {
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

    fun testConversationLongPressKeepsOriginalActionsWithoutTaskRecords() {
        lateinit var conversation: Conversation
        mainThread {
            conversation = fixture.database.create()
            fixture.database.rename(conversation.id, "保留原会话菜单")
        }
        val before = fixture.database.exportBackup()
        val owner = mainThreadValue { MainActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(activity) }
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        val tag = "drawer-conversation:${conversation.id.value}"
        node("drawer-list").performScrollToKey(conversation.id.value)
        awaitUniqueTag(tag)
        node(tag).performTouchInput { longClick() }
        for (id in listOf(R.string.action_rename, R.string.conversation_pin,
            R.string.conversation_export, R.string.drawer_move_to_folder, R.string.action_delete)) {
            ui.onNodeWithText(instrumentation.targetContext.getString(id))
                .assertIsDisplayed().assertHasClickAction()
        }
        ui.onAllNodesWithText(instrumentation.targetContext.getString(R.string.business_task_records))
            .assertCountEquals(0)
        assertEquals("chat", mainThreadValue { hostValue("getRetainedPage") })
        assertEquals(true, mainThreadValue { hostValue("getSidebarVisible") })
        assertEquals(owner, mainThreadValue { MainActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(activity) })
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
