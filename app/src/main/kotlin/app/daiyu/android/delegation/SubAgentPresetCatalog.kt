package app.daiyu.android.delegation

import org.json.JSONArray
import org.json.JSONObject

/** Presets are editable copy sources, never runtime owners or live links from a conversation. */
internal data class SubAgentPreset(val id: String, val name: String, val config: ConversationSubAgentConfig)

/** Only the directory lives here; payloads are p_ owners in the repository's atomic transaction. */
internal object SubAgentPresetCatalog {
    const val KEY = "agent_conversation_child_preset_catalog_v1"
    const val DEFAULT_ID = "frozen-seed-v1"
    const val DEFAULT_NAME = "默认子代理组"
    private const val VERSION = 1
    data class Entry(val id: String, val name: String) {
        init { require(id.isNotBlank()) { "Empty preset ID" }; validateName(name) }
    }
    fun validateName(name: String) {
        require(name.isNotBlank() && name.length <= 80) { "Preset name must contain 1–80 characters" }
    }
    fun encode(entries: List<Entry>): String {
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate preset IDs" }
        return JSONObject().put("version", VERSION).put("presets", JSONArray(entries.map {
            JSONObject().put("id", it.id).put("name", it.name)
        })).toString()
    }
    fun decode(raw: String): List<Entry> {
        val json = JSONObject(raw)
        require(json.opt("version") is Int && json.getInt("version") == VERSION) { "Unsupported preset catalog version" }
        require(json.opt("presets") is JSONArray) { "Invalid preset catalog entries" }
        val array = json.getJSONArray("presets")
        val entries = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            require(item.opt("id") is String && item.opt("name") is String) { "Invalid preset catalog entry" }
            Entry(item.getString("id"), item.getString("name"))
        }
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate preset IDs" }
        return entries
    }
}
