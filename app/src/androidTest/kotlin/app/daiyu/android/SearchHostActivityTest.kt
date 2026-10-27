package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.domain.*
import app.daiyu.android.context.Utf8Payload
import app.daiyu.android.ui.RetainedConversationData
import app.daiyu.android.ui.model.MessageSearchHit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Exercises the real MainActivity routes + SQLite adapter, not a replacement setContent host. */
class SearchHostActivityTest : WiredActivityTestCase() {
    private fun replaceSearchQuery(query: String) {
        node("search:query")
            .performSemanticsAction(SemanticsActions.SetText) { setText ->
                assertTrue(setText(AnnotatedString(query)))
            }
            .assertEditableText(query)
        dismissIme()
    }
    private fun text(id: Int) = activity.getString(id)
    private fun page(id: Int) = node("page:${text(id)}")
    private fun awaitTag(tag: String) {
        ui.waitUntil(5_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitPage(id: Int) = awaitTag("page:${text(id)}")
    private fun openManage() {
        // Legacy search-route coverage is separate from the drawer's in-place selection UI.
        mainThread {
            MainActivity::class.java.getDeclaredMethod("updatePane").apply { isAccessible = true }.invoke(activity)
            MainActivity::class.java.getDeclaredMethod("setRetainedPage", String::class.java)
                .apply { isAccessible = true }.invoke(activity, "manage")
        }
        awaitPage(R.string.history_page_title)
    }

    private fun openMessages() {
        mainThread { retainedControls(activity).onMenuAction(ChatMenuAction.SEARCH) }
        awaitTag("search-history-dialog")
    }
    private fun seed(title: String, key: String, body: String, later: Int = 0, segmentedFirstAnswer: Boolean = false): Pair<Conversation, Message> {
        lateinit var conversation: Conversation
        lateinit var first: Message
        mainThread {
            conversation = fixture.database.create("default")
            repeat(later + 1) { index ->
                val user = Message(MessageId("$key-user-$index"), conversation.id, RunId("$key-run-$index"), "user",
                    listOf(MessageBlock.Text("text", if (index == 0) body else "后续消息 $index")), RunStatus.COMPLETED)
                val answer = user.copy(id = MessageId("$key-answer-$index"), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
                fixture.database.send(user, answer)
                val segments = if (segmentedFirstAnswer && index == 0) listOf(
                    MessageHistorySegment(answer.id, "assistant", listOf(MessageBlock.Text("first", "相同词前段"))),
                    MessageHistorySegment(MessageId("supplement"), "user", listOf(MessageBlock.Text("supplement", "补充定位词"))),
                    MessageHistorySegment(answer.id, "assistant", listOf(MessageBlock.Text("last", "相同词后段"))),
                ) else emptyList()
                fixture.database.save(answer.copy(status = RunStatus.COMPLETED,
                    blocks = segments.filter { it.role == "assistant" }.flatMap { it.blocks },
                    historySegments = segments))
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
        replaceSearchQuery("中文检索")
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
        replaceSearchQuery("中文检索")
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
        replaceSearchQuery("独特旧记录关键词")
        val tag = "search:message:${target.id.value}:${older.id.value}:0"
        awaitTag(tag)
        node("search:message:${other.id.value}:${otherMessage.id.value}:0").assertDoesNotExist()
        node("search-history-results").scrollToDescendant(hasTestTag(tag))
        node(tag).performClick()
        node("chat-title").assertTextEquals("较早命中所在会话")
        awaitTag("chat-turn:${older.id.value}")
        node("chat-turn:${older.id.value}").assertIsDisplayed()
        node("search-history-dialog").assertDoesNotExist()
        assertDraft("当前会话草稿")
        assertEquals(0, fixture.opens.get())
    }

    fun testSearchHitOpensSupplementAndLaterRepeatedAssistantSegment() {
        val (conversation, _) = seed("分段检索", "segments", "普通提问", later = 30, segmentedFirstAnswer = true)
        val ownerId = MessageId("segments-answer-0")
        mainThread { activity.select(conversation) }
        setDraft("分段检索草稿")
        openMessages()
        replaceSearchQuery("补充定位词")
        val supplementTag = "search:message:${conversation.id.value}:supplement:0"
        awaitTag(supplementTag)
        node(supplementTag).performClick()
        awaitTag("chat-turn:supplement")
        node("chat-turn:supplement").assertIsDisplayed()
        openMessages()
        replaceSearchQuery("相同词")
        val lastTag = "search:message:${conversation.id.value}:${ownerId.value}:1"
        awaitTag(lastTag)
        node(lastTag).performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText("相同词后段", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("相同词后段", substring = true).assertIsDisplayed()
        node("search-history-dialog").assertDoesNotExist()
        assertDraft("分段检索草稿")
        assertEquals(0, fixture.opens.get())
    }

    fun testManageMessageEntryHasNoFakeCurrentScopeAndBackDoesNotExitManage() {
        openManage()
        ui.onNodeWithContentDescription(text(R.string.action_search_history)).performClick()
        awaitPage(R.string.search_messages_title)
        ui.onNodeWithText(text(R.string.search_current)).assertIsNotEnabled()
        ui.onNodeWithText(text(R.string.search_current_unavailable)).assertExists()
        node("search:prompt").assertExists()
        replaceSearchQuery("没有这种记录")
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
        replaceSearchQuery("修复后可检索")
        awaitTag("search:error")
        node("search:empty").assertDoesNotExist()
        fixture.database.writableDatabase.execSQL("UPDATE messages SET blocks=? WHERE id=?", arrayOf(originalBlocks, first.id.value))
        node("search:retry").performClick()
        awaitTag("search:message:${conversation.id.value}:${first.id.value}:0")
        node("search:error").assertDoesNotExist()
        ui.onNodeWithText(activity.getString(R.string.search_limited_results, 200)).assertDoesNotExist()
        replaceSearchQuery("不存在的正文")
        awaitTag("search:empty")
        node("search:error").assertDoesNotExist()
        replaceSearchQuery("")
        node("search:prompt").assertDoesNotExist()
        node("search:loading").assertDoesNotExist()
        node("search:empty").assertDoesNotExist()
        backToChat(activity)
        node("chat-draft").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testLoadMoreOpens201stPersistedMessageWithoutTruncation() {
        // Import real physical owners once instead of replaying full-history send CAS for every row.
        val conversationId = ConversationId("paging-ui-conversation")
        val messages = JSONArray()
        fun appendTurn(key: String, index: Int, body: String) {
            fun row(id: String, role: String, blocks: JSONArray) = JSONObject()
                .put("id", id).put("conversation_id", conversationId.value).put("run_id", "$key-run-$index")
                .put("role", role).put("blocks", blocks).put("status", RunStatus.COMPLETED.name)
                .put("error", JSONObject.NULL).put("protocol", JSONArray())
            val blocks = JSONArray().put(JSONObject().put("type", "text").put("item", "$key-text-$index").put("text", body))
            messages.put(row("$key-user-$index", "user", blocks))
            messages.put(row("$key-answer-$index", "assistant", JSONArray()))
        }
        repeat(201) { index -> appendTurn("paging-ui", index, "分页UI命中 $index") }
        repeat(30) { index -> appendTurn("paging-tail", index, "后续不匹配正文 $index") }
        val payload = JSONObject()
            .put("conversations", JSONArray().put(JSONObject().put("id", conversationId.value)
                .put("title", "新会话").put("draft", "").put("updated", 1L).put("folder", "")
                .put("pinned", false).put("assistant_id", "default")))
            .put("messages", messages).put("draft", "").toString()
        val backup = JSONObject().put("format", "daiyu-neutral-text").put("version", 2)
            .put("conversation_count", 1).put("message_count", messages.length())
            .put("sha256", Utf8Payload.sha256(payload)).put("payload", payload).toString()
        assertEquals(1 to 462, fixture.database.importBackup(backup))
        val conversation = fixture.database.conversations().single { it.id == conversationId }
        val lastId = MessageId("paging-ui-user-200")
        mainThread { activity.select(conversation) }
        setDraft("分页跳转前草稿")
        openMessages()
        replaceSearchQuery("分页UI命中")
        awaitTag("search:message:${conversation.id.value}:paging-ui-user-0:0")
        val lastTag = "search:message:${conversation.id.value}:${lastId.value}:0"
        node("search-history-results").scrollToDescendant(hasTestTag("search:more"))
        node("search:more").performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("search:more").fetchSemanticsNodes().isEmpty() &&
                ui.onAllNodesWithTag("search:loading").fetchSemanticsNodes().isEmpty()
        }
        node("search-history-results").scrollToDescendant(hasTestTag(lastTag))
        node(lastTag).performClick()
        node("search-history-dialog").assertDoesNotExist()
        awaitTag("chat-turn:${lastId.value}")
        node("chat-turn:${lastId.value}").assertIsDisplayed()
        assertDraft("分页跳转前草稿")
        assertEquals(0, fixture.opens.get())
    }

    fun testDeletedHitCannotNavigateToAnotherConversation() {
        val (conversation, message) = seed("将被删除的命中", "deleted", "删除前关键词")
        mainThread { activity.select(conversation) }
        openMessages()
        replaceSearchQuery("删除前关键词")
        val tag = "search:message:${conversation.id.value}:${message.id.value}:0"
        awaitTag(tag)
        mainThread { fixture.database.delete(conversation.id) }
        node(tag).performClick()
        node("search-history-dialog").assertExists()
        node("chat-draft").assertExists()
        backToChat(activity)
        node("chat-draft").assertExists()
        assertEquals(0, fixture.opens.get())
    }

    fun testEmptyDraftSearchDoesNotSearchOtherConversations() {
        seed("其他会话", "not-current", "不应跨会话展示")
        mainThread { activity.select(null) }
        setDraft("未发送草稿")
        openMessages()
        replaceSearchQuery("不应跨会话展示")
        node("search:empty").assertExists()
        node("search:loading").assertDoesNotExist()
        backToChat(activity)
        assertDraft("未发送草稿")
        assertEquals(0, fixture.opens.get())
    }

    fun testManageGlobalSearchStillOpensAnotherConversation() {
        val (conversation, message) = seed("全局入口会话", "global", "全局正文词")
        openManage()
        ui.onNodeWithContentDescription(text(R.string.action_search_history)).performClick()
        awaitPage(R.string.search_messages_title)
        replaceSearchQuery("全局正文词")
        val tag = "search:message:${conversation.id.value}:${message.id.value}"
        awaitTag(tag)
        node(tag).performClick()
        awaitTag("chat-turn:${message.id.value}")
        node("chat-turn:${message.id.value}").assertIsDisplayed()
        node("chat-title").assertTextEquals("全局入口会话")
        assertEquals(0, fixture.opens.get())
    }

    fun testPausePreservesSearchButOldHitCannotNavigateAfterResume() {
        val (conversation, message) = seed("暂停检索", "pause", "暂停前搜索词")
        mainThread { activity.select(conversation) }
        setDraft("暂停保留草稿")
        openMessages()
        replaceSearchQuery("暂停前搜索词")
        val tag = "search:message:${conversation.id.value}:${message.id.value}:0"
        awaitTag(tag)
        lateinit var oldClick: () -> Unit
        mainThread {
            val page = checkNotNull(invokeHost(activity, "getSearchPage"))
            @Suppress("UNCHECKED_CAST")
            val open = invokeHost(activity, "historySearchHitAction", page) as (MessageSearchHit) -> Unit
            val hit = (invokeHost(activity, "getRetainedData") as RetainedConversationData)
                .searchPage("暂停前搜索词", conversation.id.value).results.single {
                    it.messageId == message.id.value && it.segmentIndex == 0
                }
            oldClick = { open(hit) }
        }
        val generation = MainActivity::class.java.getDeclaredField("editorGeneration").apply { isAccessible = true }
        fun replayOldHit() {
            val before = generation.getLong(activity)
            oldClick()
            assertEquals(before, generation.getLong(activity))
            assertNotNull(invokeHost(activity, "getSearchPage"))
            assertEquals("history-search", invokeHost(activity, "getRetainedPage"))
        }
        realPauseResume(activity, atPause = { replayOldHit() })
        awaitTag("search-history-dialog")
        node("search:query").assertEditableText("暂停前搜索词")
        awaitTag(tag)
        mainThread { replayOldHit() }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        assertDraft("暂停保留草稿")
        assertEquals(0, fixture.opens.get())
    }

    fun testRecreationDisposesSearchAndPreservesUnsentDraft() {
        setDraft("重建时保留的搜索草稿")
        openMessages()
        replaceSearchQuery("不会发送的检索词")
        val old = activity
        activity = recreateActivity(old)
        node("search-history-dialog").assertDoesNotExist()
        assertDraft("重建时保留的搜索草稿")
        assertTrue(old.isDestroyed)
        assertEquals(0, fixture.opens.get())
    }

    fun testSearchNavigationDoesNotPauseStopOrResendActiveRun() {
        val session = fixture.session(holdSeconds = 120)
        try {
            setDraft("运行中的提问")
            ui.waitUntil(5_000) {
                ui.onAllNodes(hasTestTag("chat-send") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            node("chat-send").performClick()
            awaitTextSendResult(activity)
            ui.waitUntil(5_000) { session.entered.count == 0L }
            val owner = checkNotNull(fixture.controller.active)
            setDraft("保留追加草稿")
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
