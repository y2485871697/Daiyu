package app.daiyu.android.model.generation

import org.json.JSONObject
import kotlin.math.abs

/** Per-call values, never persisted into a model's shared configuration. */
internal data class ImageGenerationOptions(
    val aspectRatio: String? = null,
    val resolution: String? = null,
    val size: String? = null,
    val count: Int? = null,
    val quality: String? = null,
    val responseFormat: String? = null,
    val concurrency: Int? = null,
) {
    val isEmpty get() = this == ImageGenerationOptions()

    fun toJson() = JSONObject().also { json ->
        aspectRatio?.let { json.put("aspect_ratio", it) }; resolution?.let { json.put("resolution", ImageResolutionTier.normalize(it)) }
        size?.let { json.put("size", it) }; count?.let { json.put("n", it) }
        quality?.let { json.put("quality", it) }; responseFormat?.let { json.put("response_format", it) }
        concurrency?.let { json.put("concurrency", it) }
    }

    /** Apply after configured defaults. Compatibility belongs to the endpoint, not model names. */
    @Suppress("UNUSED_PARAMETER")
    fun applyTo(body: JSONObject, model: String = "") {
        val normalized = fromJson(toJson())
        if (normalized != this) { normalized.applyTo(body); return }
        validateShape()
        // A per-call shape replaces conflicting configured shape defaults, never other extras.
        if (size != null) {
            body.remove("aspect_ratio")
            body.remove("resolution")
        } else if (aspectRatio != null || resolution != null) {
            body.remove("size")
        }
        val values = toJson()
        values.keys().forEach { key -> body.put(key, values.get(key)) }
    }

    fun validateShape() {
        if (aspectRatio != null && aspectRatio != "auto" && size == "auto")
            invalid("指定比例不能与自动尺寸同时使用。")
        if (aspectRatio != null && size != null && aspectRatio != "auto" && size != "auto") {
            val (w, h) = dimensions(size)
            if (!matchesRatio(w, h, aspectRatio)) invalid("aspect_ratio 与 size 冲突。")
        }
    }

    /** Validate actual decoded dimensions, not a provider's claimed metadata. Never resize or retry. */
    fun dimensionReport(width: Int, height: Int, expectedSize: String? = null): String {
        val requested = listOfNotNull(aspectRatio?.let { "比例 $it" }, resolution?.let { "${ImageResolutionTier.label(it)}分辨率" }, size?.let { "尺寸 $it" }).joinToString("，")
        if (width <= 0 || height <= 0) return "IMAGE_DIMENSIONS_UNVERIFIED：无法解码实际宽高" + if (requested.isBlank()) "。" else "；请求 $requested，不能声称符合要求。"
        val problems = mutableListOf<String>()
        if (aspectRatio != null && aspectRatio != "auto" && !matchesRatio(width, height, aspectRatio)) problems += "宽高比不符合 $aspectRatio"
        if (size != null && size != "auto" && dimensions(size) != (width to height)) problems += "像素尺寸不符合 $size"
        if (expectedSize != null && dimensions(expectedSize) != (width to height))
            problems += "与该端点已验证档位输出 $expectedSize 不符"
        val tierUnverified = resolution != null && size == null && expectedSize == null
        return (if (problems.isEmpty()) "实际尺寸" else "IMAGE_DIMENSIONS_MISMATCH") + "：${width}x$height" +
            (if (requested.isBlank()) "" else "；请求 $requested") +
            (if (problems.isEmpty()) "。" else "；${problems.joinToString("，")}，不能作为符合要求的结果交付。") +
            (if (tierUnverified) "IMAGE_RESOLUTION_UNVERIFIED：端点未提供该档位可验证的像素映射，不得仅凭比例正确宣称分辨率达标。"
                else if (resolution == null) "" else "分辨率档位是请求意图，不等同于精确像素承诺；发送字段见参数摘要。")
    }

    companion object {
        val aspectRatios = listOf("auto", "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "2:1", "1:2", "19.5:9", "9:19.5", "20:9", "9:20", "21:9", "5:2")
        fun fromJson(json: JSONObject): ImageGenerationOptions {
            val keys = setOf("aspect_ratio", "resolution", "size", "n", "quality", "response_format", "concurrency")
            if (json.keys().asSequence().any { it !in keys }) invalid("image_options 含不支持的字段。")
            fun text(key: String): String? {
                if (!json.has(key)) return null
                val value = json.opt(key) as? String ?: invalid("image_options.$key 必须是字符串。")
                if (value.length > 40 || value.isBlank()) invalid("image_options.$key 格式无效。")
                return value.trim().lowercase()
            }
            val ratio = text("aspect_ratio")?.replace('：', ':')?.replace(" ", "")
            if (ratio != null && ratio != "auto" && !Regex("[1-9][0-9]{0,3}(?:\\.[0-9]{1,2})?:[1-9][0-9]{0,3}(?:\\.[0-9]{1,2})?").matches(ratio)) invalid("aspect_ratio 必须是正数比例或 auto。")
            val resolution = text("resolution")?.let(ImageResolutionTier::normalize)
            if (resolution != null && !Regex("[a-z0-9][a-z0-9_.-]{0,19}").matches(resolution)) invalid("resolution 格式无效。")
            val size = text("size")?.replace('×', 'x')?.replace(" ", "")
            if (size != null && size != "auto") dimensions(size)
            val count = if (!json.has("n")) null else {
                val number = json.opt("n") as? Number ?: invalid("n 必须是整数。")
                val n = number.toDouble()
                if (n !in 1.0..10.0 || n % 1 != 0.0) invalid("n 必须为 1 至 10 的整数。")
                n.toInt()
            }
            val quality = text("quality")
            if (quality != null && !Regex("[a-z0-9][a-z0-9_.-]{0,19}").matches(quality)) invalid("quality 格式无效。")
            val format = text("response_format")
            if (format != null && format !in setOf("url", "b64_json")) invalid("response_format 仅支持 url、b64_json。")
            val concurrency = if (!json.has("concurrency")) null else {
                val number = json.opt("concurrency") as? Number ?: invalid("concurrency 必须是整数。")
                val n = number.toDouble()
                if (n !in 1.0..8.0 || n % 1 != 0.0) invalid("concurrency 必须为 1 至 8 的整数。")
                n.toInt()
            }
            return ImageGenerationOptions(ratio, resolution, size, count, quality, format, concurrency)
        }
        fun dimensions(size: String): Pair<Int, Int> {
            if (!Regex("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}").matches(size)) invalid("size 必须是 WIDTHxHEIGHT，例如 864x1536。")
            return size.split('x').let { it[0].toInt() to it[1].toInt() }
        }
        private fun matchesRatio(width: Int, height: Int, ratio: String): Boolean {
            val parts = ratio.split(':'); val expected = parts[0].toDouble() / parts[1].toDouble()
            return abs(width.toDouble() / height / expected - 1.0) <= 0.01
        }
        fun invalid(message: String): Nothing = throw ImageGenerationParameterException(message)
    }
}

internal class ImageGenerationParameterException(message: String) : IllegalArgumentException(message)
