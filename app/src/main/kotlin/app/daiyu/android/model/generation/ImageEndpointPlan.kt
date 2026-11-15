package app.daiyu.android.model.generation

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Transport is selected by protocol, never by model name or gateway brand. */
internal object ImageEndpointPlan {
    enum class Kind { GENERATIONS, EDITS_MULTIPART, EDITS_JSON, GENERATIONS_IMAGE, NOVELAI }
    data class Plan(val kind: Kind, val body: JSONObject, val options: ImageGenerationOptions, val expectedSize: String? = null) {
        fun url(baseUrl: String): String {
            val base = baseUrl.trim().toHttpUrl()
            require(base.query == null && base.fragment == null && base.username.isEmpty() && base.password.isEmpty()) {
                "生图地址不能包含查询参数、片段或内嵌认证信息。"
            }
            val path = base.encodedPath.trimEnd('/')
            val target = if (kind == Kind.NOVELAI) {
                if (path.isEmpty()) "/ai/generate-image" else path
            } else {
                val root = path.removeSuffix("/images/generations").removeSuffix("/images/edits")
                if (root.endsWith("/chat/completions") || root.endsWith("/responses") || root.endsWith("/messages"))
                    ImageGenerationOptions.invalid("请配置 Images 基础地址，不能向文本协议端点发送生图参数。")
                root + if (kind in setOf(Kind.EDITS_MULTIPART, Kind.EDITS_JSON)) "/images/edits" else "/images/generations"
            }
            return base.newBuilder().encodedPath(target).build().toString()
        }
    }

    fun create(prepared: ImageRequestParameters.Prepared, imageCount: Int, hasMask: Boolean): Plan {
        require(imageCount >= 0)
        if (hasMask && imageCount != 1) ImageGenerationOptions.invalid("遮罩编辑需要且仅支持一张参考图。")
        if (prepared.endpoint == "novelai_native") {
            if (imageCount != 0 || hasMask) ImageGenerationOptions.invalid("当前 NovelAI 原生路径仅支持文生图；不会丢弃参考图或遮罩。")
            val body = NovelAiImageProtocol.request(prepared)
            val params = body.getJSONObject("parameters")
            return Plan(Kind.NOVELAI, body, prepared.options.copy(size = "${params.getInt("width")}x${params.getInt("height")}"))
        }
        val kind = if (imageCount == 0) Kind.GENERATIONS else when (prepared.editProtocol) {
            "multipart" -> Kind.EDITS_MULTIPART
            "json_image_url" -> Kind.EDITS_JSON
            "generations_image" -> Kind.GENERATIONS_IMAGE
            else -> error("未识别的编辑协议")
        }
        if (kind in setOf(Kind.EDITS_JSON, Kind.GENERATIONS_IMAGE) && imageCount != 1)
            ImageGenerationOptions.invalid("该 JSON 编辑协议仅支持一张参考图，不会拼图或丢弃额外图片。")
        if (hasMask && kind == Kind.EDITS_JSON)
            ImageGenerationOptions.invalid("json_image_url 未定义遮罩协议；请选择 multipart 或 generations_image。")
        if (listOf("image", "images", "mask").any(prepared.body::has))
            ImageGenerationOptions.invalid("参考图和遮罩应通过图片输入传入，不能由额外请求体覆盖。")
        return Plan(kind, JSONObject(prepared.body.toString()), prepared.options, prepared.expectedSize)
    }
}
