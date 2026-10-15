package app.daiyu.android.browser.cookie

import java.net.URI
import java.util.Locale
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal data class CookieOrigin(val url: String, val host: String, val secure: Boolean)
internal data class CookiePair(val name: String, val value: String)
internal data class CookieWrite(val name: String, val header: String)

internal object BrowserCookiePolicy {
    private val namePattern = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
    fun origin(raw: String?): CookieOrigin {
        require(raw != null && raw.length <= 8192) { "COOKIE_ORIGIN" }
        val uri = URI(raw)
        require(uri.scheme in setOf("http", "https") && uri.userInfo == null && !uri.host.isNullOrEmpty()) { "COOKIE_ORIGIN" }
        val host = uri.host.lowercase(Locale.ROOT)
        require(host.matches(Regex("[a-z0-9.-]+")) && !host.endsWith('.') && !host.startsWith('.')) { "COOKIE_ORIGIN" }
        val authority = host + if (uri.port == -1) "" else ":${uri.port}"
        // Deliberately read only the origin's / cookies, never a model-supplied URL or path.
        return CookieOrigin("${uri.scheme}://$authority/", host, uri.scheme == "https")
    }
    fun read(raw: String?): List<CookiePair> {
        if (raw.isNullOrEmpty()) return emptyList()
        require(raw.length <= 65536) { "COOKIE_LIMIT" }
        val pairs = raw.split(';')
        require(pairs.size <= 128) { "COOKIE_LIMIT" }
        return pairs.map { part ->
            val text = part.trim()
            val eq = text.indexOf('=')
            require(eq > 0) { "COOKIE_HEADER" }
            val name = text.substring(0, eq)
            val value = text.substring(eq + 1)
            require(namePattern.matches(name) && value.length <= 4096 && value.all { it.code in 0x20..0x7e }) { "COOKIE_HEADER" }
            CookiePair(name, value)
        }
    }
    fun keywords(arguments: Map<String, Any?>): Pair<List<String>, Boolean> {
        require(arguments.keys.all { it in setOf("keywords", "fuzzy") }) { "COOKIE_ARGUMENT" }
        val words = if (arguments.containsKey("keywords")) {
            val value = arguments["keywords"]
            require(value is List<*> && value.size <= 32) { "COOKIE_KEYWORDS" }
            value.map {
                require(it is String && it.isNotEmpty() && it.length <= 128 && it.all { c -> c.code in 0x21..0x7e }) { "COOKIE_KEYWORDS" }
                it
            }
        } else emptyList()
        require(!arguments.containsKey("fuzzy") || arguments["fuzzy"] is Boolean) { "COOKIE_FUZZY" }
        return words to (arguments["fuzzy"] == true)
    }
    fun writes(arguments: Map<String, Any?>, origin: CookieOrigin): List<CookieWrite> {
        require(arguments.keys == setOf("cookies")) { "COOKIE_ARGUMENT" }
        val json = arguments["cookies"]
        require(json is String && json.length in 1..32768) { "COOKIE_JSON" }
        return StrictCookieJson(json).parse().map { fields ->
            require(fields.keys.all { it in setOf("name", "value", "domain", "path", "secure", "httpOnly", "http_only", "sameSite", "expires") }) { "COOKIE_FIELD" }
            val name = fields["name"]
            val value = fields["value"]
            require(name is String && namePattern.matches(name) && value is String && value.length <= 4096) { "COOKIE_FIELD" }
            // RFC6265 cookie-octet. Quotes, separators, controls and non-ASCII cannot inject headers.
            require(value.all { it.code == 0x21 || it.code in 0x23..0x2b || it.code in 0x2d..0x3a || it.code in 0x3c..0x5b || it.code in 0x5d..0x7e }) { "COOKIE_VALUE" }
            val domain = fields["domain"]
            require(domain == null || domain is String && domain.lowercase(Locale.ROOT) == origin.host) { "COOKIE_DOMAIN" }
            // Reject parent domains and Domain attributes entirely: all writes are host-only.
            val path = fields["path"] ?: "/"
            require(path is String && path.startsWith('/') && path.length <= 512 && path.all { it.code in 0x21..0x7e && it != ';' && it != ',' && it != '\\' && it != '"' }) { "COOKIE_PATH" }
            for (key in listOf("secure", "httpOnly", "http_only")) require(key !in fields || fields[key] is Boolean) { "COOKIE_FIELD" }
            require(!fields.containsKey("httpOnly") || !fields.containsKey("http_only")) { "COOKIE_FIELD_CONFLICT" }
            val httpOnly = fields["httpOnly"] == true || fields["http_only"] == true
            val expires = fields["expires"]
            require(expires == null || expires is Long && expires in 0L..253402300799L) { "COOKIE_EXPIRES" }
            val secure = fields["secure"] == true
            require(!secure || origin.secure) { "COOKIE_SECURE" }
            val sameSite = fields["sameSite"]
            require(sameSite == null || sameSite in setOf("Strict", "Lax", "None")) { "COOKIE_SAMESITE" }
            require(sameSite != "None" || secure) { "COOKIE_SECURE" }
            require(!name.startsWith("__Secure-") || secure) { "COOKIE_PREFIX" }
            require(!name.startsWith("__Host-") || secure && path == "/" && domain == null) { "COOKIE_PREFIX" }
            CookieWrite(name, buildString {
                append(name).append('=').append(value).append("; Path=").append(path)
                if (secure) append("; Secure")
                if (httpOnly) append("; HttpOnly")
                if (sameSite != null) append("; SameSite=").append(sameSite)
                if (expires is Long) append("; Expires=").append(
                    DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
                        .withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(expires))
                )
            })
        }
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    fun env(pairs: List<CookiePair>): ByteArray = buildString {
        // Indexed keys avoid collisions and shell-identifier injection by cookie names.
        pairs.forEachIndexed { index, pair ->
            append("export BROWSER_COOKIE_").append(index).append("_NAME=").append(shellQuote(pair.name)).append('\n')
            append("export BROWSER_COOKIE_").append(index).append("_VALUE=").append(shellQuote(pair.value)).append('\n')
        }
    }.toByteArray(Charsets.UTF_8)
}

