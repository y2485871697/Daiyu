package app.daiyu.android.data.repository

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class ModelUsageSnapshot(
    val providers: List<ModelUsageProviderUi> = emptyList(),
) {
    val totalInputTokens: Long get() = providers.sumOf { it.inputTokens }
    val totalFreshInputTokens: Long get() = providers.sumOf { it.freshInputTokens }
    val totalOutputTokens: Long get() = providers.sumOf { it.outputTokens }
    val totalCachedTokens: Long get() = providers.sumOf { it.cachedTokens }
    val totalCacheCreationTokens: Long get() = providers.sumOf { it.cacheCreationTokens }
    val totalTokens: Long get() = totalInputTokens + totalOutputTokens

    fun filtered(startMillis: Long?, endMillis: Long?): ModelUsageSnapshot {
        if (startMillis == null && endMillis == null) return this
        return ModelUsageSnapshot(
            providers = providers.mapNotNull { provider ->
                val models = provider.models.mapNotNull { it.filtered(startMillis, endMillis) }
                if (models.isEmpty()) null else provider.copy(models = models)
            },
        )
    }
}

internal data class ModelUsageProviderUi(
    val id: String,
    val name: String,
    val models: List<ModelUsageModelUi>,
) {
    val inputTokens: Long get() = models.sumOf { it.inputTokens }
    val freshInputTokens: Long get() = models.sumOf { it.freshInputTokens }
    val outputTokens: Long get() = models.sumOf { it.outputTokens }
    val cachedTokens: Long get() = models.sumOf { it.cachedTokens }
    val cacheCreationTokens: Long get() = models.sumOf { it.cacheCreationTokens }
}

internal data class ModelUsageEvent(
    val atMillis: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long = 0L,
    val cacheCreationTokens: Long = 0L,
    val conversationId: String? = null,
    val round: Int? = null,
    val requestId: String? = null,
)

internal data class ModelUsageModelUi(
    val id: String,
    val displayName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val conversationCount: Int,
    val activeDays: Int,
    val events: List<ModelUsageEvent> = emptyList(),
    val cachedTokens: Long = 0L,
    val cacheCreationTokens: Long = 0L,
) {
    /** Uncached prefix: full prompt minus reads and writes. */
    val freshInputTokens: Long
        get() = (inputTokens - cachedTokens - cacheCreationTokens).coerceAtLeast(0L)
    /** Fresh input, output, cache reads and cache writes. */
    val totalTokens: Long
        get() = inputTokens + outputTokens
    val dailyAverageTokens: Long
        get() = if (activeDays <= 0) 0L else freshInputTokens / activeDays
    val conversationAverageTokens: Long
        get() = if (conversationCount <= 0) 0L else freshInputTokens / conversationCount

    fun filtered(startMillis: Long?, endMillis: Long?): ModelUsageModelUi? {
        if (startMillis == null && endMillis == null) return this
        val matched = events.filter { event ->
            (startMillis == null || event.atMillis >= startMillis) &&
                (endMillis == null || event.atMillis <= endMillis)
        }.collapsedByRound()
        if (matched.isEmpty()) return null
        val conversations = matched.mapNotNull { it.conversationId }.toSet()
        val days = matched.map { eventDay(it.atMillis) }.toSet()
        val filteredInput = matched.sumOf { it.inputTokens }
        return copy(
            inputTokens = filteredInput,
            outputTokens = matched.sumOf { it.outputTokens },
            cachedTokens = matched.sumOf { it.cachedTokens },
            cacheCreationTokens = matched.sumOf { it.cacheCreationTokens },
            conversationCount = conversations.size,
            activeDays = days.size,
            events = matched,
        )
    }
}

private fun eventDay(atMillis: Long): LocalDate =
    Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDate()


internal fun List<ModelUsageEvent>.collapsedByRound(): List<ModelUsageEvent> {
    if (isEmpty()) return this
    val kept = ArrayList<ModelUsageEvent>(size)
    val indexByKey = HashMap<String, Int>()
    forEach { event ->
        val round = event.round
        val conversation = event.conversationId
        if (event.requestId.isNullOrBlank() && (round == null || conversation.isNullOrBlank())) {
            kept += event
        } else {
            val key = event.requestId?.let { "request:$it" } ?: "legacy:$conversation#$round"
            val existing = indexByKey[key]
            if (existing == null) {
                indexByKey[key] = kept.size
                kept += event
            } else {
                kept[existing] = event
            }
        }
    }
    return kept
}
