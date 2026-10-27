package app.daiyu.android.references

import org.json.JSONObject

/** Literal user references contain locators only, never copied conversation or skill bodies. */
internal object PromptReferences {
    const val PREFIX = "会话引用（按需读取）："
    data class Conversation(val id: String, val title: String)
    private val skills = Regex("(?<!\\S)/skill:((?:(?:builtin|user):)?[a-z0-9]+(?:-[a-z0-9]+)*)(?=\\s|$)")

    fun conversation(line: String): Conversation? {
        if (!line.startsWith(PREFIX)) return null
        return runCatching {
            val json = JSONObject(line.removePrefix(PREFIX))
            val id = json.getString("id")
            require(id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() })
            Conversation(id, json.getString("title").filterNot { it.isISOControl() }.take(200))
        }.getOrNull()
    }
    fun encode(id: String, title: String): String = PREFIX + JSONObject().put("id", id)
        .put("title", title.filterNot { it.isISOControl() }.take(200)).toString()
    fun conversations(texts: List<String>): List<Conversation> = texts.asSequence().flatMap { it.lineSequence() }
        .mapNotNull(::conversation).distinctBy { it.id }.take(16).toList()
    fun skillIds(text: String): Set<String> = skills.findAll(text).map { it.groupValues[1] }.toSet()
}
