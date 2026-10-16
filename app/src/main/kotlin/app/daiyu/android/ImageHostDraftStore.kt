package app.daiyu.android

import android.content.SharedPreferences
import app.daiyu.android.media.ImageAttachment
import app.daiyu.android.media.ImageAttachmentOwner
import org.json.JSONArray
import org.json.JSONObject

/** Only owned draft references and fences; never picker URIs, model credentials or grants.
 * A process-lost pending send restores as UNKNOWN, with the SAME journal owner and revision.
 */
internal class ImageHostDraftStore(private val preferences: SharedPreferences) {
    data class Draft(val conversation: String?, val owner: ImageAttachmentOwner, val revision: Long,
        val images: List<ImageAttachment>, val fenced: Boolean)

    fun read(): List<Draft> {
        val value = preferences.getString(KEY, null) ?: return emptyList()
        val rows = JSONArray(value)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val owner = ImageAttachmentOwner(row.getString("conversationId"), row.getString("draftId"))
            val images = row.getJSONArray("images")
            Draft(row.optString("selection").takeIf { it.isNotEmpty() }, owner, row.getLong("revision"),
                (0 until images.length()).map { imageIndex ->
                    val image = images.getJSONObject(imageIndex)
                    ImageAttachment(owner, image.getString("id"), image.getString("mime"),
                        image.getInt("bytes"), image.getString("sha256"), image.getInt("width"), image.getInt("height"))
                }, row.getBoolean("fenced"))
        }.also { rowsRead -> check(rowsRead.map { it.conversation }.distinct().size == rowsRead.size) }
    }

    fun write(drafts: List<Draft>): Boolean = runCatching {
        val rows = JSONArray()
        drafts.forEach { draft ->
            val images = JSONArray()
            draft.images.forEach { image -> images.put(JSONObject()
                .put("id", image.id).put("mime", image.mime).put("bytes", image.bytes)
                .put("sha256", image.sha256).put("width", image.width).put("height", image.height)) }
            rows.put(JSONObject().put("selection", draft.conversation.orEmpty())
                .put("conversationId", draft.owner.conversationId).put("draftId", draft.owner.draftId)
                .put("revision", draft.revision).put("images", images).put("fenced", draft.fenced))
        }
        preferences.edit().putString(KEY, rows.toString()).commit()
    }.getOrDefault(false)

    private companion object { const val KEY = "image-host-owned-drafts-v1" }
}
