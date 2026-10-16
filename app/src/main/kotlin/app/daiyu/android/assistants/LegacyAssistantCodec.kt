package app.daiyu.android.assistants

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Pure legacy decoder. Absence is handled by the caller; malformed data is never a reset. */
internal object LegacyAssistantCodec {
    const val KEY = "assistant-index-v1"
    data class Snapshot(val profiles: List<AssistantProfile>, val selectedId: String)

    fun decode(raw: String): Snapshot {
        val parser = JSONTokener(raw)
        val root = parser.nextValue() as? JSONObject ?: error("助手数据不是对象；原数据保留")
        require(parser.nextClean() == '\u0000')
        require(root.keys().asSequence().toSet() == setOf("profiles", "activeId")) { "未知助手索引字段；原数据保留" }
        val array = root.getJSONArray("profiles")
        require(array.length() > 0) { "助手列表为空；原数据保留" }
        fun text(p: JSONObject, key: String) = p.get(key) as? String ?: error("助手字段须为文本: $key")
        val profiles = (0 until array.length()).map { i ->
            val p = array.getJSONObject(i)
            require(p.keys().asSequence().all { it in setOf("id", "name", "prompt", "avatarFileName", "createdAt", "memoryEnabled", "enabledSkillIds") }) { "未知助手字段；原数据保留" }
            val skills = if (p.has("enabledSkillIds")) {
                val ids = p.getJSONArray("enabledSkillIds")
                (0 until ids.length()).map { ids.get(it) as? String ?: error("技能 ID 须为文本") }
            } else AssistantDefaults.ENABLED_SKILL_IDS
            val created = if (p.has("createdAt")) p.get("createdAt").also { require(it is Long || it is Int) } as Number else 0L
            AssistantProfile(AssistantStorage.id(text(p, "id")), text(p, "name"),
                if (p.has("prompt")) text(p, "prompt") else "",
                if (!p.has("avatarFileName") || p.isNull("avatarFileName")) null else text(p, "avatarFileName"),
                created.toLong(), if (p.has("memoryEnabled")) p.get("memoryEnabled") as? Boolean
                    ?: error("记忆开关须为布尔值") else true, skills.toList())
        }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "助手 ID 重复；原数据保留" }
        val selected = text(root, "activeId")
        require(profiles.any { it.id == selected }) { "已选助手不存在；原数据保留" }
        return Snapshot(profiles, selected)
    }

    fun encode(snapshot: Snapshot): String = JSONObject().put("profiles", JSONArray().apply {
        snapshot.profiles.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("prompt", p.prompt)
            .put("avatarFileName", p.avatarFileName ?: JSONObject.NULL).put("createdAt", p.createdAt)
            .put("memoryEnabled", p.memoryEnabled).put("enabledSkillIds", JSONArray(p.enabledSkillIds))) }
    }).put("activeId", snapshot.selectedId).toString()
}
