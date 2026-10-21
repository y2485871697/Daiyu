package app.daiyu.android.model

import app.daiyu.android.context.TokenTailCompression
import app.daiyu.android.model.images.FrozenMessageImagePlan
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** Measures the final request in local units, never bills base64 or opaque signatures as text. */
internal object RequestInputEstimate {
    internal fun payload(value: JSONObject): String? = when (value.optString("type")) {
        "input_image" -> value.opt("image_url") as? String
        "image_url" -> value.optJSONObject("image_url")?.opt("url") as? String
        "image" -> value.optJSONObject("source")?.takeIf { it.optString("type") == "base64" }?.opt("data") as? String
        else -> null
    }
    fun from(request: JSONObject, images: FrozenMessageImagePlan?): Long {
        val sizes = images?.estimateImageSizes().orEmpty()
        var imageTokens = 0L
        val opaque = setOf("signature", "thoughtSignature", "thought_signature", "encrypted_content", "encryptedContent")
        fun clean(value: Any?, wire: Boolean): Any = when (value) {
            is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(clean(value.opt(i), wire)) }
            is JSONObject -> {
                val type = value.optString("type")
                if (wire && type in setOf("input_image", "image_url", "image")) {
                    val size = requireNotNull(payload(value)?.let(sizes::get)) { "Unrecognized frozen image estimate" }
                    val scale = minOf(1.0, 2048.0 / maxOf(size.first, size.second))
                    imageTokens += maxOf(85L, (ceil(size.first * scale / 32) * ceil(size.second * scale / 32)).toLong())
                    JSONObject()
                } else if (wire && type in setOf("redacted_thinking", "video", "input_video", "video_url")) JSONObject()
                else JSONObject().also { out -> value.keys().forEach { key ->
                    if (!wire || key !in opaque) {
                        val business = (type == "tool_use" && key == "input") ||
                            key in setOf("arguments", "args", "response", "metadata")
                        out.put(key, clean(value.opt(key), wire && !business))
                    }
                } }
            }
            null -> JSONObject.NULL
            else -> value
        }
        val result = JSONObject()
        request.keys().forEach { key -> result.put(key, clean(request.opt(key),
            key in setOf("input", "messages", "contents", "system", "systemInstruction"))) }
        return TokenTailCompression.textTokens(result.toString()) + imageTokens
    }
}
