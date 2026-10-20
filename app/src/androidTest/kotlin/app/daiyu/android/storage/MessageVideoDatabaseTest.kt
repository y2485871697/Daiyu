package app.daiyu.android.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.RunCheckpoint
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.model.generation.*
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.testContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Real application helper/transactions; references are metadata fixtures, not playable assets. */
@Suppress("DEPRECATION")
class MessageVideoDatabaseTest : InstrumentationTestCase() {
    private lateinit var scoped: Context
    private lateinit var database: ConversationDatabase
    private var runtime: SQLiteRuntimeStore? = null

    override fun setUp() {
        super.setUp()
        scoped = testContext(instrumentation.targetContext, "message-video-${UUID.randomUUID()}-")
        database = ConversationDatabase(scoped)
    }

    override fun tearDown() {
        try {
            runtime?.close()
            database.close()
            scoped.deleteDatabase("conversations.db")
            File(scoped.getDatabasePath("conversations.db").canonicalPath + ".runtime-owner").delete()
        } finally { super.tearDown() }
    }

    private fun reopen() {
        check(runtime == null)
        database.close()
        database = ConversationDatabase(scoped)
    }

    private fun pair(id: ConversationId): Pair<Message, Message> {
        val key = UUID.randomUUID().toString()
        val user = Message(MessageId("user-$key"), id, RunId("run-$key"), Message.USER_ROLE,
            listOf(MessageBlock.Text("question", "video question")), RunStatus.COMPLETED)
        return user to user.copy(id = MessageId("assistant-$key"), role = Message.ASSISTANT_ROLE,
            blocks = emptyList(), status = RunStatus.RUNNING)
    }

    private fun input(message: Message): MessageVideoInput {
        val owner = MessageVideoOwner(message.conversationId.value, message.id.value)
        return MessageVideoInput(owner, listOf(MessageVideoReference(owner, UUID.randomUUID().toString(),
            "video/mp4", 91_234L, "0123456789abcdef".repeat(4), 12_345L, 1920, 1080)))
    }

    // MessageVideoInput intentionally has identity equality; compare every persisted value instead.
    private fun projection(inputs: List<MessageVideoInput>) = inputs.map { it.owner to it.references }

    private fun rejects(action: () -> Unit) {
        try { action() } catch (_: Exception) { return }
        // AssertionFailedError is not caught above: a non-rejection really fails this test.
        fail("Expected fail-closed database rejection")
    }

