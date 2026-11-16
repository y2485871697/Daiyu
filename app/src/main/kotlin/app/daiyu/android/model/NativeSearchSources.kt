package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** Provider metadata is untrusted display data. It never creates a local tool invocation. */
internal class NativeSearchSources {
    private val sources = linkedMapOf<String, String>()
    private val queries = linkedSetOf<String>()
    private val chunks = JSONArray()
    private val supports = JSONArray()
    private var searchEntryPoint: JSONObject? = null
    private var chars = 0

    fun add(url: String, title: String = "") {
        require(url.length <= 8192 && title.length <= 2048) { "网页引用过长" }
        val uri = URI.create(url)
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && url.none { it <= ' ' || it in "<>\\" }) { "网页引用地址无效" }
        val previous = sources[url]
        if (previous == null || previous.isBlank()) sources[url] = title
        require(sources.size <= 128) { "网页引用过多" }
    }

    fun chat(value: JSONObject): Boolean {
        var present = false
        if (value.has("citations") && !value.isNull("citations")) {
            present = true
            val list = value.getJSONArray("citations")
            require(list.length() <= 128)
            repeat(list.length()) { add(list.get(it) as? String ?: throw IllegalArgumentException("网页引用地址无效")) }
        }
        if (value.has("search_results") && !value.isNull("search_results")) {
            present = true
            val list = value.getJSONArray("search_results")
            require(list.length() <= 128)
            repeat(list.length()) { val item = list.getJSONObject(it); add(item.getString("url"), item.optString("title")) }
        }
        if (value.has("web_search") && !value.isNull("web_search")) {
            present = true
            val list = value.getJSONArray("web_search")
            require(list.length() <= 128)
            repeat(list.length()) { val item = list.getJSONObject(it); add(item.getString("link"), item.optString("title")) }
        }
        return present
    }

    fun gemini(value: JSONObject) {
        chars += value.toString().length
        require(chars <= 262144) { "搜索元数据过长" }
        value.optJSONArray("webSearchQueries")?.let { list ->
            require(list.length() <= 128)
            repeat(list.length()) {
                val query = list.get(it) as? String ?: throw IllegalArgumentException("搜索建议格式无效")
                require(query.length <= 8192)
                queries.add(query); require(queries.size <= 128)
            }
        }
        value.optJSONObject("searchEntryPoint")?.let { searchEntryPoint = it }
        value.optJSONArray("groundingChunks")?.let { list ->
            repeat(list.length()) {
                val chunk = list.getJSONObject(it)
                chunks.put(chunk); require(chunks.length() <= 128)
                chunk.optJSONObject("web")?.let { web -> add(web.getString("uri"), web.optString("title")) }
            }
        }
        value.optJSONArray("groundingSupports")?.let { list ->
            repeat(list.length()) { supports.put(list.getJSONObject(it)); require(supports.length() <= 1024) }
        }
    }

    fun geminiWire(): JSONObject {
        repeat(supports.length()) { position ->
            supports.getJSONObject(position).optJSONArray("groundingChunkIndices")?.let { indices ->
                repeat(indices.length()) {
                    val index = indices.get(it)
                    require(index is Number && index.toDouble() == index.toInt().toDouble() && index.toInt() in 0 until chunks.length()) {
                        "搜索来源索引无效"
                    }
                }
            }
        }
        return JSONObject().put("groundingChunks", chunks).put("groundingSupports", supports)
            .put("webSearchQueries", JSONArray(queries.toList()))
            .also { result -> searchEntryPoint?.let { result.put("searchEntryPoint", it) } }
    }

    fun claude(citations: JSONArray) {
        require(citations.length() <= 128)
        repeat(citations.length()) {
            val citation = citations.getJSONObject(it)
            if (citation.optString("type") == "web_search_result_location")
                add(citation.getString("url"), citation.optString("title"))
        }
    }

    fun wire(): JSONArray = JSONArray().also { result -> sources.forEach { (url, title) ->
        result.put(JSONObject().put("url", url).put("title", title))
    } }

    fun restore(list: JSONArray) {
        require(list.length() <= 128)
        repeat(list.length()) { val value = list.getJSONObject(it); add(value.getString("url"), value.getString("title")) }
    }

    fun display(plain: String): String {
        val sections = mutableListOf<String>()
        if (sources.isNotEmpty()) sections.add(sources.entries.mapIndexed { index, source ->
            "${index + 1}. [${label(source.value.ifBlank { "网页来源 ${index + 1}" })}](<${source.key}>)"
        }.joinToString("\n", "来源：\n\n"))
        if (queries.isNotEmpty()) sections.add(queries.joinToString(" · ", "Google 搜索建议：\n\n") {
            "[${label(it)}](<https://www.google.com/search?q=${URLEncoder.encode(it, "UTF-8")}>)"
        })
        return sections.fold(plain) { text, section -> app.daiyu.android.MarkdownParser.appendStandaloneSection(text, section) }
    }

    private fun label(value: String): String = value.replace(Regex("[\\r\\n\\t]+"), " ")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
        .replace("*", "\\*").replace("_", "\\_").replace("`", "\\`")
}
