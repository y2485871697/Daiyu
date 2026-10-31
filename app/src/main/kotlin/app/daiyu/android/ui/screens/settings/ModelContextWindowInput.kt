package app.daiyu.android.ui.screens.settings

import java.math.BigDecimal

internal enum class ModelContextWindowUnit(val decimalPlaces: Int) {
    K(3), M(6);

    fun next() = if (this == K) M else K
}

internal data class ModelContextWindowInput(val text: String, val unit: ModelContextWindowUnit) {
    fun tokenText(): String? {
        if (text.isBlank()) return ""
        if (text.any { it !in '0'..'9' && it != '.' }) return null
        val number = text.toBigDecimalOrNull() ?: return null
        val tokens = try {
            number.movePointRight(unit.decimalPlaces).intValueExact()
        } catch (_: ArithmeticException) {
            return null
        }
        return tokens.takeIf { it > 0 }?.toString()
    }

    fun switchUnit(): ModelContextWindowInput {
        val next = unit.next()
        val tokens = tokenText()?.toIntOrNull()
        return ModelContextWindowInput(tokens?.let { format(it, next) } ?: text, next)
    }

    companion object {
        fun fromTokens(tokens: String): ModelContextWindowInput {
            val count = tokens.toIntOrNull()
            val unit = if (count != null && count >= 1_000_000) ModelContextWindowUnit.M else ModelContextWindowUnit.K
            return ModelContextWindowInput(count?.let { format(it, unit) } ?: tokens, unit)
        }

        private fun format(tokens: Int, unit: ModelContextWindowUnit): String =
            BigDecimal.valueOf(tokens.toLong(), unit.decimalPlaces).stripTrailingZeros().toPlainString()
    }
}
