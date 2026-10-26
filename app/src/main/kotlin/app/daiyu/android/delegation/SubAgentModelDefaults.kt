package app.daiyu.android.delegation

import app.daiyu.android.model.GptSpeedMode
import org.json.JSONArray
import org.json.JSONObject

/** Last explicit confirmation, shared by selection identity (provider ID + model selection ID). */
internal object SubAgentModelDefaults {
    const val KEY = "agent_subagent_model_defaults_v1"
    private const val VERSION = 1
    data class Entry(
        val providerId: String, val modelId: String, val enabled: Boolean, val role: String,
        val tier: SubAgentTaskTier?, val reasoning: ReasoningEffort?, val imageResolution: String?,
        val gptSpeed: GptSpeedMode, val apiModel: String? = null, val parallelLimit: Int? = null,
        val gptSpeedInherited: Boolean = false,
    ) {
        val key: String get() = SubAgentProfile.modelReasoningKey(providerId, modelId)
        fun restore(profile: SubAgentProfile): SubAgentProfile = profile.copy(
            providerId = providerId, modelId = modelId, enabled = enabled, role = role, tier = tier,
            reasoning = reasoning, imageResolution = imageResolution,
            reasoningByModel = reasoning?.let { mapOf(key to it) }.orEmpty(), gptSpeedByModel = if (gptSpeedInherited) emptyMap() else mapOf(key to gptSpeed),
        )
    }
    fun confirmed(profile: SubAgentProfile, previous: Entry?, binding: SubAgentParallelModel?, limit: Int?): Entry = Entry(
        profile.providerId, profile.modelId, profile.enabled, profile.role, profile.tier, profile.reasoning,
        profile.imageResolution, profile.gptSpeedForModel(), binding?.apiModel ?: previous?.apiModel,
        limit ?: previous?.parallelLimit,
        gptSpeedInherited = SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId) !in profile.gptSpeedByModel,
    )
    fun validate(raw: String) { decode(raw) }
    fun decode(raw: String): Map<String, Entry> {
        val json = JSONObject(raw)
        require(json.opt("version") is Int && json.getInt("version") == VERSION) { "Unsupported model defaults version" }
        require(json.opt("models") is JSONArray) { "Invalid model defaults entries" }
        val array = json.getJSONArray("models")
        val result = linkedMapOf<String, Entry>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val provider = string(item, "provider"); val model = string(item, "model")
            require(provider.isNotBlank() && model.isNotBlank() && '\u0000' !in provider && '\u0000' !in model)
            require(item.opt("enabled") is Boolean) { "Invalid model defaults enabled flag" }
            val role = string(item, "role")
            require(role in setOf("implementation", "review", "image_generation", "video_generation"))
            val tierWire = string(item, "tier")
            val tier = SubAgentTaskTier.fromWireValue(tierWire)
            require(tierWire.isEmpty() || (role == "implementation" && tier != null))
            val reasoningWire = string(item, "reasoning")
            val reasoning = ReasoningEffort.fromWireValue(reasoningWire)
            require(reasoningWire.isEmpty() || reasoning != null)
            val resolution = string(item, "image_resolution").takeIf { it.isNotEmpty() }
            require(resolution == null || (role == "image_generation" && resolution in ImageResolutionTier.values))
            val speedWire = string(item, "gpt_speed")
            val speed = GptSpeedMode.entries.singleOrNull { it.name == speedWire } ?: error("Invalid model defaults GPT speed")
            val inherited = if (item.has("gpt_speed_inherited")) {
                require(item.opt("gpt_speed_inherited") is Boolean) { "Invalid inherited GPT speed flag" }
                item.getBoolean("gpt_speed_inherited")
            } else false
            val api = if (item.isNull("api_model")) null else string(item, "api_model").also { require(it.isNotBlank()) }
            val limit = if (item.isNull("parallel_limit")) null else {
                require(item.opt("parallel_limit") is Int) { "Invalid remembered parallel limit" }
                item.getInt("parallel_limit").also { require(it >= 0 && api != null) }
            }
            require(item.has("api_model") && item.has("parallel_limit"))
            val entry = Entry(provider, model, item.getBoolean("enabled"), role, tier, reasoning, resolution, speed, api, limit, inherited)
            require(result.put(entry.key, entry) == null) { "Duplicate model defaults identity" }
        }
        return result.toMap()
    }
    fun encode(entries: Map<String, Entry>): String {
        require(entries.all { (key, entry) -> key == entry.key })
        val raw = JSONObject().put("version", VERSION).put("models", JSONArray(entries.values.map { entry ->
            JSONObject().put("provider", entry.providerId).put("model", entry.modelId).put("enabled", entry.enabled)
                .put("role", entry.role).put("tier", entry.tier?.wireValue.orEmpty())
                .put("reasoning", entry.reasoning?.wireValue.orEmpty()).put("image_resolution", entry.imageResolution.orEmpty())
                .put("gpt_speed", entry.gptSpeed.name).put("api_model", entry.apiModel ?: JSONObject.NULL)
                .put("parallel_limit", entry.parallelLimit ?: JSONObject.NULL)
                .also { if (entry.gptSpeedInherited) it.put("gpt_speed_inherited", true) }
        })).toString()
        validate(raw)
        return raw
    }
    private fun string(json: JSONObject, name: String): String {
        require(json.opt(name) is String) { "Invalid model defaults string: $name" }
        return json.getString(name)
    }
}
