package app.daiyu.android.model.generation

import app.daiyu.android.delegation.ReasoningEffort
import org.json.JSONObject

/** An explicit endpoint contract, never inferred from a chat model's capabilities or name. */
internal object MediaReasoningSettings {
    const val CONFIG_KEY = "daiyu_media_reasoning"
    enum class Status { SUPPORTED, UNSUPPORTED, INVALID }
    data class Capability(
        val status: Status,
        val efforts: List<ReasoningEffort> = emptyList(),
        val defaultEffort: ReasoningEffort = ReasoningEffort.OFF,
        val field: String = "",
        val values: Map<ReasoningEffort, Any> = emptyMap(),
        val error: String = "",
        val transport: String? = null,
    ) {
        val label get() = when (status) {
            Status.SUPPORTED -> "端点显式配置"
            Status.UNSUPPORTED -> "当前接口未适配"
            Status.INVALID -> "思考配置无效"
        }
        fun effective(requested: ReasoningEffort?): ReasoningEffort = requested ?: defaultEffort
    }
    private val roles = setOf("image_generation", "video_generation")
    private val reserved = setOf("model", "prompt", "messages", "input", "content", "tools", "tool_choice", "stream",
        "image", "images", "mask", "size", "aspect_ratio", "resolution", "n", "concurrency", "daiyu_image_config", CONFIG_KEY,
        "input_reference", "image_url", "duration", "seconds")

    fun resolve(input: JSONObject, role: String): Capability = try {
        val body = JSONObject(input.toString())
        require(role in roles) { "无效媒体职责" }
        if (!body.has(CONFIG_KEY)) Capability(Status.UNSUPPORTED) else {
            val settings = body.getJSONObject(CONFIG_KEY)
            require(settings.keys().asSequence().all { it in roles }) { "未知媒体职责配置" }
            if (!settings.has(role)) Capability(Status.UNSUPPORTED) else {
                val spec = settings.getJSONObject(role)
                require(spec.keys().asSequence().all { it in setOf("field", "values", "default", "transport") }) { "未知思考配置项" }
                val transport = if (spec.has("transport")) spec.get("transport") as? String
                    ?: error("transport 应为字符串") else null
                if (role == "video_generation") require(transport in setOf("videos_json", "videos_multipart", "videos_generations", "video_generations", "ark_contents")) {
                    "视频思考映射需指定 transport，不能自动换端点"
                } else require(transport == null) { "图片传输使用 daiyu_image_config 配置" }
                val field = spec.get("field") as? String ?: error("field 必须为字符串")
                val parts = field.split('.')
                require(parts.size in 1..6 && parts.all { Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(it) } &&
                    parts.first() !in reserved && parts.none { it.startsWith("daiyu_") }) { "思考字段路径无效或覆盖保留字段" }
                require(transport != "videos_multipart" || parts.size == 1) { "视频 multipart 思考映射仅支持顶层标量字段" }
                val values = spec.getJSONObject("values")
                require(values.length() > 0) { "缺少支持的思考档位" }
                val mapped = values.keys().asSequence().associate { key ->
                    val effort = ReasoningEffort.entries.firstOrNull { it.wireValue == key } ?: error("未知思考档位")
                    val value = values.get(key)
                    require(value is String && value.isNotBlank() && value.length <= 100 || value is Boolean ||
                        value is Number && value.toDouble().isFinite()) { "档位值应为非空字符串、布尔或有限数字" }
                    effort to value
                }
                val ordered = ReasoningEffort.entries.filter { it in mapped }
                val default = if (spec.has("default")) {
                    val name = spec.get("default") as? String ?: error("default 应为档位字符串")
                    ordered.firstOrNull { it.wireValue == name } ?: error("默认档位不在支持列表")
                } else ordered.first()
                Capability(Status.SUPPORTED, ordered, default, field, mapped, transport = transport)
            }
        }
    } catch (_: Exception) { Capability(Status.INVALID, error = "媒体思考配置无效，请检查职责、字段路径和档位映射。") }

    fun apply(input: JSONObject, role: String, requested: ReasoningEffort?): JSONObject {
        val capability = resolve(input, role)
        require(capability.status != Status.INVALID) { capability.error }
        if (capability.status == Status.UNSUPPORTED) {
            require(requested == null || requested == ReasoningEffort.OFF || requested == ReasoningEffort.DEFAULT) {
                "当前媒体接口未适配原生思考，不能应用所选档位。"
            }
        }
        val effort = capability.effective(requested?.takeUnless { it == ReasoningEffort.DEFAULT })
        val body = JSONObject(input.toString())
        body.remove(CONFIG_KEY)
        if (capability.status == Status.SUPPORTED) {
            require(effort in capability.efforts) { "保存的思考档位不在当前接口支持列表，请重新选择。" }
            val path = capability.field.split('.')
            var target = body
            for (part in path.dropLast(1)) {
                require(!target.has(part) || target.opt(part) is JSONObject) { "思考字段路径与已有参数冲突。" }
                target = target.optJSONObject(part) ?: JSONObject().also { target.put(part, it) }
            }
            target.put(path.last(), capability.values.getValue(effort))
        }
        return body
    }
}
