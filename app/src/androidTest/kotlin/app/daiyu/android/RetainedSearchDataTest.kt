package app.daiyu.android

import app.daiyu.android.context.ContextService
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.ui.RetainedConversationData
import app.daiyu.android.ui.screens.search.conversationSearchResults

/** 检查 SQLite 搜索读取及作用域过滤，覆盖超出全局结果上限的数据。 */
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
        complete: Boolean = true,
    ): Pair<Message, Message> {
        val user = userTransform(Message(MessageId("user-$key"), conversation.id, RunId(key), "user",
            listOf(MessageBlock.Text("text-$key", text)), RunStatus.COMPLETED))
        val answer = answerTransform(user.copy(id = MessageId("answer-$key"), role = "assistant",
            status = RunStatus.RUNNING, protocolItems = emptyList()))
        database.send(user, answer)
        val stored = if (complete) answer.copy(status = RunStatus.COMPLETED) else answer
        if (complete) database.save(stored)
        return user to stored
    }

    fun testSearchMatchesOnlyBodyKeepsRolesAndExcludesToolReasoningAndProtocolAfterReopen() {
        val conversation = database.create("default")
        val (user, answer) = turn(conversation, "body-only", "用户正文关键词", answerTransform = { owner ->
            val blocks = listOf(MessageBlock.Text("before", "助手正文前段"),
                MessageBlock.Tool(ToolInvocation(ToolCallId("hidden-call"), "hidden_tool_name",
                    "screen_read_mode=visual_fallback"), "hidden_tool_result"),
                MessageBlock.Text("after", "助手正文后段"))
            owner.copy(blocks = blocks, historySegments = listOf(MessageHistorySegment(owner.id,
                Message.ASSISTANT_ROLE, blocks,
                protocolItems = listOf("{\"type\":\"hidden_protocol_marker\"}"),
                reportedReasoning = listOf(ReportedReasoning("thought", "hidden_reasoning_marker")))))
        })
        database.close(); database = ConversationDatabase(scoped)
        data = RetainedConversationData(database, ContextService(scoped, database))
        val before = database.messages(conversation.id)
        for (hidden in listOf("hidden_tool_name", "screen_read_mode", "visual_fallback",
            "hidden_tool_result", "hidden_protocol_marker", "hidden_reasoning_marker")) {
            assertTrue(hidden, data.search(hidden).isEmpty())
            assertTrue(hidden, data.searchCurrentConversation(hidden, conversation.id.value).isEmpty())
        }
        val userHit = data.search("用户正文关键词").single()
        assertEquals(user.id.value, userHit.messageId)
        assertEquals("用户", userHit.roleLabel)
        val answerHit = data.searchCurrentConversation("助手正文前段", conversation.id.value).single()
        assertEquals(answer.id.value, answerHit.messageId)
        assertEquals("助手", answerHit.roleLabel)
        assertEquals("助手正文前段\n助手正文后段", answerHit.snippet)
        assertEquals(before, database.messages(conversation.id))
        val literal = "screen_read_mode=visual_fallback"
        val (literalUser, literalAnswer) = turn(conversation, "body-literal", literal)
        val literalHits = data.search(literal)
        assertEquals(setOf(literalUser.id.value, literalAnswer.id.value), literalHits.map { it.messageId }.toSet())
        assertEquals(setOf("用户", "助手"), literalHits.map { it.roleLabel }.toSet())
        assertTrue(literalHits.all { it.snippet == literal })
    }

    fun testLongBodySnippetStaysAroundHitWithoutSplittingEmojiOrChangingTarget() {
        val conversation = database.create("default")
        val prefix = "无关开头\n".repeat(200) + "😀".repeat(40)
        val suffix = "😀".repeat(100)
        val (user, answer) = turn(conversation, "hit-context", prefix + "关键词" + suffix)
        val expected = "😀".repeat(12) + "关键词" + "😀".repeat(80)
        for (hits in listOf(data.search("关键词"),
            data.searchCurrentConversation("关键词", conversation.id.value))) {
            assertEquals(setOf(user.id.value, answer.id.value), hits.map { it.messageId }.toSet())
            assertTrue(hits.all { it.snippet == expected && it.conversationId == conversation.id.value })
            assertTrue(hits.all { it.ownerMessageId == it.messageId && it.segmentIndex == 0 })
            assertTrue(hits.none { it.snippet.first().isLowSurrogate() || it.snippet.last().isHighSurrogate() })
        }
        assertEquals(prefix + "关键词" + suffix,
            (database.messages(conversation.id).first { it.id == user.id }.blocks.single() as MessageBlock.Text).text)
    }

    fun testToolOnlySegmentsDoNotOccupyBodySearchPagesOrChangeSegmentIdentity() {
        val conversation = database.create("default")
        val (_, answer) = turn(conversation, "body-pages", "ordinary user", answerTransform = { owner ->
            owner.copy(historySegments = listOf(
                MessageHistorySegment(owner.id, "assistant", listOf(
                    MessageBlock.Text("first", "body needle first"))),
                MessageHistorySegment(owner.id, "assistant", listOf(MessageBlock.Tool(
                    ToolInvocation(ToolCallId("only-tool"), "body needle tool", "body needle arguments"),
                    "body needle result"))),
                MessageHistorySegment(owner.id, "assistant", listOf(
                    MessageBlock.Text("last", "body needle last"))),
            ))
        })
        val first = data.searchPage("body needle", conversation.id.value, pageSize = 1)
        assertEquals(listOf(0), first.results.map { it.segmentIndex })
        val next = checkNotNull(first.nextCursor)
        val second = data.searchPage("body needle", conversation.id.value, next, pageSize = 1)
        assertEquals(listOf(2), second.results.map { it.segmentIndex })
        assertEquals("body needle last", second.results.single().snippet)
        assertEquals(answer.id.value, second.results.single().ownerMessageId)
        assertNull(second.nextCursor)
    }

    fun testAllScopeExcludesTemporaryVoiceConversationsThatCannotBeOpenedFromHistory() {
        val persisted = database.create("default")
        val temporary = database.create("default", temporaryVoice = true)
        turn(persisted, "visible-scope", "正文范围共同词")
        turn(temporary, "hidden-voice", "正文范围共同词")
        val page = data.searchPage("正文范围共同词", pageSize = 1)
        assertTrue(page.results.all { it.conversationId == persisted.id.value })
        assertEquals(listOf(persisted.id.value), checkNotNull(page.nextCursor).conversations.map { it.id })
        val next = data.searchPage("正文范围共同词", cursor = page.nextCursor, pageSize = 1)
        assertTrue(next.results.all { it.conversationId == persisted.id.value })
        assertNull(next.nextCursor)
        assertEquals(2, data.searchCurrentConversation("正文范围共同词", persisted.id.value).size)
        assertTrue(database.conversations().none { it.id == temporary.id })
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

    fun testLaterSameOwnerAssistantSegmentsRemainSeparateExactHits() {
        val conversation = database.create("default")
        val (_, answer) = turn(conversation, "exact-segment", "ordinary user", answerTransform = { owner ->
            owner.copy(historySegments = listOf(
                MessageHistorySegment(owner.id, "assistant", listOf(MessageBlock.Text("first", "shared needle before"))),
                MessageHistorySegment(MessageId("middle-supplement"), "user", listOf(MessageBlock.Text("middle", "middle question"))),
                MessageHistorySegment(owner.id, "assistant", listOf(MessageBlock.Text("later", "shared needle after"))),
            ))
        })
        val hits = data.searchCurrentConversation("shared needle", conversation.id.value)
        assertEquals(2, hits.size)
        assertEquals(listOf(0, 1), hits.map { it.segmentIndex })
        assertTrue(hits.all { it.messageId == answer.id.value && it.ownerMessageId == answer.id.value })
        val turns = app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapper.map(
            ContextService(scoped, database).displayHistory(conversation.id))
        val hit = hits.last()
        val request = app.daiyu.android.ui.screens.chat.conversation.ChatScrollRequest(
            1, hit.messageId, false, hit.segmentIndex)
        val exact = turns.indexOfFirst { it.id == request.messageId && it.segmentIndex == request.segmentIndex }
        assertTrue(exact >= 0)
        assertEquals("shared needle after", (turns[exact] as app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi.Assistant).finalText)
        assertTrue(data.summaries(null).single().hasCompletionMarker) // Search is not a read acknowledgement.
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

    private fun segmented(conversation: Conversation, key: String, count: Int, complete: Boolean = true): Message =
        turn(conversation, key, "ordinary user", answerTransform = { owner ->
            owner.copy(historySegments = List(count) { index ->
                MessageHistorySegment(owner.id, "assistant", listOf(MessageBlock.Text("part-$index", "paged needle $index")))
            })
        }, complete = complete).second

    fun test201HitsContinueWithoutRepeatingSameOwnerSegments() = assertSegmentPages(201, listOf(200, 1))

    fun test401HitsContinueAcrossThreePagesWithoutLossOrDuplicates() = assertSegmentPages(401, listOf(200, 200, 1))

    private fun assertSegmentPages(count: Int, expectedPageSizes: List<Int>) {
        val conversation = database.create("default")
        val owner = segmented(conversation, "segments-$count", count)
        val hits = mutableListOf<app.daiyu.android.ui.model.MessageSearchHit>()
        val sizes = mutableListOf<Int>()
        var cursor: app.daiyu.android.ui.model.MessageSearchCursor? = null
        do {
            val page = data.searchPage("paged needle", conversation.id.value, cursor)
            sizes.add(page.results.size)
            hits.addAll(page.results)
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(expectedPageSizes, sizes)
        assertEquals((0 until count).toList(), hits.map { it.segmentIndex })
        assertTrue(hits.all { it.messageId == owner.id.value && it.ownerMessageId == owner.id.value })
        assertEquals(count, hits.map { Triple(it.conversationId, it.messageId, it.segmentIndex) }.toSet().size)
    }

    fun testSupplementExpansionAndRepeatedOwnerIdentityStraddlePageBoundary() {
        val conversation = database.create("default")
        val (_, owner) = turn(conversation, "cross-boundary", "paged needle standalone", answerTransform = { answer ->
            answer.copy(historySegments = listOf(
                MessageHistorySegment(answer.id, "assistant", listOf(MessageBlock.Text("before", "paged needle before"))),
                // Refers to the separately persisted user row. Whole-history expansion must
                // suppress that row, rather than yielding it twice after physical-row paging.
                MessageHistorySegment(MessageId("user-cross-boundary"), "user",
                    listOf(MessageBlock.Text("text-cross-boundary", "paged needle standalone"))),
                MessageHistorySegment(answer.id, "assistant", listOf(MessageBlock.Text("after", "paged needle after"))),
            ))
        })
        val first = data.searchPage("paged needle", conversation.id.value, pageSize = 2)
        val second = data.searchPage("paged needle", conversation.id.value, first.nextCursor, pageSize = 2)
        assertEquals(listOf(owner.id.value, "user-cross-boundary"), first.results.map { it.messageId })
        assertEquals(1, second.results.size)
        assertEquals(owner.id.value, second.results.single().ownerMessageId)
        assertEquals(1, second.results.single().segmentIndex)
        assertNull(second.nextCursor)
    }

    fun testContinuationCrossesPhysicalOwnersAndFrozenConversationOrder() {
        val firstConversation = database.create("default")
        val first = turn(firstConversation, "first-page", "paged needle first")
        val secondConversation = database.create("default")
        val second = turn(secondConversation, "second-page", "paged needle second")
        database.organize(firstConversation.id, "", true)
        val firstPage = data.searchPage("paged needle", pageSize = 3)
        val secondPage = data.searchPage("paged needle", cursor = requireNotNull(firstPage.nextCursor), pageSize = 3)
        assertEquals(listOf(first.first.id.value, first.second.id.value, second.first.id.value),
            firstPage.results.map { it.messageId })
        assertEquals(listOf(second.second.id.value), secondPage.results.map { it.messageId })
        assertNull(secondPage.nextCursor)
    }

    private fun invalidated(read: () -> Unit) {
        try { read(); fail("stale or differently scoped cursor accepted") }
        catch (_: app.daiyu.android.ui.model.SearchCursorInvalidatedException) { }
    }

    fun testCursorFreezesQueryScopeOrderAndUnreadHistoryRevision() {
        val firstConversation = database.create("default")
        segmented(firstConversation, "revision", 201)
        database.organize(firstConversation.id, "", true)
        val secondConversation = database.create("default")
        val unread = turn(secondConversation, "unread", "not a match", complete = false).second
        val first = data.searchPage("paged needle")
        val cursor = requireNotNull(first.nextCursor)
        invalidated { data.searchPage("other", cursor = cursor) }
        invalidated { data.searchPage("paged needle", firstConversation.id.value, cursor) }
        // Even a change after the currently scanned/result-bearing conversation invalidates.
        database.save(unread.copy(blocks = listOf(MessageBlock.Text("changed", "still not a match"))))
        invalidated { data.searchPage("paged needle", cursor = cursor) }
        val nextCursor = requireNotNull(data.searchPage("paged needle").nextCursor)
        database.organize(firstConversation.id, "", false)
        database.organize(secondConversation.id, "", true)
        invalidated { data.searchPage("paged needle", cursor = nextCursor) }
    }

    fun testScopedCursorIgnoresUnrelatedHistoryButRejectsOwnerChangesAndDeletion() {
        val conversation = database.create("default")
        val owner = segmented(conversation, "scoped-revision", 201, complete = false)
        val cursor = requireNotNull(data.searchPage("paged needle", conversation.id.value).nextCursor)
        turn(database.create("default"), "unrelated", "paged needle unrelated")
        assertEquals(1, data.searchPage("paged needle", conversation.id.value, cursor).results.size)
        database.save(owner.copy(historySegments = owner.historySegments.dropLast(1)))
        invalidated { data.searchPage("paged needle", conversation.id.value, cursor) }
        val deletionCursor = requireNotNull(data.searchPage("paged needle", conversation.id.value, pageSize = 1).nextCursor)
        database.delete(conversation.id)
        invalidated { data.searchPage("paged needle", conversation.id.value, deletionCursor) }
    }

    fun testCancelledSearchAndChunkReaderReleaseTransactionAndConnection() {
        val conversation = database.create("default")
        val owner = turn(conversation, "cancelled", "needle " + "中文".repeat(40000)).first
        val signal = android.os.CancellationSignal().also { it.cancel() }
        try { data.searchPage("needle", cancellationSignal = signal); fail("cancelled search returned") }
        catch (_: android.os.OperationCanceledException) { }
        try {
            app.daiyu.android.storage.SQLiteText.read(database.readableDatabase, "messages", "blocks", "id",
                owner.id.value, cancellationSignal = signal)
            fail("cancelled chunk read returned")
        } catch (_: android.os.OperationCanceledException) { }
        assertFalse(database.readableDatabase.inTransaction())
        assertEquals(2, data.searchPage("needle", conversation.id.value).results.size)
        // Multi-chunk UTF-8 must still be exact, not a 64KiB truncation.
        assertEquals(owner.blocks, ContextService(scoped, database).displayHistory(conversation.id).first().blocks)
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
