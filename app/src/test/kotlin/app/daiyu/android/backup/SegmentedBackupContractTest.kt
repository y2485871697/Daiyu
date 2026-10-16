package app.daiyu.android.backup

import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.domain.MessageHistorySegment
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.ToolCallId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.storage.MessageHistoryCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM wire/history contracts only: no ContextService, database, or instrumentation. */
class SegmentedBackupContractTest {
    @Test
    fun archiveRoundTripPreservesTextToolTextOrderFullResultProtocolAndOwner() {
        val owner = segmentedOwner()
        val bytes = ArchiveCodec.encode(listOf(owner))
        val wire = JSONArray(String(bytes, Charsets.UTF_8)).getJSONObject(0)
        val wireSegments = wire.getJSONArray("history_segments")

        // Exercise the actual shared segment codec, not a test-side replacement.
        assertEquals(owner.historySegments, MessageHistoryCodec.decode(wireSegments))
        assertEquals(MessageHistoryCodec.encode(owner.historySegments).toString(), wireSegments.toString())
        val restored = ArchiveCodec.decode(bytes)
        assertEquals(listOf(owner), restored)
        assertArrayEquals(bytes, ArchiveCodec.encode(restored))

        val expanded = MessageHistory.expand(restored)
        assertEquals(3, expanded.size)
        val expectedBlocks = owner.historySegments.map { it.blocks }
        assertEquals(expectedBlocks, expanded.map { it.blocks })
        assertEquals(owner.historySegments.map { it.protocolItems }, expanded.map { it.protocolItems })
        assertEquals(listOf("准备查询：你好 🌸", fullToolResult(), "查询结束：保留完整结果。"), expanded.map {
            when (val block = it.blocks.single()) {
                is MessageBlock.Text -> block.text
                is MessageBlock.Tool -> block.result
                is MessageBlock.Image -> error("This legacy text/tool fixture must not contain an image")
            }
        })
        assertEquals(owner.blocks[1], expanded[1].blocks.single())
        expanded.forEach { message ->
            assertEquals(owner.id, message.id)
            assertEquals(owner.conversationId, message.conversationId)
            assertEquals(owner.runId, message.runId)
            assertEquals(Message.ASSISTANT_ROLE, message.role)
            assertEquals(owner.status, message.status)
            assertEquals(owner.error, message.error)
            assertTrue(message.historySegments.isEmpty())
        }
    }

    @Test
    fun emptySegmentsKeepLegacyArchiveBytesAndDoNotAddHistoryMember() {
        val assistant = segmentedOwner().copy(historySegments = emptyList(), error = "旧错误", status = RunStatus.FAILED)
        val user = Message(MessageId("legacy-user"), assistant.conversationId, RunId("legacy-run"),
            Message.USER_ROLE, listOf(MessageBlock.Text("legacy-input", "旧正文")), RunStatus.COMPLETED)
        val pendingTool = assistant.copy(id = MessageId("legacy-pending"), blocks = listOf(
            MessageBlock.Tool(ToolInvocation(ToolCallId("pending-call"), "pending_tool", "{}"))))
        val legacy = listOf(user, assistant, pendingTool)

        // Frozen pre-segment encoder shape; deliberately independent of ArchiveCodec.blocks.
        val oldBytes = legacyArchiveBytes(legacy)
        val newBytes = ArchiveCodec.encode(legacy)
        assertArrayEquals("Empty histories must not alter content-addressed legacy bytes", oldBytes, newBytes)
        val wire = JSONArray(String(newBytes, Charsets.UTF_8))
        repeat(wire.length()) { assertFalse(wire.getJSONObject(it).has("history_segments")) }
        val restored = ArchiveCodec.decode(oldBytes)
        assertEquals(legacy, restored)
        assertTrue(restored.all { it.historySegments.isEmpty() })
        assertEquals(legacy, MessageHistory.expand(restored))
        assertArrayEquals(oldBytes, ArchiveCodec.encode(restored))
    }

    @Test
    fun malformedUtf8InsideOtherwiseValidArchiveTextIsRejected() {
        val valid = ArchiveCodec.encode(listOf(segmentedOwner()))
        // Corrupt an ASCII character inside a JSON string, not JSON punctuation. A lenient
        // replacement decoder would still produce parseable JSON and silently lose data.
        val marker = "body-before"
        val index = String(valid, Charsets.UTF_8).indexOf(marker)
        assertTrue(index >= 0)
        val byteIndex = String(valid, Charsets.UTF_8).substring(0, index).toByteArray(Charsets.UTF_8).size
        listOf(0x80, 0xc0, 0xff).forEach { invalid ->
            val broken = valid.copyOf()
            broken[byteIndex] = invalid.toByte()
            assertRejected("invalid UTF-8 byte $invalid") { ArchiveCodec.decode(broken) }
        }
    }

