package app.daiyu.android

import android.content.SharedPreferences
import app.daiyu.android.domain.MessageId
import app.daiyu.android.domain.RunId
import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentOwner
import org.json.JSONArray
import org.json.JSONObject

/** Only owned draft references, immutable attempted content and identity; never picker URIs,
 * provider credentials or grants. Legacy fences without a frozen identity remain UNKNOWN.
 */
internal class ImageHostDraftStore(private val preferences: SharedPreferences) {
    data class Draft(val conversation: String?, val owner: ImageAttachmentOwner, val revision: Long,
        val images: List<ImageAttachment>, val fenced: Boolean, val frozen: FrozenImageSend? = null,
        val editorReplaced: Boolean = false, val generation: Long = 0, val retired: Boolean = false)

    private fun image(owner: ImageAttachmentOwner, row: JSONObject) = ImageAttachment(owner,
        row.getString("id"), row.getString("mime"), row.getInt("bytes"), row.getString("sha256"),
        row.getInt("width"), row.getInt("height"))
    private fun image(image: ImageAttachment) = JSONObject()
        .put("id", image.id).put("mime", image.mime).put("bytes", image.bytes)
        .put("sha256", image.sha256).put("width", image.width).put("height", image.height)

    fun read(): List<Draft> {
        val value = preferences.getString(KEY, null) ?: return emptyList()
        val rows = JSONArray(value)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val owner = ImageAttachmentOwner(row.getString("conversationId"), row.getString("draftId"))
            val images = row.getJSONArray("images")
            val revision = row.getLong("revision")
            val ready = (0 until images.length()).map { image(owner, images.getJSONObject(it)) }
            val frozen = row.optJSONObject("attempt")?.let { attempt ->
                val parts = attempt.getJSONArray("parts")
                FrozenImageSend(owner, revision, attempt.getString("text"),
                    (0 until parts.length()).map { partIndex ->
                        val part = parts.getJSONObject(partIndex)
                        when (part.getString("type")) {
                            "text" -> ImageSendPart.Text(part.getString("text"))
                            "image" -> ImageSendPart.Image(image(owner, part.getJSONObject("image")))
                            else -> error("invalid_image_part")
                        }
                    }, ImageSendIdentity(MessageId(attempt.getString("user")),
                        MessageId(attempt.getString("assistant")), RunId(attempt.getString("run"))))
                    .also { check(it.parts.filterIsInstance<ImageSendPart.Image>().map { part -> part.attachment } == ready) }
            }
            Draft(row.optString("selection").takeIf { it.isNotEmpty() }, owner, revision,
                ready, row.getBoolean("fenced"), frozen, row.optBoolean("editorReplaced", false),
                row.optLong("generation", 0), row.optBoolean("retired", false))
                .also {
                    check(frozen == null || it.fenced)
                    check(it.generation >= 0)
                    check(!it.retired || !it.fenced && frozen == null && ready.isEmpty())
                }
        }.also { rowsRead -> check(rowsRead.map { it.conversation }.distinct().size == rowsRead.size) }
    }

    fun write(drafts: List<Draft>): Boolean = runCatching {
        val rows = JSONArray()
        drafts.forEach { draft ->
            val images = JSONArray()
            draft.images.forEach { images.put(image(it)) }
            val row = JSONObject().put("selection", draft.conversation.orEmpty())
                .put("conversationId", draft.owner.conversationId).put("draftId", draft.owner.draftId)
                .put("revision", draft.revision).put("images", images).put("fenced", draft.fenced)
                .put("editorReplaced", draft.editorReplaced)
                .put("generation", draft.generation).put("retired", draft.retired)
            check(draft.generation >= 0)
            check(!draft.retired || !draft.fenced && draft.frozen == null && draft.images.isEmpty())
            draft.frozen?.let { frozen ->
                check(frozen.owner == draft.owner && frozen.revision == draft.revision && draft.fenced)
                val parts = JSONArray()
                frozen.parts.forEach { part -> parts.put(when (part) {
                    is ImageSendPart.Text -> JSONObject().put("type", "text").put("text", part.text)
                    is ImageSendPart.Image -> JSONObject().put("type", "image").put("image", image(part.attachment))
                }) }
                row.put("attempt", JSONObject().put("text", frozen.text).put("parts", parts)
                    .put("user", frozen.identity.userId.value).put("assistant", frozen.identity.assistantId.value)
                    .put("run", frozen.identity.runId.value))
            }
            rows.put(row)
        }
        preferences.edit().putString(KEY, rows.toString()).commit()
    }.getOrDefault(false)

    private companion object { const val KEY = "image-host-owned-drafts-v1" }
}
