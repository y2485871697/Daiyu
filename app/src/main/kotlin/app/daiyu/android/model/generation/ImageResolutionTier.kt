package app.daiyu.android.model.generation

/** Semantic intent, not a promise of K pixels. Legacy values are read-only aliases. */
internal object ImageResolutionTier {
    private val aliases = mapOf("低" to "low", "中" to "medium", "高" to "high", "超高" to "ultra",
        "1k" to "low", "1.5k" to "medium", "2k" to "high", "4k" to "ultra")
    fun normalize(value: String): String = value.trim().lowercase().let { aliases[it] ?: it }
    fun label(value: String): String = when (normalize(value)) {
        "low" -> "低"; "medium" -> "中"; "high" -> "高"; "ultra" -> "超高"; else -> value
    }
    fun legacy(value: String): String = when (normalize(value)) {
        "low" -> "1k"; "medium" -> "1.5k"; "high" -> "2k"; "ultra" -> "4k"; else -> value
    }
    val values = listOf("low", "medium", "high", "ultra")
}
