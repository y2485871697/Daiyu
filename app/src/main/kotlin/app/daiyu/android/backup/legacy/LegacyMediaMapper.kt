package app.daiyu.android.backup.legacy

import org.json.JSONArray
import org.json.JSONObject

/** Resolves only archive bytes; no File, URL client, content resolver or image decoder exists here. */
internal object LegacyMediaMapper {
    data class Reference(val value: String, val purpose: String)
    fun collect(conversation: JSONObject, messages: List<JSONObject>, checkpoint: JSONObject?): List<Reference> {
        val refs = mutableListOf<Reference>()
        fun add(s: String, purpose: String) { if (s.isNotBlank()) refs += Reference(s, purpose) }
        fun images(value: Any, purpose: String) {
            when (value) {
                is String -> add(value, purpose)
                is JSONArray -> (0 until value.length()).forEach { images(value.get(it), purpose) }
                is JSONObject -> value.keys().forEach { key ->
                    val item = value.get(key)
                    if (key in setOf("preview", "source", "dataUrl") && item is String) add(item, if (key == "preview") "preview" else "source")
                    else if (item is JSONObject || item is JSONArray) images(item, purpose)
                }
            }
        }
        fun prose(text: String) { markdownDestinations(text).forEach { add(it, "text-attachment") } }
        fun history(array: JSONArray) {
            LegacyJson.rows(array).forEach { row ->
                val isProse = row.optString("role") in setOf("user", "assistant")
                if (isProse && row.has("content") && row.get("content") is String) prose(LegacyJson.string(row, "content"))
                if (row.has("contentJson") && !row.isNull("contentJson") && LegacyJson.string(row, "contentJson").isNotBlank()) {
                    val parts = LegacyJson.parse(LegacyJson.string(row, "contentJson")) as? JSONArray ?: error("Invalid contentJson")
                    LegacyJson.rows(parts).forEach { part -> when (part.optString("type")) {
                        "image_file", "video_file" -> add(LegacyJson.string(part, "path"), if (part.optString("type") == "video_file") "video-original" else "image-original")
                        "image_url", "video_url" -> add(LegacyJson.string(part.getJSONObject(part.getString("type")), "url"), if (part.optString("type") == "video_url") "video-original" else "image-original")
                        "text" -> if (isProse) prose(LegacyJson.string(part, "text"))
                    } }
                }
            }
        }
        history(LegacyParser.arrayString(conversation, "historyJson"))
        checkpoint?.let { history(LegacyParser.arrayString(it, "historyJson")) }
        messages.forEach { row ->
            if (row.optString("type") in setOf("user", "assistant")) prose(LegacyJson.string(row, "content"))
            images(LegacyParser.arrayString(row, "imagesJson"), "image-reference")
        }
        return refs.distinct()
    }

    fun resolve(refs: List<Reference>, owner: String, version: Int, document: JSONObject?, originals: Map<String, ByteArray>, zip: Boolean): List<LegacyMedia> {
        val declarations = if (version >= 2 && document != null) LegacyParser.rows(document, "attachments") else emptyList()
        val declared = linkedMapOf<String, String>()
        declarations.forEach { a ->
            LegacyParser.keys(a, setOf("reference", "entry", "size", "sha256"))
            val ref = LegacyJson.string(a, "reference"); require(ref.startsWith("/eta-attachments/"))
            val relative = LegacyArchive.path(ref.removePrefix("/eta-attachments/"))
            val entry = LegacyArchive.path(LegacyJson.string(a, "entry"))
            require(entry == "attachments/imports/$relative") { "Attachment entry/reference mismatch" }
            require(declared.put(ref, entry) == null && declared.values.toSet().size == declared.size) { "Duplicate attachment declaration" }
            val size = LegacyJson.integer(a, "size"); val hash = LegacyJson.string(a, "sha256")
            require(size in 0..LegacyArchive.BLOB_LIMIT.toLong() && hash.matches(Regex("[0-9a-f]{64}")))
            if (zip) {
                val bytes = originals[entry] ?: error("Missing declared original")
                require(bytes.size.toLong() == size && digest(bytes) == hash) { "Attachment size/SHA-256 mismatch" }
            }
        }
        if (version >= 2 && document != null) {
            require(LegacyParser.count(document, "attachmentCount") == declarations.size.toLong())
            if (zip) require(originals.keys == declared.values.toSet()) { "Attachment ZIP/manifest set mismatch" }
        }
        val result = refs.map { ref ->
            val local = local(ref.value)
            val entry = if (!local) null else if (version >= 2 && document != null) {
                declared[ref.value] ?: error("Local media outside declared attachments")
            } else legacyEntry(ref.value, owner)
            if (local && zip) require(entry != null && originals.containsKey(entry)) { "Missing or unsafe legacy local media" }
            val bytes = entry?.let { originals[it] }
            LegacyMedia(ref.value, entry, bytes?.let(::digest), bytes?.size?.toLong(), ref.purpose, bytes != null)
        }.toMutableList()
        // Opaque file-reference prompts are retained literally. Declared originals remain discoverable
        // even when a textual grammar is unavailable, rather than rewriting arbitrary prose/code paths.
        declared.forEach { (ref, entry) -> if (result.none { it.reference == ref }) {
            val bytes = originals[entry]
            result += LegacyMedia(ref, entry, bytes?.let(::digest), bytes?.size?.toLong(), "declared-original", bytes != null)
        } }
        return result
    }

