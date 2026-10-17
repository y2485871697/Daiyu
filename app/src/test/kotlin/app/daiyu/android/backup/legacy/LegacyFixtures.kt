package app.daiyu.android.backup.legacy

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Entirely synthetic documents. No export, fixture or filesystem from an actual user is used. */
internal object LegacyFixtures {
    fun conversation(id: String = "source") = JSONObject().put("id", id).put("title", "Synthetic $id")
        .put("thinkingEnabled", false).put("createdAt", 12L).put("updatedAt", 34L).put("historyJson", "[]")
        .put("appliedRuntimeRunIdsJson", "[]")
    fun message(id: String = "ui-1", owner: String = "source", type: String = "user", index: Int = 0) = JSONObject()
        .put("id", id).put("conversationId", owner).put("sortIndex", index).put("type", type).put("content", "Synthetic $type record")
        .put("imagesJson", "[]").put("toolsJson", "[]").put("generatedAtMillis", 900L)
    fun single(version: Int = 3, owner: String = "source") = JSONObject().put("format", "eta-conversation")
        .put("schemaVersion", version).put("exportedAt", 1000L).put("conversation", conversation(owner))
        .put("messages", JSONArray().put(message(owner = owner))).put("attachmentCount", 0).put("attachments", JSONArray())
    fun full(version: Int = 4) = JSONObject().put("format", "eta-backup").put("schemaVersion", version).put("exportedAt", 1000L)
        .put("conversations", JSONArray().put(conversation())).put("messages", JSONArray().put(message()))
        .put("contextCheckpoints", JSONArray()).put("attachmentCount", 0).put("importedFileCount", 0)
    fun bytes(json: JSONObject) = json.toString().toByteArray(Charsets.UTF_8)
    fun zip(manifest: JSONObject, vararg entries: Pair<String, ByteArray>): ByteArray = archive(
        (manifest.getString("format") + ".json") to bytes(manifest), *entries)
    fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()
    fun attached(version: Int = 3, bytes: ByteArray = "ORIGINAL".toByteArray()): Pair<JSONObject, Pair<String, ByteArray>> {
        val ref = "/eta-attachments/id/original.mp4"; val entry = "attachments/imports/id/original.mp4"
        val doc = single(version).put("attachmentCount", 1).put("attachments", JSONArray().put(JSONObject()
            .put("reference", ref).put("entry", entry).put("size", bytes.size).put("sha256", digest(bytes))))
        doc.getJSONArray("messages").getJSONObject(0).put("imagesJson", JSONArray().put(JSONObject().put("kind", "video")
            .put("source", ref).put("preview", "https://invalid.example/preview.jpg")).toString())
        return doc to (entry to bytes)
    }
}
