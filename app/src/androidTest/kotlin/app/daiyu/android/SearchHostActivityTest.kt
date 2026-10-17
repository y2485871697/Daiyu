package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import java.util.concurrent.TimeUnit

/** Exercises the real MainActivity routes + SQLite adapter, not a replacement setContent host. */
class SearchHostActivityTest : WiredActivityTestCase() {
    private fun text(id: Int) = activity.getString(id)
    private fun page(id: Int) = node("page:${text(id)}")
    private fun awaitTag(tag: String) {
        ui.waitUntil(5_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitPage(id: Int) = awaitTag("page:${text(id)}")
    private fun openManage() {
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        ui.onNodeWithText(text(R.string.drawer_manage_chats)).performTouchInput { click() }
        awaitPage(R.string.history_page_title)
    }
    private fun openMessages() {
        menu("搜索记录")
        awaitPage(R.string.search_messages_title)
    }
    private fun seed(title: String, key: String, body: String, later: Int = 0): Pair<Conversation, Message> {
        lateinit var conversation: Conversation
        lateinit var first: Message
        mainThread {
            conversation = fixture.database.create("default")
            repeat(later + 1) { index ->
                val user = Message(MessageId("$key-user-$index"), conversation.id, RunId("$key-run-$index"), "user",
                    listOf(MessageBlock.Text("text", if (index == 0) body else "后续消息 $index")), RunStatus.COMPLETED)
                val answer = user.copy(id = MessageId("$key-answer-$index"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
                fixture.database.send(user, answer)
                fixture.database.save(answer.copy(status = RunStatus.COMPLETED))
                if (index == 0) first = user
            }
            fixture.database.rename(conversation.id, title)
            conversation = fixture.database.conversations().single { it.id == conversation.id }
        }
        return conversation to first
    }

    fun testManageConversationSearchUsesTitleSnapshotAndBackRestoresManageThenChatDraft() {
        val (first, _) = seed("中文检索标题", "first", "普通预览")
        seed("其他会话", "other", "别的预览")
        setDraft("搜索前未发送草稿")
        val before = fixture.database.exportBackup()
        openManage()
        ui.onNodeWithText(text(R.string.search_conversations_title)).performClick()
        awaitPage(R.string.search_conversations_title)
        node("search:query").performTextReplacement("中文检索")
        awaitTag("search:conversation:${first.id.value}")
        node("search:conversation:${first.id.value}").assertTextContains("中文检索标题")
        assertEquals(before, fixture.database.exportBackup())
        backToPage(activity, "page:${text(R.string.history_page_title)}")
        page(R.string.history_page_title).assertExists()
        backToChat(activity)
        assertDraft("搜索前未发送草稿")
        assertEquals(0, fixture.opens.get())

        openManage()
        ui.onNodeWithText(text(R.string.search_conversations_title)).performClick()
        node("search:query").performTextReplacement("中文检索")
        awaitTag("search:conversation:${first.id.value}")
        node("search:conversation:${first.id.value}").performClick()
        node("chat-title").assertTextEquals("中文检索标题")
        page(R.string.search_conversations_title).assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testChatMessageSearchScopesCurrentAndOpensOlderOwnerWindow() {
        val (target, older) = seed("较早命中所在会话", "target", "独特旧记录关键词", later = 30)
        val (other, otherMessage) = seed("另一个会话", "other", "独特旧记录关键词")
        mainThread { activity.select(target) }
        setDraft("当前会话草稿")
        openMessages()
        node("search:query").performTextReplacement("独特旧记录关键词")
        awaitTag("search:message:${other.id.value}:${otherMessage.id.value}")
        ui.onNodeWithText(text(R.string.search_current)).assertIsEnabled().performClick()
        val tag = "search:message:${target.id.value}:${older.id.value}"
        awaitTag(tag)
        node("search:message:${other.id.value}:${otherMessage.id.value}").assertDoesNotExist()
        page(R.string.search_messages_title).performScrollToNode(hasTestTag(tag))
        node(tag).performClick()
        node("chat-title").assertTextEquals("较早命中所在会话")
        awaitTag("chat-turn:${older.id.value}")
        node("chat-turn:${older.id.value}").assertExists()
        assertDraft("当前会话草稿")
        assertEquals(0, fixture.opens.get())
    }

    fun testManageMessageEntryHasNoFakeCurrentScopeAndBackDoesNotExitManage() {
        openManage()
        ui.onNodeWithContentDescription(text(R.string.action_search_history)).performClick()
        awaitPage(R.string.search_messages_title)
        ui.onNodeWithText(text(R.string.search_current)).assertIsNotEnabled()
        ui.onNodeWithText(text(R.string.search_current_unavailable)).assertExists()
        node("search:prompt").assertExists()
        node("search:query").performTextReplacement("没有这种记录")
        awaitTag("search:empty")
        node("search:error").assertDoesNotExist()
        backToPage(activity, "page:${text(R.string.history_page_title)}")
        page(R.string.history_page_title).assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testRealReaderFailureIsRetryableAndDifferentFromEmptyAndBlank() {
        val (conversation, first) = seed("读取失败测试", "broken", "修复后可检索", later = 1)
        mainThread { activity.select(conversation) }
        val originalBlocks = fixture.database.readableDatabase.rawQuery("SELECT blocks FROM messages WHERE id=?",
            arrayOf(first.id.value)).use { it.moveToFirst(); it.getString(0) }
        fixture.database.writableDatabase.execSQL("UPDATE messages SET blocks='not-json' WHERE id=?", arrayOf(first.id.value))
        openMessages()
        node("search:query").performTextReplacement("修复后可检索")
        awaitTag("search:error")
        node("search:empty").assertDoesNotExist()
        fixture.database.writableDatabase.execSQL("UPDATE messages SET blocks=? WHERE id=?", arrayOf(originalBlocks, first.id.value))
        node("search:retry").performClick()
        awaitTag("search:message:${conversation.id.value}:${first.id.value}")
        ui.onNodeWithText(activity.getString(R.string.search_message_count, 1)).assertExists()
        node("search:error").assertDoesNotExist()
        ui.onNodeWithText(activity.getString(R.string.search_limited_results, 200)).assertDoesNotExist()
        node("search:query").performTextReplacement("不存在的正文")
        awaitTag("search:empty")
        node("search:error").assertDoesNotExist()
        node("search:query").performTextReplacement("")
        node("search:prompt").assertExists()
        backToChat(activity)
        node("chat-draft").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testLoadMoreOpens201stPersistedMessageWithoutTruncation() {
        lateinit var conversation: Conversation
        lateinit var last: Message
        mainThread {
            conversation = fixture.database.create("default")
            repeat(201) { index ->
                val user = Message(MessageId("paging-ui-user-$index"), conversation.id, RunId("paging-ui-run-$index"),
                    "user", listOf(MessageBlock.Text("paging-ui-text-$index", "分页UI命中 $index")), RunStatus.COMPLETED)
                val answer = user.copy(id = MessageId("paging-ui-answer-$index"), role = "assistant",
                    blocks = emptyList(), status = RunStatus.RUNNING)
                fixture.database.send(user, answer)
                fixture.database.save(answer.copy(status = RunStatus.COMPLETED))
                last = user
            }
            repeat(30) { index ->
                val user = Message(MessageId("paging-tail-user-$index"), conversation.id, RunId("paging-tail-run-$index"),
                    "user", listOf(MessageBlock.Text("paging-tail-text-$index", "后续不匹配正文 $index")), RunStatus.COMPLETED)
                val answer = user.copy(id = MessageId("paging-tail-answer-$index"), role = "assistant",
                    blocks = emptyList(), status = RunStatus.RUNNING)
                fixture.database.send(user, answer)
                fixture.database.save(answer.copy(status = RunStatus.COMPLETED))
            }
            activity.select(conversation)
        }
        setDraft("分页跳转前草稿")
        openMessages()
        node("search:query").performTextReplacement("分页UI命中")
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText(activity.getString(R.string.search_message_count, 200)).fetchSemanticsNodes().isNotEmpty()
        }
        val lastTag = "search:message:${conversation.id.value}:${last.id.value}"
        page(R.string.search_messages_title).performScrollToNode(hasText(text(R.string.search_load_more)))
        ui.onNodeWithText(text(R.string.search_load_more)).performClick()
        page(R.string.search_messages_title).performScrollToIndex(0)
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText(activity.getString(R.string.search_message_count, 201)).fetchSemanticsNodes().isNotEmpty()
        }
        page(R.string.search_messages_title).performScrollToNode(hasTestTag(lastTag))
        node(lastTag).performClick()
        page(R.string.search_messages_title).assertDoesNotExist()
        awaitTag("chat-turn:${last.id.value}")
        node("chat-turn:${last.id.value}").assertIsDisplayed()
        assertDraft("分页跳转前草稿")
        assertEquals(0, fixture.opens.get())
    }

    fun testDeletedHitCannotNavigateToAnotherConversation() {
        val (conversation, message) = seed("将被删除的命中", "deleted", "删除前关键词")
        openMessages()
        node("search:query").performTextReplacement("删除前关键词")
        val tag = "search:message:${conversation.id.value}:${message.id.value}"
        awaitTag(tag)
        mainThread { fixture.database.delete(conversation.id) }
        node(tag).performClick()
        page(R.string.search_messages_title).assertExists()
        node("chat-draft").assertDoesNotExist()
        backToChat(activity)
        node("chat-draft").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testRecreationDisposesSearchAndPreservesUnsentDraft() {
        setDraft("重建时保留的搜索草稿")
        openMessages()
        node("search:query").performTextReplacement("不会发送的检索词")
        val old = activity
        activity = recreateActivity(old)
        page(R.string.search_messages_title).assertDoesNotExist()
        assertDraft("重建时保留的搜索草稿")
        assertTrue(old.isDestroyed)
        assertEquals(0, fixture.opens.get())
    }

    fun testSearchNavigationDoesNotPauseStopOrResendActiveRun() {
        val session = fixture.session(holdSeconds = 120)
        setDraft("运行中的提问")
        node("chat-send").performClick()
        awaitTextSendResult(activity)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(fixture.controller.active)
        setDraft("保留追加草稿")
        try {
            openMessages()
            assertEquals(owner.id, fixture.controller.active!!.id)
            assertFalse(fixture.controller.runState(owner)!!.pauseRequested)
            assertEquals(1L, session.cancelled.count)
            backToChat(activity)
            assertDraft("保留追加草稿")
            assertEquals(owner.id, fixture.controller.active!!.id)
            assertEquals(1, fixture.opens.get())
        } finally { session.release.countDown() }
        awaitControl("original finishes") { fixture.controller.active == null }
    }
}
