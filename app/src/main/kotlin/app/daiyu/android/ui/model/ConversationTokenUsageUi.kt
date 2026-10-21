package app.daiyu.android.ui.model

/** Provider input includes cache reads and writes; missing observations remain unknown. */
data class ConversationTokenUsageUi(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedTokens: Long? = null,
    val cacheCreationTokens: Long? = null,
    val requestCount: Long = 0,
) {
    val totalTokens: Long? get() {
        val input = inputTokens ?: return null
        val output = outputTokens ?: return null
        return if (input >= 0 && output >= 0 && input <= Long.MAX_VALUE - output) input + output else null
    }
    val freshInputTokens: Long? get() {
        val input = inputTokens ?: return null
        val cached = cachedTokens ?: return null
        val creation = cacheCreationTokens ?: return null
        if (input < 0 || cached < 0 || creation < 0 || cached > input || creation > input - cached) return null
        return input - cached - creation
    }
    val hasUsage: Boolean get() = requestCount > 0 ||
        listOf(inputTokens, outputTokens, cachedTokens, cacheCreationTokens).any { it != null }
    val cachePercent: Double?
        get() = inputTokens?.takeIf { it > 0 }?.let { input ->
            cachedTokens?.takeIf { it >= 0 && it <= input }?.let { it.toDouble() / input.toDouble() * 100.0 }
        }
}