    @Test
    fun malformedHistoryFieldsAreRejectedRatherThanDroppedOrCoerced() {
        val mutations: List<Pair<String, (JSONObject) -> Unit>> = listOf(
            "non-array history" to { it: JSONObject -> it.put("history_segments", "[]") },
            "null history" to { it: JSONObject -> it.put("history_segments", JSONObject.NULL) },
            "non-object segment" to { it: JSONObject -> it.getJSONArray("history_segments").put(0, "segment") },
            "missing protocol" to { it: JSONObject -> firstSegment(it).remove("protocol") },
            "unknown segment field" to { it: JSONObject -> firstSegment(it).put("unknown", true) },
            "non-text identity" to { it: JSONObject -> firstSegment(it).put("message_id", 17) },
            "unsupported role" to { it: JSONObject -> firstSegment(it).put("role", "system") },
            "non-array blocks" to { it: JSONObject -> firstSegment(it).put("blocks", JSONObject()) },
            "non-text protocol item" to { it: JSONObject -> firstSegment(it).put("protocol", JSONArray().put(17)) },
            "unknown block field" to { it: JSONObject ->
                firstSegment(it).getJSONArray("blocks").getJSONObject(0).put("unknown", true)
            },
            "non-text tool result" to { it: JSONObject ->
                it.getJSONArray("history_segments").getJSONObject(1)
                    .getJSONArray("blocks").getJSONObject(0).put("result", 17)
            }
        )
        mutations.forEach { (label, mutate) ->
            val array = encodedOwner()
            mutate(array.getJSONObject(0))
            assertRejected(label) { ArchiveCodec.decode(array.toString().toByteArray(Charsets.UTF_8)) }
        }
    }

