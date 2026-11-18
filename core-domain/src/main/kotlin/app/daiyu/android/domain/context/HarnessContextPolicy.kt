package app.daiyu.android.domain.context

/** DeepSeek Harness ca33fd68 compaction-basic policy; see THIRD_PARTY/deepseek-harness. */
object HarnessContextPolicy {
    const val HEADROOM_TOKENS = 65_536L
    const val SUMMARY_MAX_TOKENS = 65_536
    const val COMPACTION_RETRIES = 1
    const val MAX_OVERFLOW_RETRIES = 1

    data class Spec(val thresholdTokens: Long, val retainTokens: Long)

    fun resolve(window: Long, reservedCompletionTokens: Long, thresholdPercent: Int = 80,
        headroomTokens: Long = HEADROOM_TOKENS, retainPercent: Int = 16): Spec {
        require(window > 0 && reservedCompletionTokens >= 0 && headroomTokens >= 0)
        require(thresholdPercent in 1..99 && retainPercent in 0 until thresholdPercent)
        val messageBudget = window - reservedCompletionTokens
        require(messageBudget > 0) { "输出预留后没有消息容量" }
        val pressureBudget = messageBudget - headroomTokens
        require(pressureBudget > 0) { "输出预留和压缩余量后没有压力容量" }
        val threshold = minOf(window * thresholdPercent / 100, pressureBudget)
        val retained = messageBudget * retainPercent / 100
        require(retained < threshold) { "历史保留预算必须小于压缩触发阈值" }
        return Spec(threshold, retained)
    }
}

/** Fixed UTF-16 density, framing, and signed-anchor math from dsh-token-meter. */
object HarnessTokenEstimate {
    const val BLOCK_OVERHEAD = 4L
    const val ROLE_OVERHEAD = 4L
    fun text(value: String): Long = (value.length.toLong() + 3) / 4
    fun block(value: String): Long = BLOCK_OVERHEAD + text(value)
    fun toolCall(name: String, arguments: String): Long = BLOCK_OVERHEAD + text(name) + text(arguments)
    fun system(rendered: String): Long = if (rendered.isEmpty()) 0 else ROLE_OVERHEAD + text(rendered)
    fun tools(serialized: String?, empty: Boolean = false): Long =
        if (serialized == null || empty) 0 else BLOCK_OVERHEAD + text(serialized)

    /** Measurement uses normalized input (including cache) plus output, not prompt pressure. */
    fun measure(current: Long, anchorEstimate: Long? = null, reportedTotal: Long? = null): Long {
        require(current >= 0 && (anchorEstimate == null || anchorEstimate >= 0) &&
            (reportedTotal == null || reportedTotal >= 0))
        if (anchorEstimate == null) return current
        val baseline = maxOf(anchorEstimate, reportedTotal ?: anchorEstimate)
        return (baseline + current - anchorEstimate).coerceAtLeast(0)
    }

    /** UI pressure is prompt-only plus surface movement, not billed total usage. */
    fun project(input: Long, sampledSurface: Long, currentSurface: Long): Long {
        require(input >= 0 && sampledSurface >= 0 && currentSurface >= 0)
        return (input + currentSurface - sampledSurface).coerceAtLeast(0)
    }
}
