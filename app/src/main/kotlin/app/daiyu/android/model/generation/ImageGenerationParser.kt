package app.daiyu.android.model.generation

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object ImageGenerationParser {
    data class ImageRef(
        val bytes: ByteArray? = null,
        val url: String? = null,
        val mimeType: String = "image/png",
    ) {
        val hasPayload: Boolean
            get() = bytes != null || !url.isNullOrBlank()
    }

    data class Parsed(
        val images: List<ImageRef>,
        val text: String = "",
    )

    fun parse(body: String): Parsed {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return Parsed(emptyList())
        val root = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
        if (root == null) {
            return Parsed(extractMarkdownImages(trimmed), extractPlainText(trimmed))
        }
        val images = ArrayList<ImageRef>()
        val texts = ArrayList<String>()
        collect(root, images, texts, keyHint = "")
        val unique = dedupe(images)
        val text = texts
            .map { it.trim() }
            .filter { it.isNotBlank() && !looksLikeImagePayload(it) }
            .distinct()
            .joinToString("\n\n")
        if (unique.isEmpty()) {
            val markdownImages = extractMarkdownImages(trimmed)
            if (markdownImages.isNotEmpty()) {
                return Parsed(markdownImages, text)
            }
        }
        return Parsed(unique, text)
    }

    fun errorMessage(body: String, httpCode: Int): String {
        val trimmed = body.trim()
        val obj = runCatching { JSONObject(trimmed) }.getOrNull()
        val nested = obj?.optJSONObject("error")
        val message = listOfNotNull(
            nested?.optString("message")?.takeIf { it.isNotBlank() && it != "null" },
            nested?.optString("code")?.takeIf { it.isNotBlank() && it != "null" },
            obj?.optString("error")?.takeIf { it.isNotBlank() && it != "null" && !it.startsWith("{") },
            obj?.optString("message")?.takeIf { it.isNotBlank() && it != "null" },
            obj?.optString("msg")?.takeIf { it.isNotBlank() && it != "null" },
            obj?.optString("detail")?.takeIf { it.isNotBlank() && it != "null" },
        ).firstOrNull()
        val compact = (message ?: trimmed.replace('\n', ' ').replace('\r', ' '))
            .trim()
            .ifBlank { "HTTP $httpCode" }
        return if (compact.length > 600) compact.take(600) + "..." else compact
    }

    fun markdown(paths: List<String>, text: String = ""): String {
        val images = paths
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n") { "![generated]($it)" }
        return listOf(text.trim(), images).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    fun extensionForMime(mimeType: String): String = when (mimeType.substringBefore(';').trim().lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        else -> "png"
    }

    private fun collect(value: Any?, images: MutableList<ImageRef>, texts: MutableList<String>, keyHint: String) {
        when (value) {
            null, JSONObject.NULL -> Unit
            is JSONObject -> {
                value.keys().forEach { key ->
                    collect(value.opt(key), images, texts, key)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collect(value.opt(index), images, texts, keyHint)
                }
            }
            is String -> consumeString(value, images, texts, keyHint)
            else -> Unit
        }
    }

    private fun consumeString(raw: String, images: MutableList<ImageRef>, texts: MutableList<String>, keyHint: String) {
        val value = raw.trim()
        if (value.isBlank()) return
        val key = keyHint.lowercase()
        when {
            value.startsWith("data:image/", ignoreCase = true) -> {
                decodeDataUrl(value)?.let(images::add)
            }
            key.contains("b64") || key.contains("base64") || key == "b64_json" || key == "b64" -> {
                decodeBase64Image(value)?.let(images::add)
            }
            isImageKey(key) && isDirectImageSource(value) -> {
                images += ImageRef(url = value, mimeType = mimeFromSource(value))
            }
            isImageKey(key) -> {
                decodeBase64Image(value)?.let(images::add)
            }
            key == "content" || key == "text" || key == "revised_prompt" || key == "caption" -> {
                extractMarkdownImages(value).forEach(images::add)
                val stripped = stripMarkdownImages(value).trim()
                if (stripped.isNotBlank()) texts += stripped
            }
            else -> {
                extractMarkdownImages(value).forEach(images::add)
            }
        }
    }

    private fun isImageKey(key: String): Boolean =
        key == "url" ||
            key == "image_url" ||
            key.endsWith("_url") ||
            key == "image" ||
            key == "src"

    private fun isDirectImageSource(value: String): Boolean {
        val source = value.trim()
        return source.startsWith("data:image/", ignoreCase = true) ||
            source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true) ||
            source.startsWith("content://", ignoreCase = true) ||
            source.startsWith("file://", ignoreCase = true) ||
            (source.startsWith("/") && source.contains('.'))
    }

    private fun looksLikeImagePayload(value: String): Boolean =
        value.startsWith("data:image/", ignoreCase = true) ||
            (value.length > 80 && value.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it.isWhitespace() })

    private fun extractMarkdownImages(text: String): List<ImageRef> {
        val out = ArrayList<ImageRef>()
        MARKDOWN_IMAGE.findAll(text).forEach { match ->
            val source = match.groupValues[1].trim().trim('<', '>').trim()
            if (source.startsWith("data:image/", ignoreCase = true)) {
                decodeDataUrl(source)?.let(out::add)
            } else if (isDirectImageSource(source)) {
                out += ImageRef(url = source, mimeType = mimeFromSource(source))
            }
        }
        HTML_IMAGE.findAll(text).forEach { match ->
            val source = match.groupValues[1].trim()
            if (source.startsWith("data:image/", ignoreCase = true)) {
                decodeDataUrl(source)?.let(out::add)
            } else if (isDirectImageSource(source)) {
                out += ImageRef(url = source, mimeType = mimeFromSource(source))
            }
        }
        return out
    }

    private fun extractPlainText(text: String): String = stripMarkdownImages(text).trim()

    private fun stripMarkdownImages(text: String): String =
        MARKDOWN_IMAGE.replace(HTML_IMAGE.replace(text, ""), "").trim()

    private fun decodeDataUrl(value: String): ImageRef? {
        val marker = value.indexOf("base64,", ignoreCase = true)
        if (marker < 0) return null
        val mime = value.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
        val bytes = decodeBase64(value.substring(marker + "base64,".length)) ?: return null
        if (bytes.isEmpty()) return null
        return ImageRef(bytes = bytes, mimeType = mime)
    }

    private fun decodeBase64Image(value: String): ImageRef? {
        val bytes = decodeBase64(value) ?: return null
        if (bytes.isEmpty()) return null
        return ImageRef(bytes = bytes, mimeType = "image/png")
    }

    private fun decodeBase64(value: String): ByteArray? {
        val cleaned = value.trim().replace("\\s".toRegex(), "")
        if (cleaned.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(cleaned) }.getOrElse {
            runCatching { Base64.getMimeDecoder().decode(value) }.getOrNull()
        }
    }

    private fun mimeFromSource(source: String): String {
        if (source.startsWith("data:image/", ignoreCase = true)) {
            return source.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
        }
        val name = source.substringAfterLast('/').substringBefore('?').lowercase()
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".gif") -> "image/gif"
            else -> "image/png"
        }
    }

    private fun dedupe(images: List<ImageRef>): List<ImageRef> {
        val seenUrls = HashSet<String>()
        val out = ArrayList<ImageRef>()
        images.forEach { image ->
            if (!image.hasPayload) return@forEach
            val url = image.url?.trim().orEmpty()
            if (url.isNotEmpty()) {
                if (!seenUrls.add(url)) return@forEach
            }
            out += image
        }
        return out
    }

    private val MARKDOWN_IMAGE = Regex("""!\[[^\]]*]\(\s*<?([^)>\s]+)>?\s*\)""")
    private val HTML_IMAGE = Regex("""<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
}
