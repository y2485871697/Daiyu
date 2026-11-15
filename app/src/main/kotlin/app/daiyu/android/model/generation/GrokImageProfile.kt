package app.daiyu.android.model.generation

import java.net.URI
import org.json.JSONObject

/** Measured gateway contract; never inferred from a Grok model name alone. */
internal object GrokImageProfile {
    const val PROFILE = "grok_verified_size"
    private val low = mapOf("1:1" to "1024x1024", "3:4" to "768x1024", "9:16" to "576x1024", "16:9" to "1024x576")
    private val high = mapOf("1:1" to "2048x2048", "3:4" to "1536x2048", "9:16" to "1152x2048", "4:3" to "2048x1536", "16:9" to "2048x1152")
    private val actualLow = mapOf("1:1" to "1024x1024", "3:4" to "864x1152", "9:16" to "720x1280", "16:9" to "1280x720")
    private val actualHigh = mapOf("1:1" to "2048x2048", "3:4" to "1776x2368", "9:16" to "1584x2816", "4:3" to "2368x1776", "16:9" to "2816x1584")

    fun applies(baseUrl: String, model: String, body: JSONObject): Boolean {
        if (body.has(ImageRequestParameters.CONFIG_KEY) && body.optJSONObject(ImageRequestParameters.CONFIG_KEY) == null)
            ImageGenerationOptions.invalid("daiyu_image_config 必须是对象。")
        val config = body.optJSONObject(ImageRequestParameters.CONFIG_KEY)
        if (config?.has("profile") == true) {
            val profile = config.getString("profile")
            if (profile !in setOf(PROFILE, "generic")) ImageGenerationOptions.invalid("未知生图 profile。")
            return profile == PROFILE
        }
        // Explicit alternative contracts take priority over an automatic host preset.
        if (config != null && config.length() > 0) return false
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        return uri?.scheme == "https" && uri.host.equals("v1.123336.xyz", ignoreCase = true) && uri.port in listOf(-1, 443) && uri.path.trimEnd('/') in setOf("", "/v1", "/v1/images/generations", "/images/generations") && model == "grok-imagine-image-2.0"
    }

    fun prepare(body: JSONObject, inline: ImageGenerationOptions, explicit: ImageGenerationOptions, imageCount: Int, hasMask: Boolean): ImageRequestParameters.Prepared {
        if (imageCount != 0 || hasMask) ImageGenerationOptions.invalid("该 Grok 中转仅验证了文生图；尚未验证参考图/遮罩编辑，不会丢弃图片后发送。")
        val result = JSONObject(body.toString())
        val config = result.optJSONObject(ImageRequestParameters.CONFIG_KEY)
        if (config != null && config.keys().asSequence().any { it != "profile" })
            ImageGenerationOptions.invalid("Grok 实测 profile 不能与其他字段、尺寸或端点映射混用。")
        result.remove(ImageRequestParameters.CONFIG_KEY); result.remove("daiyu_media_reasoning")
        val shape = JSONObject()
        (ImageRequestParameters.keys + "concurrency").forEach { key -> if (result.has(key)) shape.put(key,result.get(key)) }
        inline.applyTo(shape); explicit.applyTo(shape)
        var options = ImageGenerationOptions.fromJson(shape).also { it.validateShape() }
        if (options.size != null) {
            if (options.resolution != null) ImageGenerationOptions.invalid("不能同时指定精确尺寸和分辨率档位。")
            if (options.size !in low.values && options.size !in high.values)
                ImageGenerationOptions.invalid("该 Grok 中转未验证此精确尺寸，请使用已验证比例及低/高分辨率。")
        } else {
            val tier = options.resolution ?: "low"
            if (tier !in setOf("low", "high")) ImageGenerationOptions.invalid("该 Grok 中转只验证了低/高两档，尚无独立中/超高分辨率；不会静默降档或重发。")
            val ratio = options.aspectRatio ?: "1:1"
            val size = (if(tier == "low") low else high)[ratio]
                ?: ImageGenerationOptions.invalid("该 Grok 中转尚未验证 $ratio 的${ImageResolutionTier.label(tier)}分辨率，不会改成方图。")
            options = options.copy(aspectRatio = ratio, resolution = tier)
            result.put("size", size)
        }
        if (options.size != null) result.put("size",options.size)
        result.remove("aspect_ratio"); result.remove("resolution"); result.remove("concurrency")
        if (listOf("ratio", "aspectRatio", "imageSize").any(result::has))
            ImageGenerationOptions.invalid("Grok 实测路径不能混发其他比例或分辨率字段。")
        listOf("n", "quality", "response_format").forEach { key -> result.remove(key); if(shape.has(key)) result.put(key,shape.get(key)) }
        if(!result.has("n")) result.put("n",1)
        val expected = if (options.size != null) null else (if(options.resolution == "low") actualLow else actualHigh)[options.aspectRatio]
        return ImageRequestParameters.Prepared(result,options,"multipart",
            "Grok 实测端点适配；发送 size=${result.getString("size")}；请求${options.resolution?.let(ImageResolutionTier::label) ?: "精确尺寸"}；已观察输出 ${expected ?: "精确尺寸需以实际返回校验"}；不保证上游稳定，不自动重发。", expectedSize = expected)
    }
}
