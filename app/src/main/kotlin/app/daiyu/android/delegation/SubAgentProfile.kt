package app.daiyu.android.delegation

import app.daiyu.android.model.GptSpeedMode
import org.json.JSONObject

internal data class SubAgentProfile(
    val id: String,
    val name: String,
    val role: String = "implementation",
    val enabled: Boolean = true,
    val providerId: String = "",
    val modelId: String = "",
    val tier: SubAgentTaskTier? = null,
    val reasoning: ReasoningEffort? = null,
    val imageResolution: String? = null,
    val reasoningByModel: Map<String, ReasoningEffort> = emptyMap(),
    val gptSpeedByModel: Map<String, GptSpeedMode> = emptyMap(),
    val parallelLimit: Int? = null,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && name.length <= 80)
        require(parallelLimit == null || parallelLimit >= 0)
        require(role in setOf("implementation", "review", "image_generation", "video_generation"))
    }
    val supportsTaskTier: Boolean get() = role == "implementation"
    fun normalizedTaskTier(): SubAgentProfile = if (supportsTaskTier || tier == null) this else copy(tier = null)
    val isMedia: Boolean get() = role == "image_generation" || role == "video_generation"
    fun acceptsModel(image: Boolean, video: Boolean): Boolean = when (role) {
        "image_generation" -> image
        "video_generation" -> video
        else -> !image && !video
    }
    fun withRole(next: String): SubAgentProfile = (if (role == next ||
        (!isMedia && next in setOf("implementation", "review"))) copy(role = next)
        else copy(role = next, providerId = "", modelId = "", reasoning = null, imageResolution = null)).normalizedTaskTier()
    fun gptSpeedForModel(providerId: String = this.providerId, modelId: String = this.modelId,
        fallback: GptSpeedMode = GptSpeedMode.NORMAL): GptSpeedMode =
        gptSpeedByModel[modelReasoningKey(providerId, modelId)] ?: fallback
    val selection get() = ModelFeatureSelection(true, providerId, modelId)
    val roleLabel get() = when (role) {
        "implementation" -> "执行"
        "image_generation" -> "图片生成"
        "video_generation" -> "视频生成"
        else -> "审查／总结"
    }
    fun toJson() = JSONObject().put("id", id).put("name", name).put("role", role).put("enabled", enabled)
        .put("provider", providerId).put("model", modelId).put("tier", tier?.takeIf { supportsTaskTier }?.wireValue.orEmpty())
        .put("reasoning", reasoning?.wireValue.orEmpty())
        .put("image_resolution", imageResolution.orEmpty())
        .put("parallel_limit", parallelLimit ?: JSONObject.NULL)
        .put("reasoning_memory", org.json.JSONArray().also { array ->
            reasoningByModel.forEach { (key, effort) ->
                val parts = key.split("\u0000", limit = 2)
                if (parts.size == 2) array.put(JSONObject()
                    .put("provider", parts[0]).put("model", parts[1]).put("reasoning", effort.wireValue))
            }
        })
        .put("gpt_speed_memory", org.json.JSONArray().also { array ->
            gptSpeedByModel.forEach { (key, speed) ->
                val parts = key.split('\u0000')
                if (parts.size == 2 && parts.all { it.isNotBlank() }) array.put(JSONObject()
                    .put("provider", parts[0]).put("model", parts[1]).put("speed", speed.name))
            }
        })
    companion object {
        fun fromJson(j: JSONObject) = SubAgentProfile(j.getString("id"), j.getString("name"),
            j.getString("role"), j.optBoolean("enabled", true), j.optString("provider"), j.optString("model"),
            SubAgentTaskTier.fromWireValue(j.optString("tier")), ReasoningEffort.fromWireValue(j.optString("reasoning")),
            j.optString("image_resolution").takeIf { it in ImageResolutionTier.values },
            reasoningMemory(j), gptSpeedMemory(j), parallelLimit(j)).normalizedTaskTier()
        private fun parallelLimit(j: JSONObject): Int? {
            if (!j.has("parallel_limit") || j.isNull("parallel_limit")) return null
            val value = j.opt("parallel_limit")
            require(value is Int && value >= 0) { "Invalid profile parallel limit" }
            return value
        }
        fun modelReasoningKey(providerId: String, modelId: String): String = providerId + "\u0000" + modelId
        private fun gptSpeedMemory(j: JSONObject): Map<String, GptSpeedMode> {
            val array = j.optJSONArray("gpt_speed_memory") ?: return emptyMap()
            return buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val provider = item.opt("provider") as? String ?: continue
                    val model = item.opt("model") as? String ?: continue
                    val speed = GptSpeedMode.entries.firstOrNull { it.name == item.opt("speed") } ?: continue
                    if (provider.isNotBlank() && model.isNotBlank() && '\u0000' !in provider && '\u0000' !in model)
                        put(modelReasoningKey(provider, model), speed)
                }
            }
        }
        private fun reasoningMemory(j: JSONObject): Map<String, ReasoningEffort> {
            val array = j.optJSONArray("reasoning_memory") ?: return emptyMap()
            return buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val provider = item.optString("provider")
                    val model = item.optString("model")
                    val effort = ReasoningEffort.fromWireValue(item.optString("reasoning")) ?: continue
                    if (provider.isNotBlank() && model.isNotBlank()) put(modelReasoningKey(provider, model), effort)
                }
            }
        }
    }
}
