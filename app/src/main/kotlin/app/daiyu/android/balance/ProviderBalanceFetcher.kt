package app.daiyu.android.balance

import org.json.JSONTokener
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * URL resolution, preset units and spaced binary/path expression algorithm retained from
 * docs/reference-preserved/model/.../ProviderBalanceFetcher.kt (f001fb82).
 * Transport is the existing independent, cancellable BalanceHttpQuery, not a model client.
 */
internal object ProviderBalanceFetcher {
    const val TOTAL_TIMEOUT_MS = 15_000L
    private val binaryExpr = Regex("""^(.+?)\s+([+\-*/])\s+(.+)$""")

    fun resolveBalanceUrl(baseUrl: String, apiPath: String, preset: String = BalanceOption.PRESET_CUSTOM): String {
        val path = apiPath.trim()
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) return path
        val normalizedPath = path.removePrefix("/")
        val normalizedBase = if (preset == BalanceOption.PRESET_NEW_API || preset == BalanceOption.PRESET_DEEPSEEK ||
            isOriginRelativeUserApi(normalizedPath)) originFromOpenAiBaseUrl(baseUrl) else baseUrl.trim().removeSuffix("/")
        return "$normalizedBase/$normalizedPath"
    }

    fun originFromOpenAiBaseUrl(baseUrl: String): String {
        val url = baseUrl.trim().removeSuffix("/")
        for (suffix in listOf("/compatible-mode/v1", "/openai/v1", "/v1beta", "/v1")) {
            if (url.endsWith(suffix, true)) return url.dropLast(suffix.length)
        }
        return url
    }

    private fun isOriginRelativeUserApi(path: String): Boolean {
        val normalized = path.lowercase(Locale.ROOT)
        return normalized.startsWith("api/user") || normalized.startsWith("api/token")
    }

    fun extractValue(body: String, resultPath: String): String {
        // The legacy implementation parses JSON even for a literal expression.
        BalanceJsonSyntax(body).select("/__p05_syntax_validation__")
        return evaluateExpression(body, resultPath.trim())
    }

    // Deliberately retains legacy right-recursive spaced binary semantics (not JS or a new parser).
    fun evaluateExpression(body: String, expression: String): String {
        val trimmed = expression.trim()
        trimmed.toDoubleOrNull()?.let { return formatNumber(it) }
        val binary = binaryExpr.matchEntire(trimmed)
        if (binary != null) {
            val left = evaluateExpression(body, binary.groupValues[1]).toDoubleOrNull() ?: error("Invalid balance operand")
            val right = evaluateExpression(body, binary.groupValues[3]).toDoubleOrNull() ?: error("Invalid balance operand")
            val value = when (binary.groupValues[2]) {
                "+" -> left + right
                "-" -> left - right
                "*" -> left * right
                "/" -> if (right == 0.0) error("Division by zero") else left / right
                else -> error("Unsupported operator")
            }
            return formatNumber(value)
        }
        val segments = tokenizeJsonPath(expression)
        // Strict bounded parser preserves decimal scalar text and the frozen Field/Index distinction.
        val raw = BalanceJsonSyntax(body).selectPath(segments) ?: error("JSON path not found")
        return when {
            raw.startsWith('"') -> JSONTokener(raw).nextValue() as String
            raw.startsWith('{') || raw.startsWith('[') -> error("Balance path is not a scalar")
            else -> raw
        }
    }

    fun tokenizeJsonPath(path: String): List<JsonPathSegment> {
        val segments = mutableListOf<JsonPathSegment>()
        val source = path.trim()
        var index = 0
        while (index < source.length) {
            while (index < source.length && source[index] == '.') index++
            if (index >= source.length) break
            if (source[index] == '[') {
                val close = source.indexOf(']', startIndex = index)
                if (close <= index + 1) error("Invalid JSON path index")
                val raw = source.substring(index + 1, close).trim()
                val arrayIndex = raw.toIntOrNull() ?: error("Invalid JSON path index")
                segments += JsonPathSegment.Index(arrayIndex)
                index = close + 1
                continue
            }
            val start = index
            while (index < source.length && source[index] != '.' && source[index] != '[') index++
            val name = source.substring(start, index).trim()
            if (name.isEmpty()) continue
            segments += name.toIntOrNull()?.let(JsonPathSegment::Index) ?: JsonPathSegment.Field(name)
        }
        if (segments.isEmpty()) error("JSON path not found")
        return segments
    }

    private fun formatNumber(value: Double): String {
        require(value.isFinite())
        val asLong = value.toLong()
        return if (value == asLong.toDouble()) asLong.toString() else value.toString()
    }
}

internal sealed interface JsonPathSegment {
    data class Field(val name: String) : JsonPathSegment
    data class Index(val index: Int) : JsonPathSegment
}

/** Frozen visual policy: locale grouping and two decimals; actual money remains BigDecimal. */
internal fun formatBalanceDisplay(raw: String): String {
    val trimmed = raw.trim()
    val number = trimmed.toBigDecimalOrNull() ?: return trimmed
    return DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(number)
}
