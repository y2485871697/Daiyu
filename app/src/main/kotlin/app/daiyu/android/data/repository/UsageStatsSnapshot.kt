package app.daiyu.android.data.repository

import java.time.LocalDate
import java.util.Locale

internal data class UsageStatsSnapshot(
    val heatmapAvailable: Boolean = false,
    val currentDataAvailable: Boolean = false,
    val tokenAccountingAvailable: Boolean = false,
    val lifetimeAccountingAvailable: Boolean = false,
    val isLoading: Boolean = false,
    val currentConversations: Int = 0,
    val lifetimeConversations: Int = 0,
    val currentMessages: Int = 0,
    val lifetimeMessages: Int = 0,
    val currentInputTokens: Long = 0L,
    val lifetimeInputTokens: Long = 0L,
    val currentOutputTokens: Long = 0L,
    val lifetimeOutputTokens: Long = 0L,
    val currentCachedTokens: Long = 0L,
    val lifetimeCachedTokens: Long = 0L,
    val currentCacheCreationTokens: Long = 0L,
    val lifetimeCacheCreationTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
    val modelUsage: ModelUsageSnapshot = ModelUsageSnapshot(),
    val currentAccountingTokens: UsageTokenMetrics? = null,
    val lifetimeAccountingTokens: UsageTokenMetrics? = null,
    val unknownConversationDates: Int = 0,
    val unattributedUsageRequests: Int = 0,
    val accountingFailure: Boolean = false,
    val recordedUsage: RecordedUsageStats? = null,
    val attributedUsage: app.daiyu.android.model.usage.AttributedUsageStats? = null,
) {
    val recordedProjection: RecordedUsageProjection? get() = attributedUsage?.let(::RecordedUsageProjection)
    val overviewTokens: UsageTokenMetrics get() = lifetimeAccountingTokens ?: recordedProjection?.tokens ?: UsageTokenMetrics()
    fun modelTokens(filtered: ModelUsageSnapshot): UsageTokenMetrics {
        if (!tokenAccountingAvailable) return UsageTokenMetrics()
        val models = filtered.models
        fun metric(complete: (ModelUsageModelUi) -> Boolean, value: (ModelUsageModelUi) -> Long): Long? =
            if (models.all(complete) && models.tokenSumAvailable(value)) models.safeTokenSum(value) else null
        val input = metric({ it.inputComplete }, { it.inputTokens })
        val output = metric({ it.outputComplete }, { it.outputTokens })
        return UsageTokenMetrics(input, output, metric({ it.cachedComplete }, { it.cachedTokens }),
            metric({ it.creationComplete }, { it.cacheCreationTokens }),
            if (input == null || output == null || input > Long.MAX_VALUE - output) null else input + output)
    }
    val totalConversations: Int get() = currentConversations
    val totalMessages: Int get() = currentMessages
    val totalInputTokens: Long get() = lifetimeInputTokens
    val currentFreshInputTokens: Long get() = (currentInputTokens - currentCachedTokens - currentCacheCreationTokens).coerceAtLeast(0L)
    val lifetimeFreshInputTokens: Long get() = (lifetimeInputTokens - lifetimeCachedTokens - lifetimeCacheCreationTokens).coerceAtLeast(0L)
    val totalOutputTokens: Long get() = lifetimeOutputTokens
    val totalCachedTokens: Long get() = lifetimeCachedTokens
}

internal fun heatmapAlpha(count: Int, q1: Int, q2: Int, q3: Int, isFuture: Boolean): Float = when {
    isFuture -> -1f
    count <= 0 -> 0f
    count <= q1 -> 0.25f
    count <= q2 -> 0.5f
    count <= q3 -> 0.75f
    else -> 1f
}

internal fun heatmapQuartiles(counts: Collection<Int>): Triple<Int, Int, Int> {
    val active = counts.filter { it > 0 }.sorted()
    if (active.isEmpty()) return Triple(1, 2, 3)
    fun at(fraction: Double, fallback: Int): Int =
        active.getOrElse((active.size * fraction).toInt().coerceAtMost(active.lastIndex)) { fallback }
    return Triple(at(0.25, 1), at(0.50, 2), at(0.75, 3))
}

internal fun formatStatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(Locale.US, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(Locale.US, count / 1_000.0)
    else -> count.toString()
}

internal fun formatTokenCount(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(Locale.US, count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(Locale.US, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(Locale.US, count / 1_000.0)
    else -> count.toString()
}
