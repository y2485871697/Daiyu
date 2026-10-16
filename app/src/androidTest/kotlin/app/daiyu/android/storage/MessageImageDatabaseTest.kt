package app.daiyu.android.storage

import android.content.Context
import app.daiyu.android.domain.*
import app.daiyu.android.testContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Metadata/owner contract only. Publishing and resolving real asset bytes is tested elsewhere. */
@Suppress("DEPRECATION")
class MessageImageDatabaseTest : android.test.AndroidTestCase() {
    private lateinit var scoped: Context
    private lateinit var database: ConversationDatabase

    override fun setUp() {
        super.setUp()
        scoped = testContext(context, "message-image-db-${UUID.randomUUID()}-")
        database = ConversationDatabase(scoped)
    }

    override fun tearDown() {
        try {
            database.close()
            scoped.deleteDatabase("conversations.db")
        } finally { super.tearDown() }
    }

    private fun reopen() {
        database.close()
        database = ConversationDatabase(scoped)
    }

    private fun reset() {
        database.close()
        scoped.deleteDatabase("conversations.db")
        database = ConversationDatabase(scoped)
    }

    private fun pair(id: ConversationId): Pair<Message, Message> {
        val key = UUID.randomUUID().toString()
        val user = Message(MessageId("user-$key"), id, RunId("run-$key"), Message.USER_ROLE,
            listOf(MessageBlock.Text("text-$key", "visible search phrase")), RunStatus.COMPLETED)
        return user to user.copy(id = MessageId("assistant-$key"), role = Message.ASSISTANT_ROLE,
            blocks = emptyList(), status = RunStatus.RUNNING)
    }

    private fun image(message: Message, bytes: Int = 100): MessageBlock.Image = MessageBlock.Image(
        MessageImageReference(MessageImageOwner(message.conversationId.value, message.id.value),
            UUID.randomUUID().toString(), "image/png", bytes, "ab".repeat(32), 10, 10))

    private fun rejected(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("Invalid image metadata/ownership must be rejected")
    }

    private fun assertUnchanged(before: String) {
        assertEquals(before, database.exportBackup())
        reopen()
        assertEquals(before, database.exportBackup())
    }

