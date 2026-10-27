package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import app.daiyu.android.context.ContextService
import app.daiyu.android.domain.Conversation
import app.daiyu.android.ui.RetainedConversationData

class ConversationFolderPickerActivityTest : WiredActivityTestCase() {
    private fun folderData() = RetainedConversationData(fixture.database, ContextService(activity, fixture.database))

    private fun openPicker(conversation: Conversation) {
        mainThread { invokeHost(activity, "editConversation", conversation.id.value, true) }
        awaitUniqueTag("folder-picker-list")
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        ui.waitForIdle()
    }

    private fun awaitClosed() {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag("folder-picker-list").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun current(conversation: Conversation) = fixture.database.conversations().single { it.id == conversation.id }

    fun testSidebarMoveSelectsPersistedEmptyFolderAndOnlySaveWrites() {
        lateinit var conversation: Conversation
        mainThread {
            folderData().createFolder("空文件夹甲")
            folderData().createFolder("空文件夹乙")
            conversation = fixture.database.create()
            fixture.database.organize(conversation.id, "", true)
            activity.select(current(conversation))
        }
        dismissIme()
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        node("drawer-list").performScrollToKey(conversation.id.value)
        awaitUniqueTag("drawer-conversation:${conversation.id.value}")
        node("drawer-conversation:${conversation.id.value}").performTouchInput { longClick() }
        ui.onNodeWithText(activity.getString(R.string.drawer_move_to_folder)).performClick()
        awaitUniqueTag("folder-picker-list")
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        node("prompt-input").assertDoesNotExist()
        ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
        node("folder-choice-unfiled").assertIsSelected()
        val before = fixture.database.exportBackup()
        node("folder-choice:空文件夹乙").performClick().assertIsSelected()
        assertEquals(before, fixture.database.exportBackup())
        node("dialog-confirm").performClick()
        awaitClosed()
        mainThread {
            assertEquals("空文件夹乙", current(conversation).folder)
            assertTrue(current(conversation).pinned)
            assertEquals(listOf("空文件夹乙", "空文件夹甲").sorted(), folderData().folders().map { it.id })
        }
        openPicker(conversation)
        node("folder-choice:空文件夹乙").assertIsSelected()
        node("dialog-cancel").performClick()
        awaitClosed()
        assertEquals(0, fixture.opens.get())
    }

    fun testCancelBackAndOutsideDiscardSelection() {
        lateinit var conversation: Conversation
        mainThread {
            folderData().createFolder("取消目标")
            conversation = fixture.database.create()
        }
        val before = fixture.database.exportBackup()
        for (action in listOf("cancel", "back", "outside")) {
            openPicker(conversation)
            node("folder-choice:取消目标").performClick().assertIsSelected()
            when (action) {
                "cancel" -> node("dialog-cancel").performTouchInput { click() }
                "back" -> instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                "outside" -> node("preview-dialog-viewport").performTouchInput { click(Offset(2f, 2f)) }
            }
            awaitClosed()
            assertEquals(before, fixture.database.exportBackup())
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testEmptyCatalogAndMovingBackToUnfiledNeverCreateFolders() {
        lateinit var conversation: Conversation
        mainThread { conversation = fixture.database.create() }
        openPicker(conversation)
        node("folder-picker-empty").assertIsDisplayed()
        node("folder-choice-unfiled").assertIsSelected()
        node("prompt-input").assertDoesNotExist()
        val before = fixture.database.exportBackup()
        node("dialog-confirm").performClick()
        awaitClosed()
        assertEquals(before, fixture.database.exportBackup())
        mainThread {
            folderData().createFolder("原文件夹")
            fixture.database.organize(conversation.id, "原文件夹", true)
        }
        openPicker(conversation)
        node("folder-choice:原文件夹").assertIsSelected()
        node("folder-choice-unfiled").performClick()
        node("dialog-confirm").performClick()
        awaitClosed()
        mainThread {
            assertEquals("", current(conversation).folder)
            assertTrue(current(conversation).pinned)
            assertEquals(listOf("原文件夹"), folderData().folders().map { it.id })
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testDeletedDestinationCannotBeRecreatedByStaleSelection() {
        lateinit var conversation: Conversation
        mainThread {
            folderData().createFolder("原归属")
            folderData().createFolder("将删除的目标")
            conversation = fixture.database.create()
            fixture.database.organize(conversation.id, "原归属", true)
        }
        openPicker(conversation)
        node("folder-choice:将删除的目标").performClick()
        mainThread { folderData().changeFolder("将删除的目标", null) }
        val before = fixture.database.exportBackup()
        node("dialog-confirm").performClick()
        awaitClosed()
        assertEquals(before, fixture.database.exportBackup())
        mainThread {
            assertEquals("原归属", current(conversation).folder)
            assertTrue(current(conversation).pinned)
            assertEquals(listOf("原归属"), folderData().folders().map { it.id })
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testLongFolderListScrollsWithoutHidingFooter() {
        lateinit var conversation: Conversation
        mainThread {
            repeat(60) { folderData().createFolder("文件夹%02d".format(it)) }
            conversation = fixture.database.create()
        }
        openPicker(conversation)
        node("dialog-cancel").assertIsDisplayed()
        node("dialog-confirm").assertIsDisplayed()
        node("folder-picker-list").performScrollToNode(hasTestTag("folder-choice:文件夹59"))
        node("folder-choice:文件夹59").performClick().assertIsSelected()
        node("dialog-confirm").assertIsDisplayed().performClick()
        awaitClosed()
        mainThread { assertEquals("文件夹59", current(conversation).folder) }
        assertEquals(0, fixture.opens.get())
    }
}