    fun legacyEntry(reference: String, owner: String): String? {
        val raw = reference.removePrefix("file://")
        val match = Regex("^/data/(?:user/[0-9]+|data)/[^/]+/(.+)$").matchEntire(raw) ?: return null
        val tail = match.groupValues[1]
        val entry = when {
            tail.startsWith("cache/eta-chat-images/$owner/") -> "attachments/chat-images/" + tail.removePrefix("cache/eta-chat-images/")
            tail.startsWith("files/terminal/workspace/imports/") -> "attachments/imports/" + tail.removePrefix("files/terminal/workspace/imports/")
            tail.startsWith("files/terminal-user/workspace/imports/") -> "attachments/imports/" + tail.removePrefix("files/terminal-user/workspace/imports/")
            else -> return null
        }
        return LegacyArchive.path(entry)
    }
    private fun local(ref: String) = ref.startsWith('/') || ref.startsWith("file://") || ref.startsWith("content:")

    /** Conservative, single-line inline links/images, not a text replacement engine.
     * The UI MarkdownParser deliberately excludes images and local destinations, so its rendered
     * links cannot serve as an attachment inventory. Unsupported syntax stays literal in raw JSON;
     * declarations and original-byte validation in resolve() are independent of this recognizer. */
    internal fun markdownDestinations(text: String): List<String> {
        val out = mutableListOf<String>()
        val paragraph = StringBuilder()
        fun flush() { if (paragraph.isNotEmpty()) { out += inlineDestinations(paragraph.toString()); paragraph.clear() } }
        val quote = Regex("^ {0,3}>[ \\t]?")
        val fencePattern = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
        var fence: Char? = null; var fenceSize = 0; var fenceDepth = 0; var previousDepth = 0
        text.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { raw ->
            var line = raw; var depth = 0
            while (true) {
                val prefix = quote.find(line) ?: break
                line = line.drop(prefix.value.length); depth++
            }
            if (depth != previousDepth) flush()
            previousDepth = depth
            if (fence != null) {
                if (depth >= fenceDepth) {
                    val closing = line.trimStart(' ')
                    if (depth == fenceDepth && line.length - closing.length <= 3 &&
                        closing.takeWhile { it == fence }.length >= fenceSize &&
                        closing.dropWhile { it == fence }.all { it == ' ' || it == '\t' }) fence = null
                    return@forEach
                }
                fence = null // leaving a quote also ends its unclosed fenced block
            }
            val opening = fencePattern.matchEntire(line)
            if (opening != null && !(opening.groupValues[1][0] == '`' && opening.groupValues[2].contains('`'))) {
                flush(); fence = opening.groupValues[1][0]; fenceSize = opening.groupValues[1].length; fenceDepth = depth
                return@forEach
            }
            if (line.isBlank() || line.startsWith("    ") || line.startsWith('\t')) { flush(); return@forEach }
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line)
        }
        flush()
        return out
    }

    private const val MARKDOWN_ESCAPES = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    private fun inlineDestinations(source: String): List<String> {
        // Match complete code runs first, including multiline spans. Unmatched backticks are text.
        val runs = mutableListOf<Pair<Int, Int>>()
        var p = 0
        while (p < source.length) {
            if (source[p] != '`') { p++; continue }
            val start = p
            while (p < source.length && source[p] == '`') p++
            runs += start to p
        }
        val codeEnds = mutableMapOf<Int, Int>(); val nextRun = mutableMapOf<Int, Int>()
        runs.asReversed().forEach { (start, end) ->
            nextRun[end - start]?.let { codeEnds[start] = it }
            var before = start - 1
            while (before >= 0 && source[before] == '\\') before--
            if ((start - before - 1) % 2 == 1 && end - start > 1) {
                nextRun[end - start - 1]?.let { codeEnds[start + 1] = it }
            }
            nextRun[end - start] = end
        }
        val out = mutableListOf<String>()
        // Failed, deeply nested candidates must not cause quadratic destination rescans.
        // Exhaustion invalidates the whole inventory: partial results cannot prove media safety.
        var budget = source.length.toLong() * 8 + 64
        fun spend() {
            require(budget > 0) { "Markdown attachment scan budget exhausted" }
            budget--
        }
        fun space(c: Char) = c == ' ' || c == '\t'
        fun destination(start: Int): Pair<String, Int>? {
            var i = start
            while (i < source.length && space(source[i])) { spend(); i++ }
            val angle = source.getOrNull(i) == '<'
            if (angle) i++
            val value = StringBuilder(); var balance = 0
            while (i < source.length) {
                spend()
                val c = source[i]
                if (c == '\\' && source.getOrNull(i + 1)?.let { it in MARKDOWN_ESCAPES } == true) {
                    value.append(source[i + 1]); i += 2; continue
                }
                if (angle && c == '>') break
                if (!angle && (space(c) || c == ')' && balance == 0)) break
                // Entities, raw angles, code delimiters and control characters are outside this
                // small grammar, rather than guessed/decoded into a possibly different local path.
                if (c == '<' || c == '>' || c == '`' || c == '&' || c.isISOControl() && c != '\t') return null
                if (!angle) {
                    if (c == '(') balance++
                    if (c == ')') balance--
                }
                value.append(c); i++
            }
            if (value.isEmpty() || balance != 0) return null
            if (angle) { if (source.getOrNull(i) != '>') return null; i++ }
            val end = i
            while (i < source.length && space(source[i])) { spend(); i++ }
            if (source.getOrNull(i) == ')') return value.toString() to (i + 1)
            if (i == end) return null
            val title = source.getOrNull(i) ?: return null
            if (title !in "\"'(") return null
            val close = if (title == '(') ')' else title
            i++
            while (i < source.length && source[i] != close) {
                spend()
                val c = source[i]
                if (c == '\n' || c == '\r' || c == '`' || title == '(' && c == '(') return null
                if (c == '\\' && source.getOrNull(i + 1)?.let { it in MARKDOWN_ESCAPES } == true) i++
                i++
            }
            if (source.getOrNull(i) != close) return null
            i++
            while (i < source.length && space(source[i])) { spend(); i++ }
            return if (source.getOrNull(i) == ')') value.toString() to (i + 1) else null
        }
        p = 0
        while (p < source.length) {
            if (source[p] == '\\' && source.getOrNull(p + 1)?.let { it in MARKDOWN_ESCAPES } == true) { p += 2; continue }
            val codeEnd = codeEnds[p]
            if (codeEnd != null) { p = codeEnd; continue }
            if (source[p] == '[') {
                var end = p + 1
                while (end < source.length && source[end] !in "[]`\n") {
                    spend()
                    if (source[end] == '\\' && source.getOrNull(end + 1)?.let { it in MARKDOWN_ESCAPES } == true) end++
                    end++
                }
                if (source.getOrNull(end) == ']' && source.getOrNull(end + 1) == '(') {
                    val found = destination(end + 2)
                    if (found != null) { out += found.first; p = found.second; continue }
                }
            }
            p++
        }
        return out
    }
}
