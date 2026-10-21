package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.ui.test.*
import app.daiyu.android.domain.Conversation

/** The preview's manage action selects in place and never mutates conversations. */
class ConversationPaneManagementActivityTest : WiredActivityTestCase() {
    private fun hostValue(name: String): Any? = MainActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

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
