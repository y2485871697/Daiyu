package app.daiyu.android.context

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.domain.MessageHistorySegment
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.ToolCallId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.storage.MessageHistoryCodec
import app.daiyu.android.storage.MessageImageReferenceCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Exercises the real archive and shared reference/history codecs without Android storage. */
class ArchiveImageCodecTest {
    @Test
    fun mixedImagesRoundTripInOrderWithFullMetadataAndStoppedState() {
        val row = row()
        val bytes = ArchiveCodec.encode(listOf(row))
        val wire = json(bytes).getJSONObject(0)
        val blocks = wire.getJSONArray("blocks")
        assertEquals(listOf("text", "image", "tool", "image", "text"),
            (0 until blocks.length()).map { blocks.getJSONObject(it).getString("type") })
        row.blocks.filterIsInstance<MessageBlock.Image>().forEachIndexed { index, image ->
            val encoded = blocks.getJSONObject(if (index == 0) 1 else 3)
            assertEquals(MessageImageReferenceCodec.encodeBlock(image).toString(), encoded.toString())
            assertEquals(image, MessageImageReferenceCodec.decodeBlock(encoded))
        }
        assertFalse(wire.has("history_segments"))
        val restored = ArchiveCodec.decode(bytes, ArchiveCodec.digest(bytes))
        assertEquals(listOf(row), restored)
        assertEquals(RunStatus.STOPPED, restored.single().status)
        assertEquals("cancelled by user", restored.single().error)
        assertArrayEquals(bytes, ArchiveCodec.encode(restored))
        assertEquals(row.blocks, ArchiveCodec.decodeBlocks(ArchiveCodec.blocks(row.blocks)))
    }

    @Test
    fun historyImagesUseSegmentMessageIdentityAndSurviveExpansion() {
        val owner = historyOwner()
        val userSegment = owner.historySegments.first()
        val supplement = Message(userSegment.messageId, owner.conversationId, owner.runId,
            userSegment.role, userSegment.blocks, RunStatus.COMPLETED,
            protocolItems = userSegment.protocolItems)
        val rows = listOf(owner, supplement)
        val bytes = ArchiveCodec.encode(rows)
        val segments = json(bytes).getJSONObject(0).getJSONArray("history_segments")
        assertEquals(owner.historySegments, MessageHistoryCodec.decode(segments))
        val restored = ArchiveCodec.decode(bytes)
        assertEquals(rows, restored)
        assertArrayEquals(bytes, ArchiveCodec.encode(restored))
        val expanded = MessageHistory.expand(restored)
        assertEquals(owner.historySegments.map { it.messageId }, expanded.map { it.id })
        assertEquals(owner.historySegments.map { it.blocks }, expanded.map { it.blocks })
        assertEquals(owner.historySegments.map { it.protocolItems }, expanded.map { it.protocolItems })
        assertEquals(supplement, expanded.first())
        assertEquals(RunStatus.STOPPED, expanded.last().status)
        expanded.forEach { message ->
            message.blocks.filterIsInstance<MessageBlock.Image>().forEach {
                assertEquals(MessageImageOwner(message.conversationId.value, message.id.value), it.reference.owner)
            }
        }
    }

    @Test
    fun legacyTextToolBytesAndPendingToolRemainUnchanged() {
        val legacy = row().copy(blocks = listOf(
            MessageBlock.Text("before", "旧正文 🌸"),
            MessageBlock.Tool(ToolInvocation(ToolCallId("pending"), "lookup", "{}")),
            MessageBlock.Tool(ToolInvocation(ToolCallId("complete"), "lookup", "{\"q\":1}"), "完整结果")))
        // Frozen pre-image encoder, intentionally independent of ArchiveCodec.blocks.
        val blocks = JSONArray().put(JSONObject().put("type", "text").put("item", "before").put("text", "旧正文 🌸"))
            .put(JSONObject().put("type", "tool").put("id", "pending").put("name", "lookup")
                .put("arguments", "{}").put("result", JSONObject.NULL))
            .put(JSONObject().put("type", "tool").put("id", "complete").put("name", "lookup")
                .put("arguments", "{\"q\":1}").put("result", "完整结果"))
        val bytes = JSONArray().put(JSONObject().put("id", legacy.id.value)
            .put("conversation", legacy.conversationId.value).put("run", legacy.runId.value)
            .put("role", legacy.role).put("status", legacy.status.name).put("error", legacy.error)
            .put("protocol", JSONArray(legacy.protocolItems)).put("blocks", blocks))
            .toString().toByteArray(Charsets.UTF_8)
        assertArrayEquals(bytes, ArchiveCodec.encode(listOf(legacy)))
        assertEquals(listOf(legacy), ArchiveCodec.decode(bytes))
    }

