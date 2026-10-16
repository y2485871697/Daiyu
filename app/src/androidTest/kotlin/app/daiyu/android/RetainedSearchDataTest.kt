package app.daiyu.android

import app.daiyu.android.context.ContextService
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.ui.RetainedConversationData
import app.daiyu.android.ui.screens.search.conversationSearchResults

/** Real SQLite reader contract; no synthetic empty reader or capped-global scope filtering. */
@Suppress("DEPRECATION")
class RetainedSearchDataTest : android.test.AndroidTestCase() {
    private lateinit var scoped: android.content.Context
    private lateinit var database: ConversationDatabase
    private lateinit var data: RetainedConversationData

    override fun setUp() {
        super.setUp()
        scoped = testContext(context, "search-reader-${java.util.UUID.randomUUID()}-")
        database = ConversationDatabase(scoped)
        data = RetainedConversationData(database, ContextService(scoped, database))
    }

    override fun tearDown() {
        try { database.close(); scoped.deleteDatabase("conversations.db") } finally { super.tearDown() }
    }

    private fun turn(
        conversation: Conversation, key: String, text: String,
        userTransform: (Message) -> Message = { it },
        answerTransform: (Message) -> Message = { it },
    ): Pair<Message, Message> {
        val user = userTransform(Message(MessageId("user-$key"), conversation.id, RunId(key), "user",
            listOf(MessageBlock.Text("text-$key", text)), RunStatus.COMPLETED))
        val answer = answerTransform(user.copy(id = MessageId("answer-$key"), role = "assistant",
            status = RunStatus.RUNNING, protocolItems = emptyList()))
        database.send(user, answer)
        database.save(answer.copy(status = RunStatus.COMPLETED))
        return user to answer.copy(status = RunStatus.COMPLETED)
    }

    fun testCurrentScopeIsReadBeforeGlobalCapAndStillFindsOldMessages() {
        val noisy = database.create("default")
        repeat(101) { turn(noisy, "noise-$it", "共同关键词 $it") }
        database.organize(noisy.id, "", true) // Deterministically fills the global 200 first.
        val target = database.create("default")
        val older = turn(target, "target", "共同关键词：较早正文").first
        repeat(30) { turn(target, "later-$it", "后续不匹配正文 $it") }
        database.rename(target.id, "中文目标会话")

        val global = data.search("共同关键词")
        assertEquals(RetainedConversationData.SEARCH_RESULT_LIMIT, global.size)
        assertTrue(global.all { it.conversationId == noisy.id.value })
        val current = data.searchCurrentConversation("  共同关键词  ", target.id.value)
        assertEquals(2, current.size)
        assertTrue(current.all { it.conversationId == target.id.value && it.conversationTitle == "中文目标会话" })
        assertEquals(older.id.value, current.first().messageId)
        assertEquals(older.id.value, current.first().ownerMessageId)
        assertTrue(current.first().snippet.contains("较早正文"))
        assertEquals(200, data.searchCurrentConversation("共同关键词", noisy.id.value).size)
    }

    fun testSupplementHitRetainsPersistedOwnerForWindowNavigation() {
        val conversation = database.create("default")
        val supplementId = MessageId("supplement-without-own-row")
        val (_, answer) = turn(conversation, "segmented", "普通正文", answerTransform = { owner ->
            owner.copy(historySegments = listOf(
                MessageHistorySegment(owner.id, "assistant", owner.blocks),
                MessageHistorySegment(supplementId, "user", listOf(MessageBlock.Text("supplement-text", "补充检索词"))),
            ))
        })
        val hit = data.searchCurrentConversation("补充检索词", conversation.id.value).single()
        assertEquals(supplementId.value, hit.messageId)
        assertEquals(answer.id.value, hit.ownerMessageId)
        assertEquals("用户", hit.roleLabel)
        database.readableDatabase.rawQuery("SELECT seq FROM messages WHERE conversation_id=? AND id=?",
            arrayOf(hit.conversationId, hit.ownerMessageId)).use { assertTrue(it.moveToFirst()) }
    }

    fun testChineseTitlesAndPreviewsDoNotSearchSerializedProtocolOrBlockMetadata() {
        val conversation = database.create("default")
        turn(conversation, "metadata", "真正中文正文", userTransform = { user ->
            user.copy(
                blocks = listOf(MessageBlock.Text("图片标识-secret-image-id", "真正中文正文")),
                protocolItems = listOf("{\"type\":\"input_image\",\"mime\":\"image/jpeg\",\"path\":\"/private/图片元数据\",\"sha256\":\"secret-image-hash\"}"),
            )
        })
        database.rename(conversation.id, "中文标题")
        database.draft(conversation.id, "仅草稿关键词")
        val summaries = data.summaries(null)
        assertEquals("中文标题", summaries.single().title)
        assertEquals("真正中文正文", summaries.single().preview)
        assertEquals(1, conversationSearchResults(summaries, "中文标题", null).size)
        assertTrue(conversationSearchResults(summaries, "仅草稿关键词", null).isEmpty())
        for (metadata in listOf("图片元数据", "secret-image", "image/jpeg", "input_image")) {
            assertTrue(data.search(metadata).isEmpty())
            assertTrue(data.searchCurrentConversation(metadata, conversation.id.value).isEmpty())
            assertTrue(conversationSearchResults(summaries, metadata, null).isEmpty())
        }
        assertEquals("中文标题", data.search("真正中文正文").first().conversationTitle)
    }

    fun testEmptyNoMatchInvalidAndDeletedScopesAreDistinctFromReadFailure() {
        val conversation = database.create("default")
        turn(conversation, "errors", "正文")
        assertTrue(data.search("  ").isEmpty())
        assertTrue(data.searchCurrentConversation("缺失关键词", conversation.id.value).isEmpty())
        try { data.search("长".repeat(1001)); fail("long query accepted") } catch (_: IllegalArgumentException) { }
        try { data.searchCurrentConversation("正文", "missing-id"); fail("deleted scope accepted") }
        catch (_: IllegalStateException) { }
        // Corrupt authoritative data must propagate to SearchReadError, never return an empty list.
        database.writableDatabase.execSQL("UPDATE messages SET blocks='not-json' WHERE conversation_id=?", arrayOf(conversation.id.value))
        try { data.searchCurrentConversation("正文", conversation.id.value); fail("read failure hidden") }
        catch (_: org.json.JSONException) { }
    }
}
