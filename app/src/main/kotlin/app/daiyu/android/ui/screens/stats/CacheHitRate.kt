package app.daiyu.android.ui.screens.stats

import java.text.NumberFormat
import java.util.Locale

internal fun formatCacheHitRate(cachedTokens: Long, inputTokens: Long, locale: Locale = Locale.getDefault()): String {
    if (inputTokens <= 0L) return "—"
    return NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        // cachedTokens is a subset of inputTokens in every provider's normalized usage, so a
        // ratio above 1 means the data is inconsistent; clamp instead of printing 380%.
    }.format((cachedTokens.coerceIn(0L, inputTokens).toDouble() / inputTokens.toDouble()))
}
