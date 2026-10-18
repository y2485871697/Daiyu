package app.daiyu.android.model

import org.json.JSONObject
import org.json.JSONTokener

internal enum class RequestBodyValueType(val label: String) {
    STRING("字符串"), NUMBER("数字"), BOOLEAN("布尔"), NULL("null"), OBJECT("对象"), ARRAY("数组")
}

internal data class RequestBodyField(val name: String, val type: RequestBodyValueType, val text: String) {
    override fun toString() = "RequestBodyField(name=<redacted>, type=$type, text=<redacted>)"
}

/** An editor projection only. Keep raw value tokens, including numbers Android JSON would round. */
internal object RequestBodyFields {
    fun read(raw: String): List<RequestBodyField> = body(raw).map { (name, token) ->
        val type = type(token)
        RequestBodyField(name, type, if (type == RequestBodyValueType.STRING) JSONTokener(token).nextValue() as String else token)
    }

    fun put(raw: String, originalName: String?, field: RequestBodyField, protocol: ProviderProtocol): String {
        val body = body(raw)
        require(field.name.isNotEmpty() && field.name.none { it.code < 32 }) { "请输入有效字段名" }
        require(originalName == null || originalName in body) { "原字段已变化" }
        require(field.name == originalName || field.name !in body) { "字段名已存在" }
        bounded(field.text)
        val token = if (field.type == RequestBodyValueType.STRING) JSONObject.quote(field.text) else {
            val envelope = try { body("{\"value\":${field.text}}") }
            catch (_: Exception) { throw IllegalArgumentException("字段值须为所选类型的严格 JSON") }
            require(envelope.size == 1 && "value" in envelope) { "字段值须为单个 JSON 值" }
            val value = envelope.getValue("value")
            require(type(value) == field.type) { "字段值与所选类型不符" }
            value
        }
        if (originalName != null && originalName != field.name) body.remove(originalName)
        body[field.name] = token
        return checked(body, protocol)
    }

    fun remove(raw: String, name: String, protocol: ProviderProtocol): String {
        val body = body(raw)
        require(name in body) { "原字段已变化" }
        body.remove(name)
        return checked(body, protocol)
    }

    private fun checked(body: Map<String, String>, protocol: ProviderProtocol): String {
        val encoded = body.entries.joinToString(",", "{", "}") { (name, token) -> "${JSONObject.quote(name)}:$token" }
        ProviderRequestOptions(customBody = encoded).validate(protocol)
        return encoded
    }

    private fun type(token: String): RequestBodyValueType = when (token.first()) {
        '"' -> RequestBodyValueType.STRING
        '{' -> RequestBodyValueType.OBJECT
        '[' -> RequestBodyValueType.ARRAY
        't', 'f' -> RequestBodyValueType.BOOLEAN
        'n' -> RequestBodyValueType.NULL
        else -> RequestBodyValueType.NUMBER
    }

    private fun bounded(raw: String) {
        require(raw.length <= ProviderRequestOptions.MAX_BODY_BYTES &&
            raw.toByteArray(Charsets.UTF_8).size <= ProviderRequestOptions.MAX_BODY_BYTES) { "自定义请求体过大" }
    }

    private fun body(raw: String): LinkedHashMap<String, String> {
        bounded(raw)
        if (raw.isEmpty()) return linkedMapOf()
        // Reuse the strict syntax/depth/duplicate validator before locating root member spans.
        // Its materialized numbers are intentionally discarded, never used to rewrite the draft.
        try { MessagesRequest.jsonObject(raw, rejectDuplicateKeys = true) }
        catch (_: Exception) { throw IllegalArgumentException("请先在 JSON 编辑器修复请求体") }
        var index = raw.indexOf('{') + 1
        fun whitespace() { while (raw[index].isWhitespace()) index++ }
        fun stringEnd(start: Int): Int {
            var cursor = start + 1
            while (raw[cursor] != '"') cursor += if (raw[cursor] == '\\') 2 else 1
            return cursor + 1
        }
        val fields = linkedMapOf<String, String>()
        whitespace()
        if (raw[index] == '}') return fields
        while (true) {
            val start = index
            index = stringEnd(index)
            val name = JSONTokener(raw.substring(start, index)).nextValue() as String
            whitespace(); index++; whitespace() // The validated colon.
            val valueStart = index
            var depth = 0
            while (true) {
                when (raw[index]) {
                    '"' -> { index = stringEnd(index); continue }
                    '{', '[' -> depth++
                    '}', ']' -> if (depth == 0) break else depth--
                    ',' -> if (depth == 0) break
                }
                index++
            }
            fields[name] = raw.substring(valueStart, index).trimEnd()
            if (raw[index] == '}') return fields
            index++; whitespace()
        }
    }
}