    private fun table(name: String): List<List<String?>> = database.readableDatabase
        .rawQuery("SELECT * FROM $name ORDER BY rowid", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_BLOB -> "blob:" + cursor.getBlob(column).joinToString("") {
                            "%02x".format(it.toInt() and 255)
                        }
                        else -> cursor.getType(column).toString() + ":" + cursor.getString(column)
                    }
                })
            }
        }

    private fun snapshot() = listOf("conversations", "composer", "messages", "message_video_inputs",
        "message_video_witnesses", "runtime_checkpoints", "generation_attempts", "generation_outputs").associateWith(::table)

    private fun assertUnchanged(before: Map<String, List<List<String?>>>) {
        assertEquals(before, snapshot())
        reopen()
        assertEquals(before, snapshot())
    }

    private fun payload(message: Message): String = database.readableDatabase.rawQuery(
        "SELECT payload FROM message_video_inputs WHERE message_id=?", arrayOf(message.id.value)).use {
        assertTrue(it.moveToFirst()); it.getString(0)
    }

    private fun replacePayload(message: Message, encoded: String) {
        assertEquals(1, database.writableDatabase.update("message_video_inputs", ContentValues().apply {
            put("payload", encoded)
        }, "message_id=?", arrayOf(message.id.value)))
    }

    private fun physicalHistory(id: ConversationId): List<Message> {
        var before = Long.MAX_VALUE
        var result = emptyList<Message>()
        while (true) {
            val page = database.messages(id, before = before, limit = 17)
            if (page.isEmpty()) return result
            result = page.map { it.second } + result
            before = page.first().first
        }
    }

    fun testVersionElevenUpgradePreservesGenerationRowsMessagesAndDrafts() {
        val id = database.create(assistantId = database.selectedAssistantId()).id
        val (user, plain) = pair(id)
        val call = ToolInvocation(ToolCallId("generation-call"), "generate_media", "{}")
        val assistant = plain.copy(blocks = listOf(MessageBlock.Tool(call)))
        database.send(user, assistant)
        database.draft(id, "retained conversation draft")
        database.draft("retained composer draft")
        val binding = GenerationAttemptBinding(id.value, assistant.id.value, assistant.runId.value,
            call.id.value, call.name)
        val attempt = GenerationAttempt(binding.identity("item1"), "a".repeat(64), GenerationProtocol.OPENAI_IMAGES_JSON)
        val attempts = SQLiteGenerationAttemptStore({ database.writableDatabase }, binding)
        assertSame(GenerationReservation.Started, attempts.reserve(attempt))
        attempts.finish(attempt, Submission.Unknown(GenerationFailure.TRANSPORT))
        // A constrained metadata row tests migration retention, not original-file authorization.
        val hash = "b".repeat(64)
        database.writableDatabase.insertOrThrow("generation_outputs", null, ContentValues().apply {
            put("operation_id", attempt.identity.operationId); put("item_id", attempt.identity.itemId)
            put("output_index", 0); put("fingerprint", attempt.fingerprint); put("protocol", attempt.protocol.name)
            put("account_scope", "offline-test"); put("conversation_id", id.value)
            put("assistant_id", assistant.id.value); put("run_id", assistant.runId.value)
            put("tool_call_id", call.id.value); put("tool_name", call.name)
            put("receipt", "genasset-v1-${"c".repeat(64)}-$hash"); put("kind", "IMAGE")
            put("mime_type", "image/png"); put("byte_count", 123L); put("sha256", hash)
            put("width", 1); put("height", 1); put("duration_ms", 0L)
        })
        val retained = snapshot().filterKeys { it != "message_video_inputs" }
        // v12's only addition over v11 is this table. Reconstruct that predecessor on disk;
        // do not invoke onUpgrade manually or substitute a fake helper for the upgrade.
        database.writableDatabase.execSQL("DROP TABLE message_video_inputs")
        database.writableDatabase.version = 11
        assertEquals(11, database.readableDatabase.version)
        reopen()
        assertEquals(22, database.readableDatabase.version)
        assertEquals(retained, snapshot().filterKeys { it != "message_video_inputs" })
        assertTrue(table("message_video_inputs").isEmpty())
        database.readableDatabase.rawQuery("PRAGMA foreign_key_list(message_video_inputs)", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("messages", it.getString(2)); assertEquals("message_id", it.getString(3))
            assertEquals("id", it.getString(4)); assertEquals("CASCADE", it.getString(6))
        }
        val existing = attempts.reserve(attempt) as GenerationReservation.Existing
        assertEquals(attempt.fingerprint, existing.fingerprint)
        assertEquals(Submission.Unknown(GenerationFailure.TRANSPORT), existing.submission)
        val before = snapshot()
        reopen()
        assertEquals(before, snapshot())
        assertEquals(listOf(user, assistant), physicalHistory(id))
        assertEquals("retained composer draft", database.draft())
        assertEquals("retained conversation draft", database.conversations().single().draft)
    }

    fun testSendVideoCommitsBothMessagesMetadataAndDraftReceiptAcrossReopen() {
        for (newConversation in listOf(false, true)) {
            val id = if (newConversation) ConversationId(UUID.randomUUID().toString()) else database.create(database.selectedAssistantId()).id
            val (user, assistant) = pair(id)
            val videos = listOf(input(user))
            database.draft("composer survives existing send")
            if (!newConversation) database.draft(id, "conversation draft")
            val accepted = database.send(user, assistant, newConversation, videoInputs = videos)
            assertEquals(id, accepted.id); assertEquals("", accepted.draft)
            assertEquals(if (newConversation) "" else "composer survives existing send", database.draft())
            val wire = payload(user)
            reopen()
            assertEquals(22, database.readableDatabase.version)
            assertEquals(listOf(user, assistant), physicalHistory(id))
            assertEquals(projection(videos), projection(database.videoInputs(physicalHistory(id))))
            assertEquals(wire, payload(user))
            assertEquals(projection(videos), projection(MessageVideoInputCodec.decode(user, wire)))
        }
    }

    fun testWrongInputAndReferenceOwnersRollBackMessagesAndBothDrafts() {
        val retained = database.create(database.selectedAssistantId()).id
        database.draft(retained, "keep conversation draft"); database.draft("keep composer")
        val before = snapshot()
        for (newConversation in listOf(false, true)) {
            val id = if (newConversation) ConversationId(UUID.randomUUID().toString()) else retained
            val (user, assistant) = pair(id)
            val good = input(user)
            val wrongConversation = MessageVideoOwner("foreign-conversation", user.id.value)
            val wrongMessage = MessageVideoOwner(id.value, assistant.id.value)
            val invalid = listOf(
                MessageVideoInput(wrongConversation, good.references.map { it.copy(owner = wrongConversation) }),
                MessageVideoInput(wrongMessage, good.references.map { it.copy(owner = wrongMessage) }),
                MessageVideoInput(good.owner, good.references.map { it.copy(owner = wrongMessage) }))
            for (bad in invalid) {
                rejects { database.send(user, assistant, newConversation, videoInputs = listOf(bad)) }
                assertUnchanged(before)
            }
        }
    }

    fun testIgnoredAbortedAndTamperedSidecarInsertRollsBackEntireSend() {
        val retained = database.create(database.selectedAssistantId()).id
        database.draft(retained, "keep conversation draft"); database.draft("keep composer")
        val before = snapshot()
        for (newConversation in listOf(false, true)) {
            for (fault in listOf("IGNORE", "ABORT", "invalid-payload", "changed-reference")) {
                val id = if (newConversation) ConversationId(UUID.randomUUID().toString()) else retained
                val (user, assistant) = pair(id)
                val videos = listOf(input(user))
                val sql = when (fault) {
                    "IGNORE" -> "BEFORE INSERT ON message_video_inputs BEGIN SELECT RAISE(IGNORE); END"
                    "ABORT" -> "BEFORE INSERT ON message_video_inputs BEGIN SELECT RAISE(ABORT,'test failure'); END"
                    else -> {
                        val changed = if (fault == "invalid-payload") "{}" else {
                            val value = JSONObject(MessageVideoInputCodec.encode(user, videos))
                            value.getJSONArray("inputs").getJSONObject(0).getJSONArray("references")
                                .getJSONObject(0).put("bytes", 91_235L)
                            value.toString()
                        }
                        "AFTER INSERT ON message_video_inputs BEGIN UPDATE message_video_inputs SET payload='" +
                            changed.replace("'", "''") + "' WHERE message_id=NEW.message_id; END"
                    }
                }
                database.writableDatabase.execSQL("CREATE TRIGGER video_insert_fault $sql")
                try {
                    rejects { database.send(user, assistant, newConversation, videoInputs = videos) }
                    assertUnchanged(before)
                } finally { database.writableDatabase.execSQL("DROP TRIGGER video_insert_fault") }
            }
        }
    }

    fun testCorruptRetainedPayloadFailsClosedOnMessageAndVideoReadsAfterReopen() {
        val id = database.create(database.selectedAssistantId()).id
        val (user, assistant) = pair(id)
        database.send(user, assistant, videoInputs = listOf(input(user)))
        val original = payload(user)
        replacePayload(user, "{}") // Legal SQL TEXT, invalid sidecar; reads must not discard or repair it.
        val corrupted = snapshot()
        rejects { database.messages(id) }
        rejects { database.videoInputs(listOf(user, assistant)) }
        assertUnchanged(corrupted)
        rejects { database.messages(id) }
        rejects { database.videoInputs(listOf(user, assistant)) }
        assertEquals("{}", payload(user))
        replacePayload(user, original)
        assertEquals(listOf(user, assistant), physicalHistory(id))
    }

    private data class SegmentFixture(val user: Message, val assistant: Message,
        val userVideo: MessageVideoInput, val segmentVideo: MessageVideoInput)

    private fun segmentFixture(): SegmentFixture {
        val id = database.create(database.selectedAssistantId()).id
        val (user, plain) = pair(id)
        val supplement = user.copy(id = MessageId("segment-${UUID.randomUUID()}"))
        val assistant = plain.copy(historySegments = listOf(
            MessageHistorySegment(plain.id, Message.ASSISTANT_ROLE, emptyList()),
            MessageHistorySegment(supplement.id, Message.USER_ROLE, supplement.blocks)))
        val userVideo = input(user)
        val segmentVideo = input(supplement)
        database.send(user, assistant, videoInputs = listOf(userVideo))
        // send attaches only to its physical user. Seed an expanded-user sidecar explicitly to
        // exercise the existing save/runtime compatibility guard on a physical assistant owner.
        // Construct a pre-v13 expanded-user fixture; runtime paths never replace witnesses.
        app.daiyu.android.context.DatabaseTransactions { database.writableDatabase }.run { sql ->
            sql.execSQL("DELETE FROM message_video_witnesses WHERE message_id=?", arrayOf(assistant.id.value))
            SQLiteMessageVideoStore.insert(sql, assistant, listOf(segmentVideo))
        }
        return SegmentFixture(user, assistant, userVideo, segmentVideo)
    }

    private fun withAnswer(message: Message) = message.copy(
        blocks = listOf(MessageBlock.Text("answer", "accepted answer")),
        historySegments = message.historySegments.map {
            if (it.role == Message.ASSISTANT_ROLE) it.copy(blocks = listOf(MessageBlock.Text("answer", "accepted answer"))) else it
        })

    fun testSavePreservesUserAndExpandedUserMetadataAndRejectsOrphaningRewrite() {
        val fixture = segmentFixture()
        val before = snapshot()
        rejects { database.save(fixture.assistant.copy(historySegments = emptyList())) }
        assertUnchanged(before)
        val completed = withAnswer(fixture.assistant).copy(status = RunStatus.COMPLETED)
        database.save(completed)
        val wire = payload(fixture.assistant)
        reopen()
        assertEquals(listOf(fixture.user, completed), physicalHistory(fixture.user.conversationId))
        assertEquals(projection(listOf(fixture.userVideo, fixture.segmentVideo)),
            projection(database.videoInputs(physicalHistory(fixture.user.conversationId))))
        assertEquals(wire, payload(completed))
    }

    fun testRuntimeCheckpointPreservesMetadataAndRollsBackIncompatibleMessageAndCheckpoint() {
        val store = SQLiteRuntimeStore.acquire(database).also { runtime = it; it.recoverPending() }
        val fixture = segmentFixture()
        val first = RunCheckpoint(fixture.assistant, listOf(fixture.user), ExecutionPhase.BOUNDARY,
            owner = "video-runtime-owner", revision = 1)
        store.commitCheckpoint(first)
        val accepted = first.copy(message = withAnswer(first.message), revision = 2)
        store.commitCheckpoint(accepted)
        assertEquals(accepted, store.readCommittedCheckpoint(accepted))
        assertEquals(projection(listOf(fixture.userVideo, fixture.segmentVideo)),
            projection(database.videoInputs(physicalHistory(fixture.user.conversationId))))
        val before = snapshot()
        val incompatible = accepted.copy(message = accepted.message.copy(historySegments = emptyList()), revision = 3)
        rejects { store.commitCheckpoint(incompatible) }
        assertEquals(before, snapshot())
        assertEquals(accepted, store.readCommittedCheckpoint(incompatible))
        store.close(); runtime = null
        reopen()
        assertEquals(before, snapshot()) // Reopen without recovery cannot rewrite the checkpoint.
        val recoveredStore = SQLiteRuntimeStore.acquire(database).also { runtime = it }
        val recovered = recoveredStore.reconcileCheckpoint(first)
        assertEquals(RunStatus.INTERRUPTED, recovered.message.status)
        assertEquals(accepted.message.historySegments, recovered.message.historySegments)
        assertEquals(projection(listOf(fixture.userVideo, fixture.segmentVideo)),
            projection(database.videoInputs(physicalHistory(fixture.user.conversationId))))
        assertEquals(recovered, recoveredStore.reconcileCheckpoint(first))
        recoveredStore.close(); runtime = null
        reopen()
        assertEquals(recovered.message, physicalHistory(fixture.user.conversationId).last())
        assertEquals(projection(listOf(fixture.userVideo, fixture.segmentVideo)),
            projection(database.videoInputs(physicalHistory(fixture.user.conversationId))))
    }

    fun testDeleteIgnoreAndAbortRollBackThenSuccessfulDeleteRemovesOnlyOwnedMetadata() {
        val id = database.create(database.selectedAssistantId()).id
        val (user, assistant) = pair(id)
        database.send(user, assistant, videoInputs = listOf(input(user)))
        database.draft(id, "retained draft"); database.draft("retained composer")
        val other = database.create(database.selectedAssistantId()).id
        val (otherUser, otherAssistant) = pair(other)
        val otherVideos = listOf(input(otherUser))
        database.send(otherUser, otherAssistant, videoInputs = otherVideos)
        val before = snapshot()
        for (fault in listOf("IGNORE", "ABORT")) {
            val raise = if (fault == "IGNORE") "RAISE(IGNORE)" else "RAISE(ABORT,'test failure')"
            database.writableDatabase.execSQL("CREATE TRIGGER video_delete_fault BEFORE DELETE ON message_video_inputs " +
                "BEGIN SELECT $raise; END")
            try {
                rejects { database.delete(id) }
                assertUnchanged(before)
            } finally { database.writableDatabase.execSQL("DROP TRIGGER video_delete_fault") }
        }
        database.delete(id)
        reopen()
        assertTrue(database.messages(id).isEmpty())
        assertTrue(database.conversations().none { it.id == id })
        assertEquals(1, table("message_video_inputs").size)
        assertEquals(projection(otherVideos), projection(database.videoInputs(physicalHistory(other))))
        assertEquals("retained composer", database.draft())
        database.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
    }

    fun testTextBackupRejectsVideoGloballyAndPerConversationButAllowsTextOnlySelection() {
        val textId = database.create(database.selectedAssistantId()).id
        val (textUser, textAssistant) = pair(textId)
        database.send(textUser, textAssistant)
        database.save(textAssistant.copy(status = RunStatus.COMPLETED))
        val videoId = database.create(database.selectedAssistantId()).id
        val (videoUser, videoAssistant) = pair(videoId)
        database.send(videoUser, videoAssistant, videoInputs = listOf(input(videoUser)))
        database.draft(videoId, "keep video draft"); database.draft("keep composer")
        val before = snapshot()
        rejects { database.exportBackup() }
        rejects { database.exportConversationBackup(videoId) }
        val textBackup = database.exportConversationBackup(textId)
        val body = JSONObject(JSONObject(textBackup).getString("payload"))
        assertEquals(1, body.getJSONArray("conversations").length())
        assertEquals(2, body.getJSONArray("messages").length())
        assertEquals(textId.value, body.getJSONArray("conversations").getJSONObject(0).getString("id"))
        assertUnchanged(before)
        rejects { database.exportBackup() }
        rejects { database.exportConversationBackup(videoId) }
        assertEquals(textBackup, database.exportConversationBackup(textId))
        database.delete(videoId)
        assertTrue(database.exportBackup().isNotEmpty())
    }

    fun testAllPhysicalHistoryFindsVideoOlderThanUiPageAndRejectsStaleSnapshots() {
        val id = database.create(database.selectedAssistantId()).id
        val expected = mutableListOf<Message>()
        var firstVideo: MessageVideoInput? = null
        repeat(31) { index ->
            val (user, assistant) = pair(id)
            val videos = if (index == 0) listOf(input(user).also { firstVideo = it }) else emptyList()
            database.send(user, assistant, videoInputs = videos)
            val completed = assistant.copy(status = RunStatus.COMPLETED)
            // Leave the last owner running so a real accepted save can invalidate our snapshot.
            if (index < 30) database.save(completed)
            expected += user; expected += if (index < 30) completed else assistant
        }
        reopen()
        val uiPage = database.messages(id).map { it.second }
        val physical = physicalHistory(id)
        assertEquals(50, uiPage.size); assertEquals(62, physical.size)
        assertEquals(expected, physical)
        assertFalse(uiPage.any { it.id == physical.first().id })
        // The reader cannot infer completeness. The caller must pass all physical pages.
        assertTrue(database.videoInputs(uiPage).isEmpty())
        assertEquals(projection(listOf(checkNotNull(firstVideo))), projection(database.videoInputs(physical)))
        val changed = physical.last().copy(blocks = listOf(MessageBlock.Text("late", "new accepted text")),
            status = RunStatus.COMPLETED)
        database.save(changed)
        val beforeRead = snapshot()
        rejects { database.videoInputs(physical) }
        assertUnchanged(beforeRead)
        rejects { database.videoInputs(physical) }
        val fresh = physicalHistory(id)
        assertEquals(changed, fresh.last())
        assertEquals(projection(listOf(checkNotNull(firstVideo))), projection(database.videoInputs(fresh)))
    }
}