    fun testOrdinaryImageTextAndToolRoundTripReopenAndSearch() {
        val id = database.create("default").id
        val (plain, assistant) = pair(id)
        val reference = image(plain)
        val user = plain.copy(blocks = plain.blocks + reference)
        database.send(user, assistant)
        val completed = assistant.copy(status = RunStatus.COMPLETED, blocks = listOf(
            MessageBlock.Text("answer", "answer text"),
            MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "lookup", "tool argument needle"), "tool result needle")))
        database.save(completed)
        reopen()
        assertEquals(10, database.readableDatabase.version)
        assertEquals(listOf(user, completed), database.messages(id).map { it.second })
        for (query in listOf("visible search phrase", "tool argument needle", "tool result needle")) {
            assertEquals(listOf(id), database.conversations(query).map { it.id })
        }
        for (query in listOf(reference.reference.id, reference.reference.sha256, "image/png")) {
            assertTrue(database.conversations(query).isEmpty())
        }
        val wire = database.readableDatabase.rawQuery("SELECT blocks FROM messages WHERE id=?", arrayOf(user.id.value)).use {
            assertTrue(it.moveToFirst()); JSONArray(it.getString(0)).getJSONObject(1)
        }
        assertEquals(reference, MessageImageReferenceCodec.decodeBlock(wire))
        val backup = database.exportBackup()
        reset()
        assertEquals(1 to 2, database.importBackup(backup))
        reopen()
        assertEquals(listOf(user, completed), database.messages(id).map { it.second })
        assertEquals(backup, database.exportBackup())
    }

    fun testInvalidOwnersDuplicatesAndLimitsRollBackWholeSend() {
        val retained = database.create("default").id
        database.draft(retained, "retained draft")
        database.draft("retained composer")
        val before = database.exportBackup()
        for (newConversation in listOf(false, true)) {
            val id = if (newConversation) ConversationId(UUID.randomUUID().toString()) else retained
            val (plain, assistant) = pair(id)
            val good = image(plain)
            val wrongConversation = good.copy(reference = good.reference.copy(
                owner = good.reference.owner.copy(conversationId = "other-conversation")))
            val wrongMessage = good.copy(reference = good.reference.copy(
                owner = good.reference.owner.copy(messageId = assistant.id.value)))
            val invalid = listOf(
                listOf(wrongConversation), listOf(wrongMessage), listOf(good, good),
                List(MessageImageReference.MAX_IMAGES + 1) { image(plain) },
                List(4) { image(plain, MessageImageReference.MAX_IMAGE_BYTES) })
            for (blocks in invalid) {
                rejected { database.send(plain.copy(blocks = blocks), assistant, newConversation) }
                assertUnchanged(before)
            }
            // Invalid assistant metadata is discovered after the user insert; it must undo that too.
            rejected { database.send(plain, assistant.copy(blocks = listOf(good)), newConversation) }
            assertUnchanged(before)
        }
    }

    fun testMaximumImageCountAndAggregateBytesAreAcceptedAsMetadata() {
        val id = database.create("default").id
        val (plain, assistant) = pair(id)
        val user = plain.copy(blocks = List(MessageImageReference.MAX_IMAGES) { image(plain, 8 * 1024 * 1024) })
        database.send(user, assistant)
        reopen()
        assertEquals(user, database.messages(id).first().second)
    }

    fun testHistoryImageUsesSegmentMessageAndEnclosingConversationOwner() {
        val id = database.create("default").id
        val (plain, assistant) = pair(id)
        database.send(plain, assistant)
        val supplementId = MessageId("segment-${UUID.randomUUID()}")
        val supplement = plain.copy(id = supplementId)
        val block = image(supplement)
        val completed = assistant.copy(status = RunStatus.COMPLETED, historySegments = listOf(
            MessageHistorySegment(assistant.id, Message.ASSISTANT_ROLE, emptyList()),
            MessageHistorySegment(supplementId, Message.USER_ROLE, listOf(block))))
        val before = database.exportBackup()
        val wrongOwner = block.copy(reference = block.reference.copy(
            owner = block.reference.owner.copy(conversationId = "other-conversation")))
        rejected { database.save(completed.copy(historySegments = completed.historySegments.map {
            if (it.messageId == supplementId) it.copy(blocks = listOf(wrongOwner)) else it
        })) }
        assertUnchanged(before)
        database.save(completed)
        reopen()
        assertEquals(completed, database.messages(id).last().second)
    }

    private fun editBackup(backup: String, edit: (JSONObject) -> Unit): String {
        val envelope = JSONObject(backup)
        val payload = JSONObject(envelope.getString("payload"))
        edit(payload.getJSONArray("messages").getJSONObject(0))
        val encoded = payload.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return envelope.put("payload", encoded).put("sha256", digest).toString()
    }

    fun testInvalidImageImportRejectsBeforeAnyRowsOrDraftAreChanged() {
        val source = database.create("default").id
        val (plain, assistant) = pair(source)
        database.send(plain.copy(blocks = listOf(image(plain))), assistant)
        database.save(assistant.copy(status = RunStatus.COMPLETED))
        val backup = database.exportBackup()
        reset()
        val retained = database.create("default").id
        database.draft(retained, "retained conversation draft")
        database.draft("retained composer")
        val before = database.exportBackup()
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("blocks").getJSONObject(0).getJSONObject("reference").put("conversation", "wrong-conversation"); Unit },
            { it.getJSONArray("blocks").getJSONObject(0).getJSONObject("reference").put("message", "wrong-message"); Unit },
            { it.getJSONArray("blocks").getJSONObject(0).getJSONObject("reference").put("uri", "content://forbidden"); Unit },
            { it.getJSONArray("blocks").getJSONObject(0).put("extra", true); Unit },
            { it.getJSONArray("blocks").getJSONObject(0).getJSONObject("reference").put("bytes", 1.5); Unit },
            { it.getJSONArray("blocks").getJSONObject(0).getJSONObject("reference").put("version", 2); Unit },
            { it.getJSONArray("blocks").getJSONObject(0).getJSONObject("reference").put("id", "../bad"); Unit },
            { val blocks = it.getJSONArray("blocks"); blocks.put(JSONObject(blocks.getJSONObject(0).toString())); Unit },
            { message ->
                val prototype = message.getJSONArray("blocks").getJSONObject(0)
                val blocks = JSONArray()
                repeat(MessageImageReference.MAX_IMAGES + 1) {
                    val block = JSONObject(prototype.toString())
                    block.getJSONObject("reference").put("id", UUID.randomUUID().toString())
                    blocks.put(block)
                }
                message.put("blocks", blocks); Unit
            },
            { message ->
                val prototype = message.getJSONArray("blocks").getJSONObject(0)
                val blocks = JSONArray()
                repeat(4) {
                    val block = JSONObject(prototype.toString())
                    block.getJSONObject("reference").put("id", UUID.randomUUID().toString())
                        .put("bytes", MessageImageReference.MAX_IMAGE_BYTES)
                    blocks.put(block)
                }
                message.put("blocks", blocks); Unit
            })
        for (edit in edits) {
            val invalid = editBackup(backup, edit)
            rejected { database.preflightBackup(invalid) }
            rejected { database.importBackup(invalid) }
            assertUnchanged(before)
        }
    }
}