    @Test
    fun encodeRejectsWrongEnclosingRowOrSegmentOwner() {
        val row = row()
        listOf(MessageImageOwner("other-conversation", row.id.value),
            MessageImageOwner(row.conversationId.value, "other-message")).forEach { wrong ->
            val image = (row.blocks[1] as MessageBlock.Image).reference.copy(owner = wrong)
            assertRejected("row owner $wrong") {
                ArchiveCodec.encode(listOf(row.copy(blocks = listOf(MessageBlock.Image(image)))))
            }
        }
        val owner = historyOwner()
        val segment = owner.historySegments.first()
        listOf(MessageImageOwner("other-conversation", segment.messageId.value),
            MessageImageOwner(owner.conversationId.value, owner.id.value)).forEach { wrong ->
            val image = (segment.blocks[0] as MessageBlock.Image).reference.copy(owner = wrong)
            val badSegment = segment.copy(blocks = listOf(MessageBlock.Image(image)))
            assertRejected("segment owner $wrong") {
                ArchiveCodec.encode(listOf(owner.copy(historySegments = listOf(badSegment))))
            }
        }
    }

    @Test
    fun decodeRejectsWrongOwnerEvenWhenReferenceAndStandaloneSegmentAreValid() {
        listOf("conversation", "message").forEach { field ->
            val rows = json(ArchiveCodec.encode(listOf(row())))
            imageBlock(rows.getJSONObject(0), false).getJSONObject("reference").put(field, "other-owner")
            MessageImageReferenceCodec.decodeBlock(imageBlock(rows.getJSONObject(0), false))
            assertRejected("row $field") { ArchiveCodec.decode(bytes(rows)) }
        }
        val rows = json(ArchiveCodec.encode(listOf(historyOwner())))
        val owner = rows.getJSONObject(0)
        imageBlock(owner, true).getJSONObject("reference").put("conversation", "other-conversation")
        // The segment alone cannot know its enclosing conversation; the archive must check it.
        MessageHistoryCodec.decode(owner.getJSONArray("history_segments"))
        assertRejected("segment enclosing conversation") { ArchiveCodec.decode(bytes(rows)) }

        val wrongMessage = json(ArchiveCodec.encode(listOf(historyOwner())))
        imageBlock(wrongMessage.getJSONObject(0), true).getJSONObject("reference").put("message", "other-message")
        assertRejected("segment enclosing message") { ArchiveCodec.decode(bytes(wrongMessage)) }
    }

    @Test
    fun malformedImageBlocksAndReferencesAreRejectedForRowsAndHistory() {
        val mutations: List<Pair<String, (JSONObject) -> Unit>> = listOf(
            "unknown block URI" to { it: JSONObject -> it.put("uri", "content://not-an-asset") },
            "missing reference" to { it: JSONObject -> it.remove("reference") },
            "reference is text" to { it: JSONObject -> it.put("reference", "file:///not-an-asset") },
            "unknown reference field" to { it: JSONObject -> it.getJSONObject("reference").put("uri", "content://not-an-asset") },
            "missing digest" to { it: JSONObject -> it.getJSONObject("reference").remove("sha256") },
            "unsupported version" to { it: JSONObject -> it.getJSONObject("reference").put("version", 2) },
            "version is text" to { it: JSONObject -> it.getJSONObject("reference").put("version", "1") },
            "bytes is text" to { it: JSONObject -> it.getJSONObject("reference").put("bytes", "12") },
            "fractional width" to { it: JSONObject -> it.getJSONObject("reference").put("width", 1.5) },
            "bad asset id" to { it: JSONObject -> it.getJSONObject("reference").put("id", "../../asset") },
            "unsupported MIME" to { it: JSONObject -> it.getJSONObject("reference").put("mime", "image/gif") },
            "bad digest" to { it: JSONObject -> it.getJSONObject("reference").put("sha256", "no-digest") },
            "zero bytes" to { it: JSONObject -> it.getJSONObject("reference").put("bytes", 0) },
            "oversized image" to { it: JSONObject -> it.getJSONObject("reference").put("bytes", MessageImageReference.MAX_IMAGE_BYTES + 1) },
            "integer overflow" to { it: JSONObject -> it.getJSONObject("reference").put("bytes", Long.MAX_VALUE) },
            "negative dimensions" to { it: JSONObject -> it.getJSONObject("reference").put("height", -1) },
            "pixel limit" to { it: JSONObject -> it.getJSONObject("reference").put("width", 40_000_001).put("height", 1) },
            "blank owner" to { it: JSONObject -> it.getJSONObject("reference").put("conversation", " ") }
        )
        listOf(false, true).forEach { history ->
            mutations.forEach { (label, mutate) ->
                val rows = json(ArchiveCodec.encode(listOf(if (history) historyOwner() else row())))
                mutate(imageBlock(rows.getJSONObject(0), history))
                assertRejected("$label history=$history") { ArchiveCodec.decode(bytes(rows)) }
            }
        }
    }

