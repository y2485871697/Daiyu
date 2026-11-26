package app.daiyu.android.storage

import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [26, 30])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageDisplayStoreTest {
    private lateinit var database: ConversationDatabase
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        database = ConversationDatabase(context)
    }
    @After fun cleanup() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase("conversations.db")
    }
    private fun seed(): Pair<Message, Message> {
        val owner = database.create("default").id
        val user = Message(MessageId("user"), owner, RunId("run"), Message.USER_ROLE,
            listOf(MessageBlock.Text("q", "question")), RunStatus.COMPLETED)
        val answer = user.copy(id = MessageId("answer"), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("body", "first")), status = RunStatus.RUNNING)
        database.send(user, answer)
        database.displayMessages(owner)
        return user to answer
    }
    private fun count() = database.readableDatabase.rawQuery("SELECT count(*) FROM message_display", null).use {
        check(it.moveToFirst()); it.getInt(0)
    }
    @Test fun exactAssistantHistoryBlocksUseOneValidatedPayloadAcrossReopen() {
        val (user, answer) = seed()
        val first = MessageBlock.Text("body", "汉".repeat(400_000))
        val last = MessageBlock.Text("end", "b".repeat(70_000))
        val owner = answer.copy(status = RunStatus.STOPPED, blocks = listOf(first, last), historySegments = listOf(
            MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, listOf(first)),
            MessageHistorySegment(MessageId("embedded-user"), Message.USER_ROLE, listOf(MessageBlock.Text("q2", "continue"))),
            MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, listOf(last)),
            MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, emptyList(), reportedReasoning = listOf(
                ReportedReasoning("tail-reasoning", "reasoning-only tail", elapsedMillis = 11, blockOffset = 0)))))
        database.save(owner)
        val canonical = canonicalDisplaySource(answer.id)
        val display = database.displayMessages(user.conversationId)
        val payload = displayPayload(answer.id)
        assertEquals(4, payload.getInt("version"))
        assertEquals("assistant-history", payload.getString("blocks"))
        assertTrue(payload.toString().length < 500_000)
        assertEquals(ChatTurnMapper.map(database.messages(user.conversationId).map { it.second }), ChatTurnMapper.mapDisplay(display))
        assertEquals(owner.blocks, display.rows.last().second.coordinateBlocks)
        assertEquals(display, database.displayMessages(user.conversationId))
        assertEquals(canonical, canonicalDisplaySource(answer.id))
        database.close()
        database = ConversationDatabase(RuntimeEnvironment.getApplication())
        assertEquals(display, database.displayMessages(user.conversationId))
        assertEquals(canonical, canonicalDisplaySource(answer.id))
    }

    @Test fun compatibleVersionThreeDuplicateCompactsWithoutChangingCanonicalBytes() {
        val (user, answer) = seed()
        database.save(answer.copy(status = RunStatus.STOPPED, historySegments = listOf(
            MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, answer.blocks))))
        val expected = database.displayMessages(user.conversationId)
        val canonical = canonicalDisplaySource(answer.id)
        val old = displayPayload(answer.id)
        val blocks = old.getJSONArray("history").getJSONObject(0).getJSONArray("blocks")
        old.put("version", 3).put("blocks", blocks)
        val oldText = old.toString()
        val db = database.writableDatabase
        db.execSQL("UPDATE message_display SET payload=? WHERE message_id=?", arrayOf(oldText, answer.id.value))
        db.beginTransaction()
        try {
            assertEquals(expected, MessageDisplayStore.read(db, user.conversationId, Long.MAX_VALUE, 50))
            assertEquals("assistant-history", displayPayload(answer.id).getString("blocks"))
            assertEquals(expected, MessageDisplayStore.read(db, user.conversationId, Long.MAX_VALUE, 50))
        } finally { db.endTransaction() }
        assertEquals(oldText, displayPayload(answer.id).toString())
        assertEquals(canonical, canonicalDisplaySource(answer.id))
        assertEquals(expected, database.displayMessages(user.conversationId))
        assertEquals(4, displayPayload(answer.id).getInt("version"))
        assertEquals("assistant-history", displayPayload(answer.id).getString("blocks"))
        assertEquals(canonical, canonicalDisplaySource(answer.id))
    }

    @Test fun differentTopLevelBlocksAreNotReplacedByHistory() {
        val (user, answer) = seed()
        val owner = answer.copy(status = RunStatus.STOPPED, historySegments = listOf(
            MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE,
                listOf(MessageBlock.Text("body", "different history body")))))
        database.save(owner)
        val canonical = canonicalDisplaySource(answer.id)
        val display = database.displayMessages(user.conversationId)
        assertEquals(owner.blocks, display.rows.last().second.coordinateBlocks)
        assertEquals("first", displayPayload(answer.id).getJSONArray("blocks").getJSONObject(0).getString("text"))
        assertEquals(display, database.displayMessages(user.conversationId))
        assertEquals(canonical, canonicalDisplaySource(answer.id))
    }

    @Test fun invalidAssistantHistoryReferencesRebuildFromCanonical() {
        val (user, answer) = seed()
        database.save(answer.copy(status = RunStatus.STOPPED, historySegments = listOf(
            MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, answer.blocks))))
        val expected = database.displayMessages(user.conversationId)
        val canonical = canonicalDisplaySource(answer.id)
        val valid = displayPayload(answer.id).toString()
        val damaged = listOf(
            org.json.JSONObject(valid).put("blocks", "unknown-history"),
            org.json.JSONObject(valid).put("version", 3),
            org.json.JSONObject(valid).put("history", org.json.JSONArray()),
            org.json.JSONObject(valid).put("projections", org.json.JSONArray().put(org.json.JSONObject())))
        damaged.forEach { value ->
            database.writableDatabase.execSQL("UPDATE message_display SET payload=? WHERE message_id=?",
                arrayOf(value.toString(), answer.id.value))
            assertEquals(expected, database.displayMessages(user.conversationId))
            assertEquals("assistant-history", displayPayload(answer.id).getString("blocks"))
            assertEquals(canonical, canonicalDisplaySource(answer.id))
        }
    }

    private fun displayPayload(id: MessageId) = org.json.JSONObject(requireNotNull(
        SQLiteText.read(database.readableDatabase, "message_display", "payload", "message_id", id.value)))

    private fun canonicalDisplaySource(id: MessageId) = listOf("blocks", "protocol", "history_segments").map {
        SQLiteText.read(database.readableDatabase, "messages", it, "id", id.value)
    }

    @Test fun writesAndReopenKeepProtocolFreeDisplayAndExactTurnKeys() {
        val (user, initial) = seed()
        val supplement = user.copy(id = MessageId("supplement"), blocks = listOf(MessageBlock.Text("extra", "extra")))
        val owner = initial.copy(status = RunStatus.STOPPED,
            historySegments = listOf(MessageHistorySegment(initial.id, Message.ASSISTANT_ROLE, initial.blocks,
                listOf("x".repeat(999_999))), MessageHistorySegment(supplement.id, Message.USER_ROLE, supplement.blocks),
                MessageHistorySegment(initial.id, Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("end", "last")))))
        database.save(owner)
        val expected = ChatTurnMapper.map(database.messages(user.conversationId).map { it.second })
        val display = database.displayMessages(user.conversationId)
        assertEquals(expected, ChatTurnMapper.mapDisplay(display))
        assertEquals(2, count())
        database.readableDatabase.rawQuery("SELECT payload FROM message_display WHERE message_id='answer'", null).use {
            assertTrue(it.moveToFirst()); assertFalse(it.getString(0).contains("x".repeat(100)))
        }
        database.close()
        database = ConversationDatabase(RuntimeEnvironment.getApplication())
        assertEquals(display, database.displayMessages(user.conversationId))
    }
    @Test fun legacyMissSkipsProtocolAndNeverChangesCanonicalBytes() {
        val (user, answer) = seed()
        val db = database.writableDatabase
        db.execSQL("UPDATE messages SET protocol='not-json',history_segments=? WHERE id=?", arrayOf(
            """[{"message_id":"answer","role":"assistant","blocks":[{"type":"text","item":"body","text":"legacy"}],"protocol":["${"z".repeat(2_100_000)}"]}]""", answer.id.value))
        assertEquals(1, count())
        val raw = SQLiteText.read(db, "messages", "history_segments", "id", answer.id.value)
        val display = database.displayMessages(user.conversationId)
        assertEquals("legacy", (display.rows.last().second.segments.single().blocks.single() as MessageBlock.Text).text)
        assertEquals(raw, SQLiteText.read(db, "messages", "history_segments", "id", answer.id.value))
        assertTrue(runCatching { database.messages(user.conversationId) }.isFailure)
        assertEquals(2, count())
        assertEquals(display, database.displayMessages(user.conversationId))
    }
    @Test fun directUpdatesRollbackDeleteAndIdentityReuseInvalidateSameTransaction() {
        val (user, answer) = seed()
        val db = database.writableDatabase
        db.beginTransaction()
        try { db.execSQL("UPDATE messages SET status='STOPPED' WHERE id=?", arrayOf(answer.id.value)); assertEquals(1, count()) }
        finally { db.endTransaction() }
        assertEquals(2, count())
        db.execSQL("UPDATE messages SET status='STOPPED',protocol='not-json' WHERE id=?", arrayOf(answer.id.value))
        assertEquals(1, count())
        assertEquals(RunStatus.STOPPED, database.displayMessages(user.conversationId).rows.last().second.status)
        db.delete("messages", "id=?", arrayOf(answer.id.value))
        assertEquals(1, count())
        assertEquals(listOf(user.id), database.displayMessages(user.conversationId).rows.map { it.second.id })
    }
    @Test fun schema31UpgradeInstallsEmptyCacheWithoutRewritingHistory() {
        val (user, answer) = seed()
        val db = database.writableDatabase
        val original = SQLiteText.read(db, "messages", "blocks", "id", answer.id.value)
        listOf("insert", "update", "delete").forEach { db.execSQL("DROP TRIGGER message_display_$it") }
        db.execSQL("DROP TABLE message_display")
        db.version = 31
        database.close()
        database = ConversationDatabase(RuntimeEnvironment.getApplication())
        assertEquals(0, count())
        assertEquals(original, SQLiteText.read(database.readableDatabase, "messages", "blocks", "id", answer.id.value))
        assertEquals(2, database.displayMessages(user.conversationId).rows.size)
        assertEquals(35, database.readableDatabase.version)
    }
    @Test fun damagedAndWrongOwnerCacheRebuildFromCanonicalOnce() {
        val (user, answer) = seed()
        val db = database.writableDatabase
        db.execSQL("UPDATE message_display SET payload='broken' WHERE message_id=?", arrayOf(answer.id.value))
        assertEquals("first", (database.displayMessages(user.conversationId).rows.last().second.blocks.single() as MessageBlock.Text).text)
        db.execSQL("UPDATE message_display SET payload=(SELECT payload FROM message_display WHERE message_id=?) WHERE message_id=?",
            arrayOf(user.id.value, answer.id.value))
        assertEquals("first", (database.displayMessages(user.conversationId).rows.last().second.blocks.single() as MessageBlock.Text).text)
    }

    @Test fun legalExternalTriggerRewriteCannotBeReplacedByCallerCache() {
        val (user, answer) = seed()
        val db = database.writableDatabase
        db.execSQL("""CREATE TRIGGER rewrite_display_test AFTER UPDATE ON messages WHEN NEW.id='answer' BEGIN
            UPDATE messages SET blocks='[{"type":"text","item":"body","text":"trigger"}]' WHERE id=NEW.id;
            END""")
        database.save(answer.copy(status = RunStatus.STOPPED))
        assertEquals("trigger", (database.displayMessages(user.conversationId).rows.last().second.blocks.single() as MessageBlock.Text).text)
        assertEquals("trigger", (database.messages(user.conversationId).last().second.blocks.single() as MessageBlock.Text).text)
    }

    @Test fun corruptSupplementCacheRebuildsWindowWithoutChangingCanonicalHistory() {
        val (user, answer) = seed()
        val saved = answer.copy(status = RunStatus.STOPPED, historySegments = listOf(
            MessageHistorySegment(user.id, Message.USER_ROLE, user.blocks),
            MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, answer.blocks)))
        database.save(saved)
        val expected = database.displayMessages(user.conversationId)
        val db = database.writableDatabase
        val value = org.json.JSONObject(requireNotNull(SQLiteText.read(db, "message_display", "payload", "message_id", answer.id.value)))
        value.getJSONArray("history").getJSONObject(0).getJSONArray("blocks").getJSONObject(0).put("text", "cache corruption")
        db.execSQL("UPDATE message_display SET payload=? WHERE message_id=?", arrayOf(value.toString(), answer.id.value))
        assertEquals(expected, database.displayMessages(user.conversationId))
        assertEquals(saved, database.messages(user.conversationId).last().second)
    }

    private fun seedNextRun(completed: Boolean = true): Message {
        val (user, answer) = seed()
        database.save(answer.copy(status = RunStatus.COMPLETED))
        val next = user.copy(id = MessageId("next-user"), runId = RunId("next-run"))
        val nextAnswer = answer.copy(id = MessageId("next-answer"), runId = next.runId)
        database.send(next, nextAnswer)
        val stored = if (completed) nextAnswer.copy(status = RunStatus.COMPLETED) else nextAnswer
        if (completed) database.save(stored)
        return stored
    }

    private fun insertSupplement(id: String) {
        database.writableDatabase.execSQL(
            "INSERT INTO messages(id,conversation_id,run_id,role,blocks,status,protocol,history_segments) " +
                "SELECT ?,conversation_id,run_id,role,blocks,status,protocol,history_segments FROM messages WHERE id='next-user'",
            arrayOf(id))
    }

    @Test fun latestRunDefersOlderPayloadDecodeAndNormalWindowRemainsFull() {
        val answer = seedNextRun()
        val preview = database.displayMessages(answer.conversationId, latestRunOnly = true)
        assertEquals(listOf("next-user", "next-answer"), preview.rows.map { it.second.id.value })
        assertEquals(2, preview.deferredRows)
        assertEquals(4, database.displayMessages(answer.conversationId).rows.size)
        assertEquals(0, database.displayMessages(answer.conversationId).deferredRows)
        database.writableDatabase.execSQL("UPDATE messages SET blocks='broken' WHERE id='answer'")
        assertEquals(preview, database.displayMessages(answer.conversationId, latestRunOnly = true))
        assertTrue(runCatching { database.displayMessages(answer.conversationId) }.isFailure)
    }

    @Test fun latestRunKeepsAssistantWithLaterSupplementsAndRebuildKeepsScope() {
        val answer = seedNextRun(completed = false)
        val user = database.messages(answer.conversationId).first { it.second.id.value == "next-user" }.second
        val saved = answer.copy(status = RunStatus.STOPPED, historySegments = listOf(
            MessageHistorySegment(user.id, Message.USER_ROLE, user.blocks),
            MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, answer.blocks)))
        database.save(saved)
        repeat(3) { insertSupplement("supplement-$it") }
        val preview = database.displayMessages(answer.conversationId, latestRunOnly = true)
        assertEquals(5, preview.rows.size)
        assertEquals(2, preview.deferredRows)
        assertTrue(preview.rows.any { it.second.id == answer.id })
        val db = database.writableDatabase
        val value = org.json.JSONObject(requireNotNull(SQLiteText.read(db, "message_display", "payload", "message_id", answer.id.value)))
        value.getJSONArray("history").getJSONObject(0).getJSONArray("blocks").getJSONObject(0).put("text", "cache corruption")
        db.execSQL("UPDATE message_display SET payload=? WHERE message_id=?", arrayOf(value.toString(), answer.id.value))
        assertEquals(preview, database.displayMessages(answer.conversationId, latestRunOnly = true))
        assertEquals(saved, database.messages(answer.conversationId).first { it.second.id == answer.id }.second)
        assertEquals(7, database.displayMessages(answer.conversationId).rows.size)
    }

    @Test fun unansweredTailKeepsLatestAnsweredRunWithoutDecodingOlderPayloads() {
        val answer = seedNextRun()
        repeat(2) { insertSupplement("unanswered-$it") }
        val db = database.writableDatabase
        repeat(2) { db.execSQL("UPDATE messages SET run_id=? WHERE id=?",
            arrayOf("unanswered-run-$it", "unanswered-$it")) }
        val canonical = database.messages(answer.conversationId)
        val preview = database.displayMessages(answer.conversationId, latestRunOnly = true)
        assertEquals(listOf("next-user", "next-answer", "unanswered-0", "unanswered-1"),
            preview.rows.map { it.second.id.value })
        assertEquals(2, preview.deferredRows)
        assertEquals(canonical, database.messages(answer.conversationId))
        assertEquals(6, database.displayMessages(answer.conversationId).rows.size)
        db.execSQL("UPDATE messages SET blocks='broken' WHERE id='answer'")
        assertEquals(preview, database.displayMessages(answer.conversationId, latestRunOnly = true))
        db.execSQL("UPDATE message_display SET payload='broken' WHERE message_id='next-answer'")
        assertEquals(preview, database.displayMessages(answer.conversationId, latestRunOnly = true))
        assertTrue(runCatching { database.displayMessages(answer.conversationId) }.isFailure)
        assertEquals("broken", SQLiteText.read(db, "messages", "blocks", "id", "answer"))
    }

    @Test fun boundedUserOnlyTailKeepsNormalWindowFallback() {
        val answer = seedNextRun()
        repeat(51) { insertSupplement("supplement-$it") }
        val preview = database.displayMessages(answer.conversationId, latestRunOnly = true)
        assertEquals(50, preview.rows.size)
        assertEquals(0, preview.deferredRows)
        assertEquals(database.displayMessages(answer.conversationId), preview)
        val empty = database.create("default")
        assertTrue(database.displayMessages(empty.id, latestRunOnly = true).rows.isEmpty())
    }

    @Test fun exactLegacySearchDisplayRebuildsOldPayloadAndPreservesAuthoritativeBytes() {
        val (user, answer) = seed()
        val plain = "## Heading\n\n**bold** [link](https://example.org)"
        val item = org.json.JSONObject().put("type", "message").put("id", "body").put("role", "assistant")
            .put("status", "completed").put("content", org.json.JSONArray().put(org.json.JSONObject()
                .put("type", "output_text").put("text", plain).put("annotations", org.json.JSONArray().put(
                    org.json.JSONObject().put("type", "url_citation").put("start_index", 0).put("end_index", 2)
                        .put("url", "https://source.test").put("title", "Source")))))
        val protocol = listOf(item.toString())
        val old = app.daiyu.android.model.ResponsesSearchProtocol.legacyDisplayText(item, plain)
        val saved = answer.copy(status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text("body", old)),
            protocolItems = protocol,
            historySegments = listOf(MessageHistorySegment(answer.id, "assistant", listOf(MessageBlock.Text("body", old)), protocol)))
        database.save(saved)
        val db = database.writableDatabase
        fun bytes() = listOf("blocks", "protocol", "history_segments").map { SQLiteText.read(db, "messages", it, "id", answer.id.value) }
        val original = bytes()
        val first = database.displayMessages(user.conversationId)
        val shown = first.rows.last().second
        val expected = app.daiyu.android.model.ResponsesSearchProtocol.displayText(item, plain)
        assertEquals(expected, (shown.blocks.single() as MessageBlock.Text).text)
        assertEquals(expected, (shown.segments.single().blocks.single() as MessageBlock.Text).text)
        val payload = org.json.JSONObject(requireNotNull(SQLiteText.read(db, "message_display", "payload", "message_id", answer.id.value)))
        payload.put("version", 1)
        payload.getJSONArray("blocks").getJSONObject(0).put("text", old)
        db.execSQL("UPDATE message_display SET payload=? WHERE message_id=?", arrayOf(payload.toString(), answer.id.value))
        assertEquals(first, database.displayMessages(user.conversationId))
        assertEquals(original, bytes())
        val authority = database.messages(user.conversationId).map { it.second }
        assertEquals(saved, authority.last())
        assertEquals(ChatTurnMapper.mapDisplay(first), ChatTurnMapper.map(authority))
        assertEquals(original, bytes())
    }

}