    @Test
    fun invalidHistoryOwnersAreRejectedBeforeEncodingAndByExpansion() {
        val owner = segmentedOwner()
        val userSegment = MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE,
            listOf(MessageBlock.Text("supplement-input", "补充问题")))
        val invalidOwners = listOf(
            owner.copy(role = Message.USER_ROLE, blocks = emptyList()),
            owner.copy(historySegments = listOf(owner.historySegments.first().copy(messageId = MessageId("other-owner")))),
            owner.copy(historySegments = listOf(userSegment.copy(messageId = owner.id))),
            owner.copy(historySegments = owner.historySegments + listOf(userSegment, userSegment))
        )
        invalidOwners.forEachIndexed { index, invalid ->
            assertRejected("segment codec owner $index") { MessageHistoryCodec.validate(invalid) }
            assertRejected("archive encode owner $index") { ArchiveCodec.encode(listOf(invalid)) }
            assertRejected("expand owner $index") { MessageHistory.expand(listOf(invalid)) }
        }
    }

    @Test
    fun archiveDecodeRejectsOwnerMismatchEvenWhenSegmentShapeIsValid() {
        val mutations: List<Pair<String, (JSONObject) -> Unit>> = listOf(
            "user cannot own history" to { it: JSONObject -> it.put("role", Message.USER_ROLE).put("blocks", JSONArray()) },
            "assistant segment must belong to owner" to { it: JSONObject -> firstSegment(it).put("message_id", "other-owner") },
            "user segment cannot reuse owner id" to { it: JSONObject -> firstSegment(it).put("role", Message.USER_ROLE) },
            "duplicate user supplement identities" to { it: JSONObject ->
                val segments = it.getJSONArray("history_segments")
                val user = JSONObject().put("message_id", "duplicate-user").put("role", Message.USER_ROLE)
                    .put("blocks", JSONArray()).put("protocol", JSONArray())
                segments.put(user).put(JSONObject(user.toString()))
            }
        )
        mutations.forEach { (label, mutate) ->
            val array = encodedOwner()
            mutate(array.getJSONObject(0))
            // Each array is valid in isolation: only the enclosing owner makes it invalid.
            MessageHistoryCodec.decode(array.getJSONObject(0).getJSONArray("history_segments"))
            assertRejected(label) { ArchiveCodec.decode(array.toString().toByteArray(Charsets.UTF_8)) }
        }
    }

    @Test
    fun expandedSupplementUsesSegmentOrderWithoutDuplicatingStandaloneUserRow() {
        val owner = segmentedOwner()
        val supplement = MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE,
            listOf(MessageBlock.Text("supplement-input", "补充问题")), listOf("{\"type\":\"user_input\"}"))
        val withSupplement = owner.copy(historySegments = owner.historySegments.take(2) +
            supplement + owner.historySegments.last())
        val standalone = Message(supplement.messageId, owner.conversationId, owner.runId, supplement.role,
            supplement.blocks, RunStatus.COMPLETED, protocolItems = supplement.protocolItems)
        val restored = ArchiveCodec.decode(ArchiveCodec.encode(listOf(withSupplement, standalone)))
        val expanded = MessageHistory.expand(restored)
        assertEquals(listOf(owner.id, owner.id, supplement.messageId, owner.id), expanded.map { it.id })
        assertEquals(withSupplement.historySegments.map { it.blocks }, expanded.map { it.blocks })
        assertEquals(withSupplement.historySegments.map { it.protocolItems }, expanded.map { it.protocolItems })
        assertEquals(standalone, expanded[2])
        assertTrue(expanded.all { it.conversationId == owner.conversationId && it.runId == owner.runId })
        assertTrue(expanded.all { it.historySegments.isEmpty() })
    }

    private fun segmentedOwner(): Message {
        val ownerId = MessageId("assistant-owner")
        val blocks = listOf(
            MessageBlock.Text("body-before", "准备查询：你好 🌸"),
            MessageBlock.Tool(ToolInvocation(ToolCallId("tool-call"), "lookup",
                "{\"query\":\"完整结果\"}"), fullToolResult()),
            MessageBlock.Text("body-after", "查询结束：保留完整结果。")
        )
        val protocol = listOf(
            listOf("{\"type\":\"message\",\"id\":\"before\"}"),
            listOf("{\"type\":\"function_call\",\"call_id\":\"tool-call\"}",
                "{\"type\":\"function_call_output\",\"output\":\"完整输出 🌸\"}"),
            listOf("{\"type\":\"message\",\"id\":\"after\"}")
        )
        return Message(ownerId, ConversationId("conversation"), RunId("run"), Message.ASSISTANT_ROLE,
            blocks, RunStatus.COMPLETED, protocolItems = listOf("owner-envelope"),
            historySegments = blocks.mapIndexed { index, block ->
                MessageHistorySegment(ownerId, Message.ASSISTANT_ROLE, listOf(block), protocol[index])
            })
    }

    private fun fullToolResult(): String = "完整工具结果开始\n" +
        (1..128).joinToString("\n") { "第${it}行：保留 Unicode 🌸、引号 \" 和反斜线 \\" } + "\n完整工具结果结束"

    private fun encodedOwner(): JSONArray =
        JSONArray(String(ArchiveCodec.encode(listOf(segmentedOwner())), Charsets.UTF_8))

    private fun firstSegment(message: JSONObject): JSONObject = message.getJSONArray("history_segments").getJSONObject(0)

    private fun assertRejected(label: String, action: () -> Unit) {
        val failure = try {
            action()
            null
        } catch (expected: Exception) {
            expected
        }
        assertNotNull("Must reject $label", failure)
    }

    /** Pre-history archive wire format, retained here as a byte-compatibility oracle. */
    private fun legacyArchiveBytes(messages: List<Message>): ByteArray = JSONArray().apply {
        messages.forEach { message ->
            val blocks = JSONArray().apply {
                message.blocks.forEach { block ->
                    put(when (block) {
                        is MessageBlock.Text -> JSONObject().put("type", "text")
                            .put("item", block.itemId).put("text", block.text)
                        is MessageBlock.Tool -> JSONObject().put("type", "tool")
                            .put("id", block.invocation.id.value).put("name", block.invocation.name)
                            .put("arguments", block.invocation.arguments).put("result", block.result ?: JSONObject.NULL)
                        is MessageBlock.Image -> error("The pre-image compatibility oracle accepts only text/tool fixtures")
                    })
                }
            }
            put(JSONObject().put("id", message.id.value).put("conversation", message.conversationId.value)
                .put("run", message.runId.value).put("role", message.role).put("status", message.status.name)
                .put("error", message.error ?: JSONObject.NULL).put("protocol", JSONArray(message.protocolItems))
                .put("blocks", blocks))
        }
    }.toString().toByteArray(Charsets.UTF_8)
}
