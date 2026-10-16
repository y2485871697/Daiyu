package app.daiyu.android

import android.content.SharedPreferences
import app.daiyu.android.media.video.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/** No picker URI, permission grant, preview bytes, text or provider settings enter this journal. */
internal interface VideoDraftPersistence {
    fun read(): List<VideoHostDraftStore.Draft>
    fun write(drafts: List<VideoHostDraftStore.Draft>): Boolean
}

internal class VideoHostDraftStore(private val preferences: SharedPreferences) : VideoDraftPersistence {
    data class Draft(
        val conversation: String?,
        val owner: VideoAttachmentOwner,
        val revision: Long,
        val videos: List<VideoAttachment>,
        val fenced: Boolean,
        // Failed/in-flight reservations restore as failed cards, never as fresh picker capacity.
        val blockedSlots: Int = 0,
        // Exact draft originals awaiting deletion. Committed atomically with removal/acceptance.
        val pendingDeletes: List<VideoAttachment> = emptyList(),
    )

    override fun read(): List<Draft> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        require(raw.length <= 2 * 1024 * 1024)
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        val rows = root.getJSONArray("drafts")
        require(rows.length() <= 512)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            // Missing is corruption; JSON null is the stable, distinct new-conversation slot.
            require(row.has("slot"))
            val slot = if (row.isNull("slot")) null else row.getString("slot")
            val owner = VideoAttachmentOwner(row.getString("conversationId"), row.getString("draftId"))
            fun references(name: String): List<VideoAttachment> {
                // Old v1 journals predate the durable deletion queue.
                val videos = if (name == "pendingDeletes" && !row.has(name)) JSONArray() else row.getJSONArray(name)
                require(videos.length() <= if (name == "videos") MAX_VIDEOS else MAX_PENDING_DELETES)
                return immutableVideos((0 until videos.length()).map { i ->
                    val video = videos.getJSONObject(i)
                    VideoAttachment(owner, video.getString("id"), video.getString("mime"),
                        video.getLong("bytes"), video.getString("sha256"), video.getLong("durationMs"),
                        video.getInt("width"), video.getInt("height"))
                })
            }
            Draft(slot, owner, row.getLong("revision"), references("videos"),
                row.getBoolean("fenced"), row.getInt("blockedSlots"), references("pendingDeletes"))
        }.also(::validate)
    }

    override fun write(drafts: List<Draft>): Boolean = runCatching {
        validate(drafts)
        val rows = JSONArray()
        drafts.forEach { draft ->
            fun references(values: List<VideoAttachment>): JSONArray = JSONArray().also { array ->
                values.forEach { video -> array.put(JSONObject()
                    .put("id", video.id).put("mime", video.mime).put("bytes", video.bytes)
                    .put("sha256", video.sha256).put("durationMs", video.durationMs)
                    .put("width", video.width).put("height", video.height)) }
            }
            val videos = references(draft.videos)
            rows.put(JSONObject().put("slot", draft.conversation ?: JSONObject.NULL)
                .put("conversationId", draft.owner.conversationId).put("draftId", draft.owner.draftId)
                .put("revision", draft.revision).put("videos", videos).put("fenced", draft.fenced)
                .put("blockedSlots", draft.blockedSlots).put("pendingDeletes", references(draft.pendingDeletes)))
        }
        preferences.edit().putString(KEY, JSONObject().put("version", 1).put("drafts", rows).toString()).commit()
    }.getOrDefault(false)

    internal companion object {
        const val KEY = "video-host-owned-drafts-v1"
        const val MAX_VIDEOS = 4
        private const val MAX_PENDING_DELETES = 4096

        fun validate(drafts: List<Draft>) {
            require(drafts.size <= 512)
            require(drafts.map { it.conversation }.distinct().size == drafts.size)
            require(drafts.map { it.owner }.distinct().size == drafts.size)
            drafts.forEach { draft ->
                require(draft.conversation == null || draft.conversation == draft.owner.conversationId)
                require(draft.revision in 0 until Long.MAX_VALUE)
                require(draft.blockedSlots in 0..MAX_VIDEOS)
                require(draft.videos.size + draft.blockedSlots <= MAX_VIDEOS)
                require(draft.pendingDeletes.size <= MAX_PENDING_DELETES)
                val all = draft.videos + draft.pendingDeletes
                // A Ready/UNKNOWN original must never also be authorized for deletion.
                require(all.map { it.id }.distinct().size == all.size)
                require(draft.videos.sumOf { it.bytes } <= 128L * 1024 * 1024)
                all.forEach { video ->
                    require(video.owner == draft.owner && VIDEO_ID.matches(video.id) && VIDEO_HASH.matches(video.sha256))
                    require(video.mime in VIDEO_MIMES && video.bytes in 1..(64L * 1024 * 1024))
                    require(video.durationMs in 1..3_600_000L)
                    require(video.width > 0 && video.height > 0 && video.width.toLong() * video.height <= 40_000_000)
                }
            }
        }
    }
}

internal fun immutableVideos(videos: List<VideoAttachment>): List<VideoAttachment> =
    Collections.unmodifiableList(ArrayList(videos))
