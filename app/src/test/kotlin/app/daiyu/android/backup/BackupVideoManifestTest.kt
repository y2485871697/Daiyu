package app.daiyu.android.backup

import app.daiyu.android.context.PrivateBlobs
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.model.videos.MessageVideoInput
import app.daiyu.android.storage.MessageHistoryCodec
import app.daiyu.android.storage.MessageVideoInputCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupVideoManifestTest {
    private val bytes = byteArrayOf(3, 2, 1)
    private val user = Message(MessageId("user"), ConversationId("conversation"), RunId("run"),
        "user", listOf(MessageBlock.Text("text", "original")), RunStatus.COMPLETED)
    private fun reference(message: String = "user", n: Int = 1) = MessageVideoReference(
        MessageVideoOwner("conversation", message), "11111111-1111-1111-1111-${n.toString().padStart(12, '0')}",
        "video/mp4", bytes.size.toLong(), PrivateBlobs.digest(bytes), 1234, 32, 24)
    private fun row(message: Message): JSONObject {
        val blocks = JSONArray().put(JSONObject().put("type", "text").put("item", "text").put("text", "original"))
        return JSONObject().put("id", message.id.value).put("conversation_id", message.conversationId.value)
            .put("run_id", message.runId.value).put("role", message.role).put("blocks", blocks)
            .put("status", message.status.name).put("error", JSONObject.NULL).put("protocol", JSONArray())
            .also { if (message.historySegments.isNotEmpty()) it.put("history_segments", MessageHistoryCodec.encode(message.historySegments)) }
    }
    private fun entry(message: Message, refs: List<MessageVideoReference>) = JSONObject()
        .put("message_id", message.id.value).put("payload", MessageVideoInputCodec.encode(message,
            refs.groupBy { it.owner }.map { (owner, values) -> MessageVideoInput(owner, values) }))
    private fun manifest(vararg entries: JSONObject) = JSONObject().put("version", 1).put("sidecars", JSONArray(entries.toList()))
    private fun reject(block: () -> Unit) {
        try { block(); fail("must reject") } catch (_: IllegalArgumentException) { }
    }

    @Test fun allOriginalMetadataAndReferenceOrderRoundTrip() {
        val refs = listOf(reference(n = 2), reference(n = 1))
        val manifest = manifest(entry(user, refs))
        val parsed = BackupVideoManifest.validate(manifest, JSONArray().put(row(user)), mapOf(refs.first().sha256 to bytes))
        assertEquals(refs, parsed.references)
        assertEquals(MessageVideoInputCodec.encode(user, listOf(MessageVideoInput(refs.first().owner, refs))), parsed.sidecars.single().payload)
        assertEquals(user.id.value, parsed.sidecars.single().messageId)
    }

    @Test fun corruptedOrMissingOriginalNeverPassesValidation() {
        val manifest = manifest(entry(user, listOf(reference())))
        val rows = JSONArray().put(row(user))
        reject { BackupVideoManifest.validate(manifest, rows, emptyMap()) }
        reject { BackupVideoManifest.validate(manifest, rows, mapOf(reference().sha256 to byteArrayOf())) }
        reject { BackupVideoManifest.validate(manifest, rows, mapOf(reference().sha256 to bytes.reversedArray())) }
    }

    @Test fun unknownFieldsVersionDuplicateAndOutOfOrderPhysicalRowsAreRejected() {
        val e = entry(user, listOf(reference()))
        val rows = JSONArray().put(row(user))
        reject { BackupVideoManifest.decode(manifest(e).put("path", "video.mp4"), rows) }
        reject { BackupVideoManifest.decode(manifest(e).put("version", 2), rows) }
        reject { BackupVideoManifest.decode(manifest(e, e), rows) }
        reject { BackupVideoManifest.decode(manifest(JSONObject(e.toString()).put("message_id", "missing")), rows) }
        val second = user.copy(id = MessageId("second"))
        val twoRows = JSONArray().put(row(user)).put(row(second))
        reject { BackupVideoManifest.decode(manifest(entry(second, listOf(reference("second"))), e), twoRows) }
    }

    @Test fun crossConversationRunAndHistoricalSegmentTamperingCannotRebindAssets() {
        val refs = listOf(reference("supplement"))
        val history = user.copy(id = MessageId("assistant"), role = "assistant", historySegments = listOf(
            MessageHistorySegment(user.id, "user", user.blocks),
            MessageHistorySegment(MessageId("assistant"), "assistant", user.blocks),
            MessageHistorySegment(MessageId("supplement"), "user", user.blocks)))
        val manifest = manifest(entry(history, refs))
        assertEquals(refs, BackupVideoManifest.decode(manifest, JSONArray().put(row(history))).references)
        reject { BackupVideoManifest.decode(manifest, JSONArray().put(row(history).put("conversation_id", "foreign"))) }
        reject { BackupVideoManifest.decode(manifest, JSONArray().put(row(history).put("run_id", "foreign"))) }
        val changed = row(history)
        changed.getJSONArray("history_segments").getJSONObject(2).put("message_id", "foreign")
        reject { BackupVideoManifest.decode(manifest, JSONArray().put(changed)) }
    }

    @Test fun repeatedHistoryOwnerMustKeepFullMetadataAndOrder() {
        val refs = listOf(reference(n = 1), reference(n = 2))
        val history = user.copy(id = MessageId("assistant"), role = "assistant", historySegments = listOf(
            MessageHistorySegment(user.id, "user", user.blocks),
            MessageHistorySegment(MessageId("assistant"), "assistant", user.blocks)))
        val rows = JSONArray().put(row(user)).put(row(history))
        assertEquals(refs, BackupVideoManifest.decode(
            manifest(entry(user, refs), entry(history, refs)), rows).references)
        reject { BackupVideoManifest.decode(manifest(entry(user, refs), entry(history, refs.reversed())), rows) }
        reject { BackupVideoManifest.decode(manifest(entry(user, refs),
            entry(history, listOf(refs[0].copy(durationMs = 2345), refs[1]))), rows) }
    }

    @Test fun manifestIsBoundedAndLegacyTextAndImagePackagesMayOmitIt() {
        assertTrue(BackupVideoManifest.decode(null, JSONArray().put(row(user))).references.isEmpty())
        reject { BackupVideoManifest.decode(manifest().put("padding", "x".repeat(BackupVideoManifest.MAX_MANIFEST_BYTES)), JSONArray()) }
        val bad = JSONObject(entry(user, listOf(reference())).toString())
        val payload = JSONObject(bad.getString("payload"))
        payload.getJSONArray("inputs").getJSONObject(0).getJSONArray("references").getJSONObject(0).put("width", 33)
        bad.put("payload", MessageVideoInputCodec.encode(user, MessageVideoInputCodec.decode(user, payload.toString())))
        // Structurally valid alternate metadata must remain bound to the asset store at restore.
        val altered = BackupVideoManifest.decode(manifest(bad), JSONArray().put(row(user)))
        assertEquals(33, altered.references.single().width)
        reject { BackupVideoManifest.verify(altered.references.single().copy(bytes = 4), bytes) }
    }
}
