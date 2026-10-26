package app.daiyu.android.model

import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.Locale

/** Connection-scoped secrets. Never place these in model-local options, logs or backups. */
class ProviderHeaders private constructor(private val values: Map<String, String>) {
    val isEmpty: Boolean get() = values.isEmpty()

    internal fun applyTo(connection: HttpURLConnection) {
        try { values.forEach { (name, value) -> connection.setRequestProperty(name, value) } }
        catch (failure: RuntimeException) {
            if (failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is SecurityException) throw failure
            throw IllegalArgumentException("自定义请求头设置失败")
        }
    }

    internal fun encode(): String = JSONObject(values).toString()
    override fun toString(): String = "ProviderHeaders(<redacted>)"
    override fun equals(other: Any?): Boolean = other is ProviderHeaders && values == other.values
    override fun hashCode(): Int = values.hashCode()

    companion object {
        val EMPTY = ProviderHeaders(emptyMap())
        private const val MAX_HEADERS = 32
        private const val MAX_BYTES = 16 * 1024
        private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
        private val OWNED = setOf(
            "authorization", "proxy-authorization", "host", "content-type", "content-length",
            "content-encoding", "accept", "accept-encoding", "connection", "keep-alive", "te",
            "trailer", "transfer-encoding", "upgrade", "cookie", "cookie2", "expect",
            "chatgpt-account-id", "originator", "version", "x-api-key", "x-goog-api-key", "anthropic-version", "session-id", "thread-id", "x-opencode-session",
        )

        /** Exact JSON object; no trimming, coercion or case-insensitive duplicate merging. */
        fun parse(raw: String): ProviderHeaders {
            require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "自定义请求头过大"
            }
            if (raw.isEmpty()) return EMPTY
            val value = try { MessagesRequest.jsonObject(raw, 2, rejectDuplicateKeys = true) }
            catch (_: Exception) { throw IllegalArgumentException("自定义请求头须为严格 JSON 对象") }
            require(value.length() <= MAX_HEADERS) { "自定义请求头数量过多" }
            val headers = linkedMapOf<String, String>()
            value.keys().forEach { name ->
                val canonical = name.lowercase(Locale.ROOT)
                require(TOKEN.matches(name) && canonical !in OWNED &&
                    !canonical.startsWith("proxy-") && !canonical.startsWith("sec-") &&
                    canonical !in headers) { "自定义请求头名称无效或属于运行时字段" }
                val text = value.get(name) as? String
                require(text != null && text.length <= 8192 && text.all { it.code in 32..126 } &&
                    text == text.trim()) { "自定义请求头值须为无首尾空白的可打印 ASCII 文本" }
                headers[canonical] = text
            }
            val result = if (headers.isEmpty()) EMPTY else ProviderHeaders(headers.toSortedMap())
            require(result.encode().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "自定义请求头编码后过大" }
            return result
        }
    }
}