/** Small strict JSON subset: array of flat objects, string/boolean/integer fields only; no coercion,
 * duplicate keys, unknown nesting, null, trailing commas, or trailing input. */
private class StrictCookieJson(private val input: String) {
    private var at = 0
    fun parse(): List<Map<String, Any>> {
        take('[')
        val result = mutableListOf<Map<String, Any>>()
        require(!peek(']')) { "COOKIE_JSON" }
        while (true) {
            require(result.size < 32) { "COOKIE_LIMIT" }
            take('{')
            val fields = linkedMapOf<String, Any>()
            require(!peek('}')) { "COOKIE_JSON" }
            while (true) {
                val key = string()
                require(key !in fields && fields.size < 9) { "COOKIE_JSON" }
                take(':')
                space()
                fields[key] = when {
                    peek('"') -> string()
                    input.startsWith("true", at) -> { at += 4; true }
                    input.startsWith("false", at) -> { at += 5; false }
                    at < input.length && (input[at] == '-' || input[at] in '0'..'9') -> integer()
                    else -> error("COOKIE_JSON")
                }
                if (peek('}')) { take('}'); break }
                take(',')
            }
            result.add(fields)
            if (peek(']')) { take(']'); break }
            take(',')
        }
        space()
        require(at == input.length) { "COOKIE_JSON" }
        return result
    }
    private fun integer(): Long {
        val start = at
        if (input[at] == '-') at++
        require(at < input.length && input[at] in '0'..'9') { "COOKIE_JSON" }
        if (input[at] == '0') at++ else while (at < input.length && input[at] in '0'..'9') at++
        return input.substring(start, at).toLongOrNull() ?: error("COOKIE_JSON")
    }
    private fun space() { while (at < input.length && input[at] in " \t\r\n") at++ }
    private fun peek(c: Char): Boolean { space(); return at < input.length && input[at] == c }
    private fun take(c: Char) { require(peek(c)) { "COOKIE_JSON" }; at++ }
    private fun string(): String {
        take('"')
        val result = StringBuilder()
        while (at < input.length) {
            val c = input[at++]
            if (c == '"') return result.toString()
            require(c.code >= 0x20) { "COOKIE_JSON" }
            if (c != '\\') result.append(c) else {
                require(at < input.length) { "COOKIE_JSON" }
                when (val escaped = input[at++]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        require(at + 4 <= input.length) { "COOKIE_JSON" }
                        val hex = input.substring(at, at + 4)
                        require(hex.all { it in "0123456789abcdefABCDEF" }) { "COOKIE_JSON" }
                        result.append(hex.toInt(16).toChar()); at += 4
                    }
                    else -> error("COOKIE_JSON")
                }
            }
        }
        error("COOKIE_JSON")
    }
}
