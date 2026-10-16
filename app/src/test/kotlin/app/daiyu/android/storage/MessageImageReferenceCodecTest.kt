package app.daiyu.android.storage

import app.daiyu.android.domain.*
import app.daiyu.android.media.ImageAttachmentOwner
import app.daiyu.android.media.ImageAttachmentStore
import app.daiyu.android.media.ImageDimensions
import app.daiyu.android.media.ImageInspector
import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.MessageImageOwner as AssetOwner
import app.daiyu.android.media.MessageImageReference as AssetReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MessageImageReferenceCodecTest {
    private val owner = MessageImageOwner("conversation", "user")
    private fun reference(index: Int = 0) = MessageImageReference(owner,
        "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        "image/png", 128, "a".repeat(64), 8, 4)
    private fun rejected(action: () -> Unit) { assertThrows(Exception::class.java) { action() } }

    @Test fun referenceWireAndBridgeExactlyMatchTheExistingAssetContract() {
        val reference = reference()
        val asset = MessageImageReferenceCodec.toAsset(reference)
        assertEquals(reference, MessageImageReferenceCodec.fromAsset(asset))
        assertEquals(reference, MessageImageReferenceCodec.fromJson(asset.toJson()))
        assertEquals(asset, AssetReference.fromJson(MessageImageReferenceCodec.toJson(reference)))
        assertEquals(reference, MessageImageReferenceCodec.fromJson(MessageImageReferenceCodec.toJson(reference)))
        val value = MessageImageReferenceCodec.encode(reference)
        assertEquals(setOf("version", "conversation", "message", "id", "mime", "bytes", "sha256", "width", "height"),
            value.keys().asSequence().toSet())
        assertEquals(1, value.getInt("version"))
        assertEquals(owner.conversationId, value.getString("conversation"))
        assertEquals(owner.messageId, value.getString("message"))
        assertEquals(MessageBlock.Image(reference), MessageImageReferenceCodec.decodeBlock(
            MessageImageReferenceCodec.encodeBlock(MessageBlock.Image(reference))))
    }

    @Test fun strictReferenceDecoderRejectsUnknownMissingAndCoercedFields() {
        val valid = MessageImageReferenceCodec.encode(reference())
        val fields = valid.keys().asSequence().toList()
        fields.forEach { key ->
            rejected { MessageImageReferenceCodec.decode(JSONObject(valid.toString()).apply { remove(key) }) }
            rejected { MessageImageReferenceCodec.decode(JSONObject(valid.toString()).put(key, JSONObject.NULL)) }
        }
        listOf("uri", "data", "path", "draft", "permission", "credentials").forEach { key ->
            rejected { MessageImageReferenceCodec.decode(JSONObject(valid.toString()).put(key, "secret")) }
        }
        listOf("version", "bytes", "width", "height").forEach { key ->
            listOf<Any>("1", true, 1.0, 1.5, Long.MAX_VALUE, Long.MIN_VALUE).forEach { bad ->
                rejected { MessageImageReferenceCodec.decode(JSONObject(valid.toString()).put(key, bad)) }
            }
        }
        listOf("conversation", "message", "id", "mime", "sha256").forEach { key ->
            listOf<Any>(1, true, JSONObject(), JSONArray()).forEach { bad ->
                rejected { MessageImageReferenceCodec.decode(JSONObject(valid.toString()).put(key, bad)) }
            }
        }
        listOf(0, 2, -1).forEach { version ->
            rejected { MessageImageReferenceCodec.decode(JSONObject(valid.toString()).put("version", version)) }
        }
        rejected { MessageImageReferenceCodec.fromJson(valid.toString() + " {}") }
        rejected { MessageImageReferenceCodec.fromJson("[]") }
        rejected { MessageImageReferenceCodec.fromJson(" ".repeat(4097)) }
    }

    @Test fun rawReferenceJsonRejectsDuplicateKeysAndPermissiveParserSyntax() {
        val valid = MessageImageReferenceCodec.toJson(reference())
        listOf(
            "{\"version\":1," + valid.drop(1),
            "{\"ver\\u0073ion\":1," + valid.drop(1),
            valid.dropLast(1) + ",}",
            valid.replace("\"version\"", "version"),
            valid.replace("\"version\"", "'version'"),
            valid.replace("\"version\":1", "\"version\":1.0"),
            valid.replace("\"version\":1", "\"version\":01"),
            "/* comment */$valid"
        ).forEach { malformed -> rejected { MessageImageReferenceCodec.fromJson(malformed) } }
        val escaped = reference().copy(owner = MessageImageOwner("c\\\"\n雪", "m\t\\end"))
        assertEquals(escaped, MessageImageReferenceCodec.fromJson(MessageImageReferenceCodec.toJson(escaped)))
    }

    @Test fun strictDecoderRejectsInvalidMetadataAndBlockPayloads() {
        val valid = MessageImageReferenceCodec.encode(reference())
        val mutations = listOf("id" to "content://provider/image?token=secret", "mime" to "image/svg+xml",
            "sha256" to "A".repeat(64), "bytes" to 0, "bytes" to MessageImageReference.MAX_IMAGE_BYTES + 1,
            "width" to Int.MAX_VALUE, "height" to -1, "conversation" to "", "message" to "x".repeat(201))
        mutations.forEach { (key, value) ->
            rejected { MessageImageReferenceCodec.decode(JSONObject(valid.toString()).put(key, value)) }
        }
        val block = MessageImageReferenceCodec.encodeBlock(MessageBlock.Image(reference()))
        rejected { MessageImageReferenceCodec.decodeBlock(JSONObject(block.toString()).put("uri", "secret")) }
        rejected { MessageImageReferenceCodec.decodeBlock(JSONObject(block.toString()).put("type", "text")) }
        rejected { MessageImageReferenceCodec.decodeBlock(JSONObject(block.toString()).put("reference", valid.toString())) }
    }

    @Test fun orderedImageHistoryRoundTripsWithoutChangingLegacyTextAndToolShapes() {
        val userBlocks = listOf(MessageBlock.Text("question", "before"), MessageBlock.Image(reference()),
            MessageBlock.Text("caption", "between"), MessageBlock.Image(reference(1)))
        val user = MessageHistorySegment(MessageId("user"), Message.USER_ROLE, userBlocks)
        val assistant = MessageHistorySegment(MessageId("assistant"), Message.ASSISTANT_ROLE,
            listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "lookup", "{}"), "found"), MessageBlock.Text("answer", "after")),
            listOf("opaque protocol"))
        val segments = listOf(user, assistant)
        val encoded = MessageHistoryCodec.encode(segments)
        val restored = MessageHistoryCodec.decode(JSONArray(encoded.toString()))
        assertEquals(segments, restored)
        assertEquals(userBlocks, restored.first().blocks)
        assertEquals(setOf("type", "item", "text"), encoded.getJSONObject(0).getJSONArray("blocks")
            .getJSONObject(0).keys().asSequence().toSet())
        val legacyTool = encoded.getJSONObject(1).getJSONArray("blocks").getJSONObject(0)
        assertEquals(setOf("type", "id", "name", "arguments", "result"), legacyTool.keys().asSequence().toSet())
        val legacy = JSONArray("""[{"message_id":"old","role":"assistant","blocks":[{"type":"text","item":"t","text":"unchanged"},{"type":"tool","id":"c","name":"n","arguments":"{}","result":null}],"protocol":[]}]""")
        val old = MessageHistoryCodec.decode(legacy)
        assertEquals("unchanged", (old.single().blocks.first() as MessageBlock.Text).text)
        assertNull((old.single().blocks.last() as MessageBlock.Tool).result)
        assertEquals(old, MessageHistoryCodec.decode(MessageHistoryCodec.encode(old)))
        assertEquals(emptyList<MessageHistorySegment>(), MessageHistoryCodec.decode(JSONArray()))
    }

    @Test fun historyRejectsCrossOwnerDuplicateAndUnknownImagePayloads() {
        val image = MessageBlock.Image(reference())
        fun segment(blocks: List<MessageBlock>) = MessageHistorySegment(MessageId("user"), Message.USER_ROLE, blocks)
        rejected { MessageHistoryCodec.encode(listOf(segment(listOf(image, image)))) }
        val foreignImage = MessageBlock.Image(reference().copy(owner = owner.copy(messageId = "other")))
        rejected { MessageHistoryCodec.encode(listOf(segment(listOf(foreignImage)))) }
        val wire = MessageHistoryCodec.encode(listOf(segment(listOf(image))))
        wire.getJSONObject(0).getJSONArray("blocks").getJSONObject(0).getJSONObject("reference").put("uri", "secret")
        rejected { MessageHistoryCodec.decode(wire) }
        val ownerRow = Message(MessageId("assistant"), ConversationId("other"), RunId("run"), Message.ASSISTANT_ROLE,
            emptyList(), RunStatus.COMPLETED, historySegments = listOf(segment(listOf(image))))
        rejected { MessageHistoryCodec.validate(ownerRow) }
        val wrongPlainOwner = ownerRow.copy(id = MessageId("user"), role = Message.USER_ROLE,
            blocks = listOf(image), historySegments = emptyList())
        rejected { MessageHistoryCodec.validate(wrongPlainOwner) }
    }

    @Test fun serializedReferenceCanResolveOriginalBytesAfterDraftDeletionAndAssetReopen() {
        val root = Files.createTempDirectory("image-codec").toFile()
        try {
            val original = byteArrayOf(1, 2, 3, 4, 5)
            val drafts = ImageAttachmentStore(File(root, "drafts"), ImageInspector { ImageDimensions("image/png", 4, 3) })
            val draftOwner = ImageAttachmentOwner("conversation", "draft")
            val draft = drafts.import(draftOwner, original.inputStream())
            val assetOwner = AssetOwner(owner.conversationId, owner.messageId)
            val assets = MessageImageAssetStore(File(root, "messages"))
            val accepted = assets.prepare(assetOwner, draftOwner, drafts.freeze(draftOwner, listOf(draft), true)).single()
            val segment = MessageHistorySegment(MessageId(owner.messageId), Message.USER_ROLE,
                listOf(MessageBlock.Image(MessageImageReferenceCodec.fromAsset(accepted))))
            val persisted = MessageHistoryCodec.encode(listOf(segment)).toString()
            drafts.clear(draftOwner)
            val restored = MessageHistoryCodec.decode(JSONArray(persisted)).single().blocks.single() as MessageBlock.Image
            val reopened = MessageImageAssetStore(File(root, "messages"))
            assertEquals(accepted, MessageImageReferenceCodec.toAsset(restored.reference))
            assertArrayEquals(original, reopened.resolver.read(assetOwner, MessageImageReferenceCodec.toAsset(restored.reference)))
            assertFalse(persisted.contains("draft"))
            assertFalse(persisted.contains(root.absolutePath))
            assertFalse(persisted.contains("content://"))
        } finally { root.deleteRecursively() }
    }
}
