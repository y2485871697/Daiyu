package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.domain.*

class ConversationBatchDeleteActivityTest : WiredActivityTestCase() {
    private val deleteNotice = java.util.concurrent.atomic.AtomicReference<String?>()

    internal override fun createFixture() = super.createFixture().also { created ->
        created.controller.observe {
            created.controller.dataNotice?.let { deleteNotice.set(it) }
        }
    }

    private fun seed(): List<Conversation> {
        val conversations = (1..3).map { fixture.database.create("default") }
        conversations.forEachIndexed { index, conversation ->
            fixture.database.rename(conversation.id, "批量删除验证${index + 1}")
            val key = conversation.id.value
            val user = Message(MessageId("$key-user"), conversation.id, RunId("$key-run"), Message.USER_ROLE,
                listOf(MessageBlock.Text("q", "批量删除历史${index + 1}")), RunStatus.COMPLETED)
            val answer = user.copy(id = MessageId("$key-answer"), role = Message.ASSISTANT_ROLE, status = RunStatus.RUNNING)
            fixture.database.send(user, answer)
            fixture.database.save(answer.copy(status = RunStatus.COMPLETED))
        }
        dismissIme()
        mainThread { invokeHost(activity, "openSidebar") }
        awaitUniqueTag("conversation-drawer")
        awaitNodeWindowFocus(node("conversation-drawer"))
        node("drawer-manage").performClick()
        node("drawer-selection-count").assertTextEquals("已选择 0 项")
        return conversations
    }

    private fun select(conversation: Conversation) {
        node("drawer-list").performScrollToKey(conversation.id.value)
        node("drawer-conversation:${conversation.id.value}").performClick().assertIsSelected()
    }

    private fun batchMenu(conversation: Conversation) {
        node("drawer-list").performScrollToKey(conversation.id.value)
        awaitNodeWindowFocus(node("conversation-drawer"))
        node("drawer-conversation:${conversation.id.value}").performTouchInput { longClick() }
    }

    private fun openConfirmation(conversation: Conversation) {
        batchMenu(conversation)
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.conversation_batch_delete)).performClick()
        awaitUniqueTag("dialog-confirm")
        awaitDialogEnter(node("dialog-confirm"))
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        ui.onNodeWithText("确认删除此会话及其消息？").assertDoesNotExist()
        ui.onNodeWithContentDescription("关闭", useUnmergedTree = true).assertDoesNotExist()
    }

    fun testManagementDeletesSelectedConversationsAndMessagesNotLongPressedUnselectedRow() {
        val (first, second, survivor) = seed()
        val before = fixture.database.exportBackup()
        val ids = fixture.database.conversations().map { it.id }.toSet()
        val survivorMessages = fixture.database.messages(survivor.id)
        select(first)
        select(second)
        node("drawer-selection-count").assertTextEquals("已选择 2 项")
        openConfirmation(survivor)
        node("dialog-cancel").performClick()
        ui.waitForIdle()
        assertEquals(before, fixture.database.exportBackup())
        node("drawer-selection-count").assertTextEquals("已选择 2 项")
        openConfirmation(survivor)
        node("dialog-confirm").performClick()
        awaitControl("all selected owners deleted and data operation finished") {
            !fixture.controller.dataBusy && deleteNotice.get() != null
        }
        assertEquals("删除已完成", deleteNotice.get())
        assertEquals(ids - setOf(first.id, second.id), fixture.database.conversations().map { it.id }.toSet())
        for (conversation in listOf(first, second)) {
            fixture.database.readableDatabase.rawQuery("SELECT 1 FROM messages WHERE conversation_id=? LIMIT 1",
                arrayOf(conversation.id.value)).use { assertFalse("selected messages remain", it.moveToFirst()) }
        }
        assertEquals(survivorMessages, fixture.database.messages(survivor.id))
        ui.waitForIdle()
        node("drawer-selection-count").assertTextEquals("已选择 0 项")
        assertEquals(0, fixture.opens.get())
    }

    fun testManagementBatchDeleteIsDisabledWithNoSelectedConversations() {
        val conversations = seed()
        val before = fixture.database.exportBackup()
        batchMenu(conversations.last())
        ui.onNodeWithText(instrumentation.targetContext.getString(R.string.conversation_batch_delete)).assertIsNotEnabled()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testManagementFailureRollsBackEverySelectedConversationAndDoesNotReportSuccess() {
        val (first, second, survivor) = seed()
        val before = fixture.database.exportBackup()
        fixture.database.writableDatabase.execSQL("CREATE TRIGGER reject_batch_delete BEFORE DELETE ON conversations " +
            "WHEN OLD.id='${second.id.value}' BEGIN SELECT RAISE(IGNORE); END")
        try {
            select(first)
            select(second)
            openConfirmation(survivor)
            node("dialog-confirm").performClick()
            awaitControl("batch deletion failure reported") {
                !fixture.controller.dataBusy && deleteNotice.get() != null
            }
            assertTrue(deleteNotice.get().orEmpty().startsWith("删除未确认"))
            assertEquals(before, fixture.database.exportBackup())
            node("drawer-selection-count").assertTextEquals("已选择 2 项")
            assertEquals(0, fixture.opens.get())
        } finally {
            fixture.database.writableDatabase.execSQL("DROP TRIGGER reject_batch_delete")
        }
    }

    fun testBatchTransactionRollsBackWhenSelectedMessageDeleteIsIgnored() {
        val (first, second) = seed()
        val before = fixture.database.exportBackup()
        fixture.database.writableDatabase.execSQL("CREATE TRIGGER reject_batch_message BEFORE DELETE ON messages " +
            "WHEN OLD.conversation_id='${second.id.value}' BEGIN SELECT RAISE(IGNORE); END")
        try {
            var failed = false
            try { fixture.database.deleteConversations(linkedSetOf(first.id, second.id)) }
            catch (_: Exception) { failed = true }
            assertTrue("ignored message delete must fail", failed)
            assertEquals(before, fixture.database.exportBackup())
        } finally {
            fixture.database.writableDatabase.execSQL("DROP TRIGGER reject_batch_message")
        }
    }
}
