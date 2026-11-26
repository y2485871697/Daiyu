package app.daiyu.android.domain.context

import app.daiyu.android.domain.ModelEvent

/** Codex caps the configured trigger at 90% of the full window. Daiyu configures 80%. */
object CodexContextPolicy {
    fun autoCompactLimit(window: Long, configuredPercent: Int = 80): Long? =
        window.takeIf { it > 0 }?.let { (it / 100) * minOf(configuredPercent, 90) +
            ((it % 100) * minOf(configuredPercent, 90) + 99) / 100 }

    /** The meter and automatic gate share the same normalized cloud input (cache is a subset). */
    fun acceptedInput(usage: ModelEvent.Usage, window: Long?): Long? {
        val input = usage.inputTokens?.takeIf { it >= 0 } ?: return null
        if (window != null && window > 0 && input.toDouble() > window.toDouble() * 1.3) return null
        val cached = usage.cachedTokens
        if (cached != null && (cached > input || (window != null && window > 0 && cached > window))) return null
        return input
    }
}

/** Model-visible UTF-8 bytes, with one ceiling per item, not the serialized transport envelope. */
object CodexTokenEstimate {
    fun bytes(value: String): Long {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            bytes += when {
                char <= '\u007f' -> 1
                char <= '\u07ff' -> 2
                Character.isHighSurrogate(char) && index < value.length && Character.isLowSurrogate(value[index]) -> {
                    index++
                    4
                }
                Character.isSurrogate(char) -> 1 // JVM UTF-8 replacement for a malformed UTF-16 unit.
                else -> 3
            }
        }
        return bytes
    }
    fun tokens(bytes: Long): Long = bytes / 4 + if (bytes % 4 == 0L) 0 else 1
    fun text(value: String): Long = tokens(bytes(value))
    const val RECENT_HISTORY_TOKENS = 20_000L
    const val RESIZED_IMAGE_BYTES = 7373L
    val resizedImageTokens get() = tokens(RESIZED_IMAGE_BYTES)
}
