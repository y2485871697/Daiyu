package app.daiyu.android.model.generation

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object VideoGenerationParser {
    data class VideoRef(
        val bytes: ByteArray? = null,
        val url: String? = null,
        val mimeType: String = "video/mp4",
    ) {
        val hasPayload: Boolean
            get() = bytes != null || !url.isNullOrBlank()
    }

    data class Parsed(
        val videos: List<VideoRef>,
        val text: String = "",
        val taskId: String? = null,
        val status: String? = null,
        val failed: Boolean = false,
        val error: String? = null,
    )

    fun parse(body: String): Parsed {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return Parsed(emptyList())
        val root = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
        if (root == null) {
            return Parsed(extractMarkdownVideos(trimmed), extractPlainText(trimmed))
        }
        val videos = ArrayList<VideoRef>()
        val texts = ArrayList<String>()
        val taskIds = ArrayList<String>()
        val statuses = ArrayList<String>()
        val errors = ArrayList<String>()
        collect(root, videos, texts, taskIds, statuses, errors, keyHint = "", depth = 0)
        val status = statuses.firstOrNull { it.isNotBlank() }?.lowercase()
        val failed = status in FAILED_STATUSES || errors.isNotEmpty()
        val text = texts
            .map { it.trim() }
            .filter { it.isNotBlank() && !looksLikeVideoPayload(it) }
            .distinct()
            .joinToString("\n\n")
        val unique = dedupe(videos)
        if (unique.isEmpty()) {
            val markdown = extractMarkdownVideos(trimmed)
            if (markdown.isNotEmpty()) {
                return Parsed(
                    videos = markdown,
                    text = text,
                    taskId = taskIds.firstOrNull(),
                    status = status,
                    failed = failed,
                    error = errors.firstOrNull(),
                )
            }
        }
        return Parsed(
            videos = unique,
            text = text,
            taskId = taskIds.firstOrNull(),
            status = status,
            failed = failed,
            error = errors.firstOrNull(),
        )
    }

    fun errorMessage(body: String, httpCode: Int): String {
        val parsed = parse(body)
        val compact = (parsed.error ?: parsed.text.takeIf { it.isNotBlank() } ?: body.trim().replace('\n', ' '))
            .ifBlank { "HTTP $httpCode" }
        return if (compact.length > 600) compact.take(600) + "..." else compact
    }

    fun markdown(paths: List<String>, text: String = ""): String {
        val videos = paths
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n") { "![generated]($it)" }
        return listOf(text.trim(), videos).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    fun extensionForMime(mimeType: String): String =
        GenerationVideoCodec.extensionForMime(mimeType)

    fun isTerminalSuccess(status: String?): Boolean =
        status?.lowercase() in SUCCESS_STATUSES

    fun isInProgress(status: String?): Boolean =
        status?.lowercase() in IN_PROGRESS_STATUSES

    private fun collect(
        value: Any?,
        videos: MutableList<VideoRef>,
        texts: MutableList<String>,
        taskIds: MutableList<String>,
        statuses: MutableList<String>,
        errors: MutableList<String>,
        keyHint: String,
        depth: Int,
    ) {
        if (depth > 8) return
        when (value) {
            null, JSONObject.NULL -> Unit
            is JSONObject -> {
                val id = value.optString("id").ifBlank { value.optString("task_id").ifBlank { value.optString("taskId") } }
                val status = value.optString("status").ifBlank {
                    value.optString("state").ifBlank { value.optString("task_status") }
                }
                if (status.isNotBlank()) statuses += status
                if (id.isNotBlank() && looksLikeTask(value, status)) {
                    taskIds += id
                }
                val error = value.optJSONObject("error")?.optString("message")
                    ?.ifBlank { value.optString("error") }
                    ?: value.optString("message").takeIf { keyHint == "error" }
                if (!error.isNullOrBlank() && error != "null" && looksLikeError(value, status)) {
                    errors += error
                }
                value.keys().forEach { key ->
                    collect(value.opt(key), videos, texts, taskIds, statuses, errors, key, depth + 1)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collect(value.opt(index), videos, texts, taskIds, statuses, errors, keyHint, depth + 1)
                }
            }
            is String -> consumeString(value, videos, texts, keyHint)
            else -> Unit
        }
    }

    private fun consumeString(
        raw: String,
        videos: MutableList<VideoRef>,
        texts: MutableList<String>,
        keyHint: String,
    ) {
        val value = raw.trim()
        if (value.isBlank()) return
        val key = keyHint.lowercase()
        when {
            value.startsWith("data:video/", ignoreCase = true) -> decodeDataUrl(value)?.let(videos::add)
            key.contains("b64") || key.contains("base64") || key == "b64_json" -> {
                decodeBase64Video(value)?.let(videos::add)
            }
            isVideoKey(key) && isDirectVideoSource(value) -> {
                videos += VideoRef(url = value, mimeType = mimeFromSource(value))
            }
            key == "video_url" && (value.startsWith("http://") || value.startsWith("https://")) -> {
                videos += VideoRef(url = value, mimeType = mimeFromSource(value))
            }
            isVideoKey(key) -> decodeBase64Video(value)?.let(videos::add)
            key == "content" || key == "text" || key == "caption" || key == "revised_prompt" -> {
                extractMarkdownVideos(value).forEach(videos::add)
                val stripped = stripMarkdownImages(value).trim()
                if (stripped.isNotBlank()) texts += stripped
            }
            else -> extractMarkdownVideos(value).forEach(videos::add)
        }
    }

    private fun looksLikeTask(obj: JSONObject, status: String): Boolean {
        if (obj.optString("object").equals("video", ignoreCase = true)) return true
        if (status.isNotBlank()) return true
        return obj.has("task_id") || obj.has("taskId")
    }

    private fun looksLikeError(obj: JSONObject, status: String): Boolean {
        if (status.lowercase() in FAILED_STATUSES) return true
        return obj.has("error") && obj.opt("error") != JSONObject.NULL
    }

    private fun isVideoKey(key: String): Boolean =
        key == "url" ||
            key == "video_url" ||
            key == "video" ||
            key.endsWith("_url") ||
            key == "src" ||
            key == "output" ||
            key == "content_url"

    private fun isDirectVideoSource(value: String): Boolean {
        val source = value.trim()
        if (source.startsWith("data:video/", ignoreCase = true)) return true
        if (source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)) {
            val path = source.substringBefore('?').lowercase()
            return GenerationVideoCodec.FILE_EXTENSIONS.any { path.endsWith(".$it") } ||
                "video" in path ||
                path.contains("/videos/")
        }
        if (source.startsWith("/") && source.contains('.')) {
            return GenerationVideoCodec.isVideoSource(source)
        }
        return false
    }

    private fun looksLikeVideoPayload(value: String): Boolean =
        value.startsWith("data:video/", ignoreCase = true)

    private fun extractMarkdownVideos(text: String): List<VideoRef> {
        val out = ArrayList<VideoRef>()
        MARKDOWN_IMAGE.findAll(text).forEach { match ->
            val source = match.groupValues[1].trim().trim('<', '>').trim()
            if (source.startsWith("data:video/", ignoreCase = true)) {
                decodeDataUrl(source)?.let(out::add)
            } else if (isDirectVideoSource(source)) {
                out += VideoRef(url = source, mimeType = mimeFromSource(source))
            }
        }
        return out
    }

    private fun extractPlainText(text: String): String = stripMarkdownImages(text).trim()

    private fun stripMarkdownImages(text: String): String =
        MARKDOWN_IMAGE.replace(text, "").trim()

    private fun decodeDataUrl(value: String): VideoRef? {
        val marker = value.indexOf("base64,", ignoreCase = true)
        if (marker < 0) return null
        val mime = value.substringAfter("data:").substringBefore(';').ifBlank { "video/mp4" }
        val bytes = decodeBase64(value.substring(marker + "base64,".length)) ?: return null
        if (bytes.isEmpty()) return null
        return VideoRef(bytes = bytes, mimeType = mime)
    }

    private fun decodeBase64Video(value: String): VideoRef? {
        val bytes = decodeBase64(value) ?: return null
        if (bytes.isEmpty()) return null
        val mime = GenerationVideoCodec.sniffMime(bytes) ?: return null
        return VideoRef(bytes = bytes, mimeType = mime)
    }

    private fun decodeBase64(value: String): ByteArray? {
        val cleaned = value.trim().replace("\\s".toRegex(), "")
        if (cleaned.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(cleaned) }.getOrElse {
            runCatching { Base64.getMimeDecoder().decode(value) }.getOrNull()
        }
    }

    private fun mimeFromSource(source: String): String {
        if (source.startsWith("data:video/", ignoreCase = true)) {
            return source.substringAfter("data:").substringBefore(';').ifBlank { "video/mp4" }
        }
        return when (GenerationVideoCodec.extensionForMime("video/mp4", source.substringAfterLast('/'))) {
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            else -> "video/mp4"
        }
    }

    private fun dedupe(videos: List<VideoRef>): List<VideoRef> {
        val seenUrls = HashSet<String>()
        val out = ArrayList<VideoRef>()
        videos.forEach { video ->
            if (!video.hasPayload) return@forEach
            val url = video.url?.trim().orEmpty()
            if (url.isNotEmpty() && !seenUrls.add(url)) return@forEach
            out += video
        }
        return out
    }

    private val MARKDOWN_IMAGE = Regex("""!\[[^\]]*]\(\s*<?([^)>\s]+)>?\s*\)""")
    private val SUCCESS_STATUSES = setOf("completed", "complete", "succeeded", "succeed", "success", "done", "finished")
    private val IN_PROGRESS_STATUSES = setOf(
        "queued", "queueing", "pending", "submitted", "processing",
        "in_progress", "running", "generating", "started",
    )
    private val FAILED_STATUSES = setOf("failed", "error", "cancelled", "canceled", "expired")
}
