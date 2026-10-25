package app.daiyu.android.data.repository

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class ModelUsageSnapshot(
    val providers: List<ModelUsageProviderUi> = emptyList(),
) {
    val models: List<ModelUsageModelUi> get() = providers.flatMap { it.models }
    val totalInputTokens: Long get() = providers.safeTokenSum { it.inputTokens }
    val totalFreshInputTokens: Long get() = providers.safeTokenSum { it.freshInputTokens }
    val totalOutputTokens: Long get() = providers.safeTokenSum { it.outputTokens }
    val totalCachedTokens: Long get() = providers.safeTokenSum { it.cachedTokens }
    val totalCacheCreationTokens: Long get() = providers.safeTokenSum { it.cacheCreationTokens }
    val totalTokens: Long get() = listOf(totalInputTokens, totalOutputTokens).safeTokenSum { it }

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
    val inputTokens: Long get() = models.safeTokenSum { it.inputTokens }
    val freshInputTokens: Long get() = models.safeTokenSum { it.freshInputTokens }
    val outputTokens: Long get() = models.safeTokenSum { it.outputTokens }
    val cachedTokens: Long get() = models.safeTokenSum { it.cachedTokens }
    val cacheCreationTokens: Long get() = models.safeTokenSum { it.cacheCreationTokens }
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
    val inputReported: Boolean = true,
    val outputReported: Boolean = true,
    val cachedReported: Boolean = true,
    val creationReported: Boolean = true,
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
    val inputComplete: Boolean = true,
    val outputComplete: Boolean = true,
    val cachedComplete: Boolean = true,
    val creationComplete: Boolean = true,
) {
    /** Uncached prefix: full prompt minus reads and writes. */
    val freshInputTokens: Long
        get() = ((inputTokens - cachedTokens).coerceAtLeast(0L) - cacheCreationTokens).coerceAtLeast(0L)
    /** Fresh input, output, cache reads and cache writes. */
    val totalTokens: Long
        get() = listOf(inputTokens, outputTokens).safeTokenSum { it }
    val dailyAverageTokens: Long
        get() = if (activeDays <= 0) 0L else freshInputTokens / activeDays
    val conversationAverageTokens: Long
        get() = if (conversationCount <= 0) 0L else freshInputTokens / conversationCount

    fun filtered(startMillis: Long?, endMillis: Long?): ModelUsageModelUi? {
        if (startMillis == null && endMillis == null) return this
        val matched = events.collapsedByRound().filter { event ->
            (startMillis == null || event.atMillis >= startMillis) &&
                (endMillis == null || event.atMillis <= endMillis)
        }
        if (matched.isEmpty()) return null
        val conversations = matched.mapNotNull { it.conversationId }.toSet()
        val days = matched.map { eventDay(it.atMillis) }.toSet()
        val filteredInput = matched.safeTokenSum { it.inputTokens }
        return copy(
            inputTokens = filteredInput,
            outputTokens = matched.safeTokenSum { it.outputTokens },
            cachedTokens = matched.safeTokenSum { it.cachedTokens },
            cacheCreationTokens = matched.safeTokenSum { it.cacheCreationTokens },
            conversationCount = conversations.size,
            activeDays = days.size,
            events = matched,
            inputComplete = matched.all { it.inputReported } && matched.tokenSumAvailable { it.inputTokens }, outputComplete = matched.all { it.outputReported } && matched.tokenSumAvailable { it.outputTokens },
            cachedComplete = matched.all { it.cachedReported } && matched.tokenSumAvailable { it.cachedTokens }, creationComplete = matched.all { it.creationReported } && matched.tokenSumAvailable { it.cacheCreationTokens },
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
            val key = event.requestId?.let { "request:$conversation:$it" } ?: "legacy:$conversation#$round"
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

internal fun <T> Iterable<T>.tokenSumAvailable(value: (T) -> Long): Boolean {
    var sum = 0L
    for (item in this) { val n = value(item); if (n > Long.MAX_VALUE - sum) return false; sum += n }
    return true
}
internal fun <T> Iterable<T>.safeTokenSum(value: (T) -> Long): Long {
    var sum = 0L
    for (item in this) { val n = value(item); if (n > Long.MAX_VALUE - sum) return Long.MAX_VALUE; sum += n }
    return sum
}