    @Test
    fun duplicateCountAndAggregateByteLimitsAreEnforcedOnEncodeAndDecode() {
        listOf(false, true).forEach { history ->
            val base = if (history) historyOwner() else row()
            val targetId = if (history) base.historySegments.first().messageId.value else base.id.value
            val first = image(targetId, 1)
            val invalid = listOf(
                "duplicate" to listOf(first, first),
                "count" to (1..MessageImageReference.MAX_IMAGES + 1).map { image(targetId, it) },
                "aggregate bytes" to (1..4).map { image(targetId, it, MessageImageReference.MAX_IMAGE_BYTES) }
            )
            invalid.forEach { (label, blocks) ->
                val bad = if (history) base.copy(historySegments = listOf(base.historySegments.first().copy(blocks = blocks)))
                    else base.copy(blocks = blocks)
                assertRejected("encode $label history=$history") { ArchiveCodec.encode(listOf(bad)) }
                val rows = json(ArchiveCodec.encode(listOf(base)))
                val holder = if (history) rows.getJSONObject(0).getJSONArray("history_segments").getJSONObject(0)
                    else rows.getJSONObject(0)
                holder.put("blocks", ArchiveCodec.blocks(blocks))
                assertRejected("decode $label history=$history") { ArchiveCodec.decode(bytes(rows)) }
            }
        }
    }

    @Test
    fun exactCountAndAggregateByteCeilingsAreAccepted() {
        val base = row()
        val atCount = base.copy(blocks = (1..MessageImageReference.MAX_IMAGES).map { image(base.id.value, it) })
        val atBytes = base.copy(blocks = (1..4).map { image(base.id.value, it, 16 * 1024 * 1024) })
        listOf(atCount, atBytes).forEach { expected ->
            assertEquals(listOf(expected), ArchiveCodec.decode(ArchiveCodec.encode(listOf(expected))))
        }
        val owner = historyOwner()
        val segment = owner.historySegments.first()
        val bounded = owner.copy(historySegments = listOf(segment.copy(
            blocks = (1..4).map { image(segment.messageId.value, it, 16 * 1024 * 1024) })))
        assertEquals(listOf(bounded), ArchiveCodec.decode(ArchiveCodec.encode(listOf(bounded))))
    }

    private fun row(): Message = Message(MessageId("assistant-owner"), ConversationId("conversation"),
        RunId("run"), Message.ASSISTANT_ROLE, listOf(
            MessageBlock.Text("before", "看图 🌸"), image("assistant-owner", 1),
            MessageBlock.Tool(ToolInvocation(ToolCallId("lookup"), "inspect", "{\"full\":true}"), "完整工具结果\n第二行"),
            image("assistant-owner", 2), MessageBlock.Text("after", "保留顺序")),
        RunStatus.STOPPED, "cancelled by user", listOf("{\"type\":\"message\"}", "{\"type\":\"tool_result\"}"))

    private fun historyOwner(): Message {
        val row = row()
        return row.copy(historySegments = listOf(
            MessageHistorySegment(MessageId("user-supplement"), Message.USER_ROLE,
                listOf(image("user-supplement", 3), MessageBlock.Text("question", "补充图片")), listOf("user-protocol")),
            MessageHistorySegment(row.id, row.role, row.blocks, row.protocolItems)))
    }

    private fun image(message: String, index: Int, size: Int = 12): MessageBlock.Image = MessageBlock.Image(
        MessageImageReference(MessageImageOwner("conversation", message),
            "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
            if (index % 2 == 0) "image/webp" else "image/png", size, "a".repeat(64), 3, 4))

    private fun imageBlock(owner: JSONObject, history: Boolean): JSONObject = if (history)
        owner.getJSONArray("history_segments").getJSONObject(0).getJSONArray("blocks").getJSONObject(0)
    else owner.getJSONArray("blocks").getJSONObject(1)

    private fun json(bytes: ByteArray): JSONArray = JSONArray(String(bytes, Charsets.UTF_8))
    private fun bytes(rows: JSONArray): ByteArray = rows.toString().toByteArray(Charsets.UTF_8)
    private fun assertRejected(label: String, block: () -> Unit) {
        var failure: RuntimeException? = null
        try { block() } catch (caught: RuntimeException) { failure = caught }
        assertNotNull("Must reject $label instead of dropping/coercing images", failure)
    }
}
