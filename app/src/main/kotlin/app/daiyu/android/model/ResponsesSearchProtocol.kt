package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Hosted search is protocol data, never a local tool invocation or an instruction to visit a URL. */
internal object ResponsesSearchProtocol {
    private const val MAX_CITATIONS = 128
    private const val MAX_URL = 8192
    private const val MAX_TITLE = 2048
    private val citationFields = setOf("type", "start_index", "end_index", "url", "title")

    fun validateItem(item: JSONObject) {
        when (item.opt("type")) {
            "web_search_call" -> {
                fields(item, setOf("type", "id", "status", "action"))
                require(text(item, "id", 200).isNotBlank())
                require(item.opt("status") in setOf("completed", "failed", "incomplete")) { "不支持此原生搜索状态" }
                val action = item.opt("action") as? JSONObject ?: invalid()
                when (action.opt("type")) {
                    "search" -> {
                        fields(action, setOf("type"), setOf("query", "queries", "sources"))
                        if (action.has("query") && !action.isNull("query")) text(action, "query", 16384)
                        if (action.has("queries") && !action.isNull("queries")) {
                            val queries = action.opt("queries") as? JSONArray ?: invalid()
                            require(queries.length() <= 128)
                            repeat(queries.length()) { require(queries.opt(it) is String && (queries.opt(it) as String).length <= 16384) }
                        }
                        if (action.has("sources") && !action.isNull("sources")) {
                            val sources = action.opt("sources") as? JSONArray ?: invalid()
                            require(sources.length() <= 1024)
                            repeat(sources.length()) { index ->
                                val source = sources.opt(index) as? JSONObject ?: invalid()
                                fields(source, setOf("type", "url")); require(source.opt("type") == "url")
                                url(text(source, "url", MAX_URL))
                            }
                        }
                    }
                    "open_page", "find_in_page" -> {
                        val required = if (action.opt("type") == "find_in_page") setOf("type", "pattern", "url") else setOf("type")
                        fields(action, required, setOf("url"))
                        if (action.has("pattern")) text(action, "pattern", 16384)
                        if (action.opt("type") == "find_in_page" || action.has("url") && !action.isNull("url"))
                            url(text(action, "url", MAX_URL))
                    }
                    else -> invalid()
                }
            }
            "message" -> {
                val content = item.opt("content") as? JSONArray ?: invalid()
                repeat(content.length()) { index ->
                    val part = content.opt(index) as? JSONObject ?: invalid()
                    if (part.opt("type") == "output_text") validateTextPart(part)
                }
            }
        }
    }

    fun validateTextPart(part: JSONObject) {
        val value = part.opt("text") as? String ?: invalid()
        if (!part.has("annotations")) return
        val annotations = part.opt("annotations") as? JSONArray ?: invalid()
        require(annotations.length() <= MAX_CITATIONS) { "网页引用过多" }
        repeat(annotations.length()) { index ->
            val citation = annotations.opt(index) as? JSONObject ?: invalid()
            fields(citation, citationFields)
            require(citation.opt("type") == "url_citation") { "不支持此引用类型" }
            val start = offset(citation.opt("start_index")); val end = offset(citation.opt("end_index"))
            // Bounds accept existing UTF-16 histories too; display clamps legacy offsets to a Unicode boundary.
            require(start <= end && end <= value.length) { "网页引用位置无效" }
            url(text(citation, "url", MAX_URL)); text(citation, "title", MAX_TITLE)
        }
    }

    /** Citation offsets address Unicode characters within one part, never the merged message. */
    fun displayText(item: JSONObject, plain: String): String {
        validateItem(item)
        if (item.opt("type") != "message") return plain
        val sources = linkedMapOf<String, String>()
        val content = item.getJSONArray("content")
        repeat(content.length()) { index ->
            val part = content.getJSONObject(index)
            if (part.opt("type") == "output_text") part.optJSONArray("annotations")?.let { annotations ->
                repeat(annotations.length()) { position ->
                    val citation = annotations.getJSONObject(position)
                    sources.putIfAbsent(url(citation.getString("url")), citation.getString("title"))
                }
            }
        }
        if (sources.isEmpty()) return plain
        val numbers = sources.keys.withIndex().associate { it.value to it.index + 1 }
        val displayed = buildString {
            repeat(content.length()) { index ->
                val part = content.getJSONObject(index)
                val text = when (part.opt("type")) {
                    "output_text" -> part.getString("text")
                    "refusal" -> part.getString("refusal")
                    else -> ""
                }
                val citations = sortedMapOf<Int, LinkedHashSet<String>>()
                part.optJSONArray("annotations")?.let { annotations ->
                    repeat(annotations.length()) { position ->
                        val citation = annotations.getJSONObject(position)
                        val target = url(citation.getString("url"))
                        val end = offset(citation.opt("end_index"))
                        val boundary = text.offsetByCodePoints(0, minOf(end, text.codePointCount(0, text.length)))
                        citations.getOrPut(boundary) { linkedSetOf() }.add(target)
                    }
                }
                fun markers(at: Int) {
                    citations[at]?.forEach { target -> append(" [${numbers.getValue(target)}](<$target>)") }
                }
                markers(0)
                var position = 0
                while (position < text.length) {
                    val character = text.codePointAt(position)
                    // Cited answers render literal text so provider Markdown cannot hide inline links
                    // inside code, link labels, tables or other nested syntax. Raw protocol is untouched.
                    if (position == 0 || text[position - 1] == '\n' || text[position - 1] == '\r') append('\u2060')
                    if (character < 128 && character.toChar() in "\\`*_{}[]()<>#+-.!|~&") append('\\')
                    appendCodePoint(character)
                    position += Character.charCount(character)
                    markers(position)
                }
            }
        }
        return displayed + sources.entries.mapIndexed { index, (url, title) ->
            "${index + 1}. [${label(title.ifBlank { "网页来源 ${index + 1}" })}](<$url>)"
        }.joinToString("\n", "\n\n来源：\n\n")
    }

    private fun label(value: String): String = buildString {
        value.forEach { character ->
            if (character in "\\`*_{}[]()<>#+-.!|~") append('\\')
            append(if (character == '\n' || character == '\r' || character == '\t') ' ' else character)
        }
    }

    private fun url(value: String): String {
        require(value.isNotBlank() && value.length <= MAX_URL && value.none { it.isWhitespace() || it.code < 32 || it.code == 127 }) { "网页引用地址无效" }
        val uri = try { URI(value) } catch (_: Exception) { null }
        require(uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535)) { "网页引用地址无效" }
        val ascii = uri.toASCIIString()
        return uri.scheme.lowercase() + ascii.substring(ascii.indexOf(':'))
            .replace("(", "%28").replace(")", "%29").replace("<", "%3C").replace(">", "%3E")
    }

    private fun offset(value: Any?): Int {
        require(value is Int || value is Long) { "网页引用位置无效" }
        val number = (value as Number).toLong()
        require(number in 0..Int.MAX_VALUE.toLong()) { "网页引用位置无效" }
        return number.toInt()
    }

    private fun text(value: JSONObject, key: String, limit: Int): String {
        val text = value.opt(key) as? String ?: invalid()
        require(text.length <= limit && text.none { it.code < 32 && it !in "\n\r\t" || it.code == 127 }) { "原生搜索字段无效" }
        return text
    }

    private fun fields(value: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val actual = value.keys().asSequence().toSet()
        require(actual.containsAll(required) && actual.all { it in required || it in optional }) { "原生搜索结构无效" }
    }
    private fun invalid(): Nothing = throw IllegalArgumentException("原生搜索结构无效")
}
