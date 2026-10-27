package app.daiyu.android.context

import app.daiyu.android.domain.ModelEvent

/** Codex has no wire output cap. UTF-8 bytes conservatively bound accepted visible text;
 * reported output tokens (including hidden reasoning) are checked when supplied, not estimated.
 * These limits cancel local I/O, not already-running remote generation or its bill. */
internal class SummaryOutputBudget(outputTokens: Int, private val textLimit: Int = outputTokens * 8) {
    private val totalLimit = outputTokens.toLong()
    private val text = mutableMapOf<String, String>()
    private val reasoning = mutableMapOf<String, String>()
    init { require(textLimit > 0) }
    fun observe(event: ModelEvent) {
        when (event) {
            is ModelEvent.TextDelta -> text[event.itemId] = text[event.itemId].orEmpty() + event.text
            is ModelEvent.TextFinal -> text[event.itemId] = event.text
            is ModelEvent.ReasoningDelta -> reasoning[event.itemId] = reasoning[event.itemId].orEmpty() + event.text
            is ModelEvent.ReasoningFinal -> reasoning[event.itemId] = event.text
            is ModelEvent.Usage -> {
                val outputTokens = event.outputTokens
                require(outputTokens == null || outputTokens <= totalLimit) { "摘要输出超过本地预算；保留原文" }
            }
            else -> Unit
        }
        val bytes = byteSize(text.values)
        require(bytes <= textLimit && byteSize(reasoning.values) <= textLimit.toLong() - bytes) { "摘要输出超过本地预算；保留原文" }
    }
    fun validateTerminal(texts: List<String>) {
        val bytes = byteSize(texts) + (texts.size - 1).coerceAtLeast(0)
        require(bytes <= textLimit && byteSize(reasoning.values) <= textLimit.toLong() - bytes) { "摘要输出超过本地预算；保留原文" }
    }
    private fun byteSize(values: Collection<String>): Long = values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
}
