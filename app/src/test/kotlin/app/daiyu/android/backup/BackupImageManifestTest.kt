package app.daiyu.android.backup

import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.storage.MessageImageReferenceCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupImageManifestTest {
    private val bytes = byteArrayOf(1, 2, 3)
    private fun reference(message: String = "message", id: Int = 0) = MessageImageReference(
        MessageImageOwner("conversation", message), "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}",
        "image/png", bytes.size, PrivateBlobs.digest(bytes), 1, 1)
    private fun message(reference: MessageImageReference) = Message(MessageId(reference.owner.messageId),
        ConversationId(reference.owner.conversationId), RunId("run"), "user", listOf(MessageBlock.Image(reference)), RunStatus.COMPLETED)
    private fun references(vararg refs: MessageImageReference) = BackupImageManifest.References().apply { addArchive(refs.map(::message)) }
    private fun reject(action: () -> Unit) {
        try { action(); fail("must reject") } catch (_: IllegalArgumentException) { }
    }

    @Test fun identicalRowAndArchiveReferencesDeduplicateButConflictingMetadataRejects() {
        val ref = reference(); val expected = references(ref)
        expected.addArchive(listOf(message(ref)))
        assertEquals(listOf(ref), expected.all())
        reject { expected.addArchive(listOf(message(ref.copy(width = 2)))) }
    }

    @Test fun actualSegmentOwnerIsRequiredNotTheOwningAssistantOrReferenceClaim() {
        val ref = reference("supplement")
        val row = JSONObject().put("id", "assistant").put("conversation_id", "conversation")
            .put("blocks", JSONArray()).put("history_segments", JSONArray().put(JSONObject()
                .put("message_id", "supplement").put("role", "user")
                .put("blocks", JSONArray().put(MessageImageReferenceCodec.encodeBlock(MessageBlock.Image(ref))))
                .put("protocol", JSONArray())))
        val expected = BackupImageManifest.References().apply { addRows(JSONArray().put(row)) }
        assertEquals(listOf(ref), expected.all())
        row.getJSONArray("history_segments").getJSONObject(0).put("message_id", "foreign")
        reject { BackupImageManifest.References().addRows(JSONArray().put(row)) }
    }

    @Test fun manifestMustExactlyCoverReferencesAndOriginalsMustMatch() {
        val ref = reference(); val expected = references(ref)
        val payload = JSONObject().put("message_images", BackupImageManifest.encode(listOf(ref)))
        assertEquals(listOf(ref), BackupImageManifest.validate(payload, expected, mapOf(ref.sha256 to bytes)))
        reject { BackupImageManifest.validate(JSONObject(), expected, mapOf(ref.sha256 to bytes)) }
        reject { BackupImageManifest.validate(payload, expected, emptyMap()) }
        reject { BackupImageManifest.validate(payload, expected, mapOf(ref.sha256 to byteArrayOf(1, 2))) }
        reject { BackupImageManifest.validate(payload, expected, mapOf(ref.sha256 to byteArrayOf(3, 2, 1))) }
        payload.getJSONArray("message_images").put(MessageImageReferenceCodec.encode(reference("unreferenced")))
        reject { BackupImageManifest.validate(payload, expected, mapOf(ref.sha256 to bytes)) }
    }

    @Test fun closedManifestRejectsPathOrUnknownFieldsAndDuplicateEntries() {
        val ref = reference(); val expected = references(ref)
        val encoded = MessageImageReferenceCodec.encode(ref).put("path", "/somewhere/image.png")
        reject { BackupImageManifest.validate(JSONObject().put("message_images", JSONArray().put(encoded)), expected, mapOf(ref.sha256 to bytes)) }
        val duplicate = JSONArray().put(MessageImageReferenceCodec.encode(ref)).put(MessageImageReferenceCodec.encode(ref))
        reject { BackupImageManifest.validate(JSONObject().put("message_images", duplicate), expected, mapOf(ref.sha256 to bytes)) }
    }

    @Test fun perOwnerLimitAlsoAppliesAcrossRepeatedArchiveSnapshots() {
        val expected = BackupImageManifest.References()
        repeat(MessageImageReference.MAX_IMAGES) { expected.addArchive(listOf(message(reference(id = it)))) }
        reject { expected.addArchive(listOf(message(reference(id = MessageImageReference.MAX_IMAGES)))) }
    }

    @Test fun encodedManifestHasABoundIndependentOfPackageBlobLimit() {
        val large = List(8_000) { index -> reference().copy(owner = MessageImageOwner("c".repeat(200), index.toString().padStart(200, 'm'))) }
        reject { BackupImageManifest.encode(large) }
        reject { BackupImageManifest.encode(List(BackupImageManifest.MAX_ENTRIES + 1) { reference() }) }
    }

    @Test fun imageFreeLegacyPayloadDoesNotNeedAManifest() {
        assertTrue(BackupImageManifest.validate(JSONObject(), BackupImageManifest.References(), emptyMap()).isEmpty())
        assertEquals("[]", BackupImageManifest.encode(emptyList()).toString())
    }
}
