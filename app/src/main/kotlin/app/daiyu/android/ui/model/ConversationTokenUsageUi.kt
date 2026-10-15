package app.daiyu.android.ui.model

data class ConversationTokenUsageUi(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    /** Uncached prefix: full prompt minus cache reads and cache writes. */
    val freshInputTokens: Long get() = (inputTokens - cachedTokens - cacheCreationTokens).coerceAtLeast(0L)
    val hasUsage: Boolean get() = inputTokens > 0 || outputTokens > 0 || cachedTokens > 0 || cacheCreationTokens > 0
    val cachePercent: Double?
        get() = if (inputTokens > 0) {
            cachedTokens.toDouble() / inputTokens.toDouble() * 100.0
        } else {
            null
        }
}
