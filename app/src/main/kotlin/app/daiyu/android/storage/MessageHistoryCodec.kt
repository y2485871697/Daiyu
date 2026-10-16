package app.daiyu.android.storage

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistorySegment
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.ToolCallId
import app.daiyu.android.domain.ToolInvocation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable v1 representation for the provider history attached to a message.
 * The four keys are intentionally closed: changing this shape would make an old
 * history silently lose protocol items or tool ordering.
 */
object MessageHistoryCodec {
    fun encode(segments: List<MessageHistorySegment>): JSONArray {
        segments.forEach { validateSegment(it) }
        return JSONArray().apply {
            segments.forEach { segment ->
                put(JSONObject()
                    .put("message_id", segment.messageId.value)
                    .put("role", segment.role)
                    .put("blocks", encodeBlocks(segment.blocks))
                    .put("protocol", JSONArray(segment.protocolItems)))
            }
        }
    }

    fun decode(array: JSONArray): List<MessageHistorySegment> {
        val result = (0 until array.length()).map {
            val value = array.getJSONObject(it)
            require(value.keys().asSequence().toSet() == setOf("message_id", "role", "blocks", "protocol"))
            val id = text(value, "message_id")
            val role = text(value, "role")
            val blocks = value.getJSONArray("blocks")
            val protocol = value.getJSONArray("protocol")
            validateBlocks(blocks)
            val protocolItems = (0 until protocol.length()).map { (protocol.get(it) as? String)
                ?: error("历史协议项须为文本") }
            MessageHistorySegment(MessageId(id), role, decodeBlocks(blocks), protocolItems)
        }
        result.forEach { validateSegment(it) }
        return result
    }

    /** Validate both the regular message identity and its optional history projection. */
    fun validate(message: Message) {
        require(message.id.value.isNotBlank() && message.id.value.length <= 200)
        require(message.conversationId.value.isNotBlank() && message.conversationId.value.length <= 200)
        require(message.runId.value.isNotBlank() && message.runId.value.length <= 200)
        require(message.role == "user" || message.role == "assistant")
        MessageHistory.validateOwner(message)
        message.historySegments.forEach(::validateSegment)
    }

    private fun validateSegment(segment: MessageHistorySegment) {
        MessageHistory.validateSegment(segment)
        require(segment.blocks.size <= 10_000 && segment.protocolItems.size <= 10_000)
        fun field(value: String) { require(value.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) }
        segment.blocks.forEach { block -> when (block) {
            is MessageBlock.Text -> field(block.text)
            is MessageBlock.Image -> Unit // Immutable metadata was validated by core-domain.
            is MessageBlock.Tool -> { field(block.invocation.arguments); block.result?.let(::field) }
        } }
        segment.protocolItems.forEach { require(it.length <= 1_000_000); field(it) }
    }

    private fun encodeBlocks(blocks: List<MessageBlock>) = JSONArray().apply {
        blocks.forEach { block -> put(when (block) {
            is MessageBlock.Text -> JSONObject().put("type", "text").put("item", block.itemId).put("text", block.text)
            is MessageBlock.Image -> MessageImageReferenceCodec.encodeBlock(block)
            is MessageBlock.Tool -> JSONObject().put("type", "tool").put("id", block.invocation.id.value)
                .put("name", block.invocation.name).put("arguments", block.invocation.arguments)
                .put("result", block.result ?: JSONObject.NULL)
        }) }
    }

    private fun decodeBlocks(array: JSONArray): List<MessageBlock> = (0 until array.length()).map { index ->
        val block = array.getJSONObject(index)
        when (text(block, "type")) {
            "image" -> MessageImageReferenceCodec.decodeBlock(block)
            "text" -> {
                require(block.keys().asSequence().toSet() == setOf("type", "item", "text"))
                MessageBlock.Text(text(block, "item"), text(block, "text"))
            }
            "tool" -> {
                require(block.keys().asSequence().toSet() == setOf("type", "id", "name", "arguments", "result"))
                MessageBlock.Tool(ToolInvocation(ToolCallId(text(block, "id")), text(block, "name"), text(block, "arguments")),
                    if (block.isNull("result")) null else text(block, "result"))
            }
            else -> error("不支持的历史块")
        }
    }

    private fun validateBlocks(array: JSONArray) {
        repeat(array.length()) { index ->
            val block = array.getJSONObject(index)
            when (text(block, "type")) {
                "image" -> MessageImageReferenceCodec.decodeBlock(block)
                "text" -> { require(block.keys().asSequence().toSet() == setOf("type", "item", "text")); text(block, "item"); text(block, "text") }
                "tool" -> {
                    require(block.keys().asSequence().toSet() == setOf("type", "id", "name", "arguments", "result"))
                    text(block, "id"); text(block, "name"); text(block, "arguments")
                    if (!block.isNull("result")) text(block, "result")
                }
                else -> error("不支持的历史块")
            }
        }
    }

    private fun text(value: JSONObject, key: String): String = (value.get(key) as? String)
        ?: error("历史字段须为文本: $key")
}
