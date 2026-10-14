package app.daiyu.android.domain.context

import app.daiyu.android.domain.*
import java.security.MessageDigest

/** A revision includes content and protocol, not just IDs: corrections invalidate summaries. */
object ContextRevision {
    fun digest(messages: List<Message>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
        }
        messages.forEach { m ->
            field(m.id.value); field(m.conversationId.value); field(m.runId.value); field(m.role)
            field(m.status.name); field(if (m.error == null) "null" else "error"); field(m.error.orEmpty())
            field(m.blocks.size.toString())
            m.blocks.forEach { b -> when (b) {
                is MessageBlock.Text -> { field("text"); field(b.itemId); field(b.text) }
                is MessageBlock.Tool -> { field("tool"); field(b.invocation.id.value); field(b.invocation.name)
                    field(b.invocation.arguments); field(if (b.result == null) "null" else "result"); field(b.result.orEmpty()) }
            } }
            field(m.protocolItems.size.toString()); m.protocolItems.forEach(::field)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

data class CompressionPolicy(val enabled: Boolean = false, val contextTokens: Long? = null,
    val triggerPercent: Int = 80, val reserveTokens: Long = 4096, val keepTurns: Int = 4) {
    init { require(contextTokens == null || contextTokens in 1024..10_000_000)
        require(triggerPercent in 10..95 && reserveTokens >= 0 && keepTurns in 1..100)
        require(contextTokens == null || reserveTokens < contextTokens) }
    /** Unknown provider window remains unknown; never guess a capacity for automatic compression. */
    fun shouldCompress(estimatedTokens: Long): Boolean = enabled && contextTokens != null &&
        estimatedTokens >= (contextTokens - reserveTokens) * triggerPercent / 100
}

/** Deliberately an estimate, never presented as provider usage. UTF-8 bytes is conservative. */
fun estimateTokens(messages: List<Message>): Long = messages.sumOf { m ->
    16L + m.blocks.sumOf { b -> when (b) {
        is MessageBlock.Text -> b.text.toByteArray(Charsets.UTF_8).size.toLong()
        is MessageBlock.Tool -> b.invocation.arguments.toByteArray(Charsets.UTF_8).size.toLong() +
            (b.result?.toByteArray(Charsets.UTF_8)?.size ?: 0) + b.invocation.name.length + 24L
    } } + m.protocolItems.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
}

/** Only complete user/assistant turns; never split a call from its result or compress a live run. */
fun compressiblePrefix(messages: List<Message>, keepTurns: Int): Int {
    require(keepTurns >= 1)
    val ends = mutableListOf<Int>()
    var i = 0
    while (i + 1 < messages.size) {
        val user = messages[i]; val assistant = messages[i + 1]
        if (user.role != "user" || user.status != RunStatus.COMPLETED || assistant.role != "assistant" ||
            assistant.status != RunStatus.COMPLETED || user.runId != assistant.runId ||
            assistant.blocks.filterIsInstance<MessageBlock.Tool>().any { it.result == null }) break
        ends.add(i + 2); i += 2
    }
    return if (ends.size > keepTurns) ends[ends.size - keepTurns - 1] else 0
}

data class UsageRecord(val requestId: String, val runId: String, val branchId: String,
    val inputTokens: Long?, val outputTokens: Long?, val cachedTokens: Long? = null,
    val reasoningTokens: Long? = null) {
    init {
        require(requestId.isNotBlank() && requestId.length <= 200 && runId.isNotBlank() && runId.length <= 200)
        require(branchId.isNotBlank() && branchId.length <= 200)
        listOf(inputTokens, outputTokens, cachedTokens, reasoningTokens).forEach { require(it == null || it in 0..1_000_000_000_000) }
        require(cachedTokens == null || inputTokens != null && cachedTokens <= inputTokens)
        require(reasoningTokens == null || outputTokens != null && reasoningTokens <= outputTokens)
    }
}
data class UsageTotals(val requests: Long, val reportedRequests: Long, val inputTokens: Long,
    val outputTokens: Long, val cachedTokens: Long, val reasoningTokens: Long) {
    val totalTokens: Long get() = Math.addExact(inputTokens, outputTokens)
    val incomplete: Boolean get() = reportedRequests != requests
}
fun aggregateUsage(records: List<UsageRecord>): UsageTotals {
    val unique = records.groupBy { it.requestId }.map { (_, values) ->
        require(values.distinct().size == 1) { "Conflicting usage receipt" }; values.first()
    }
    fun sum(value: (UsageRecord) -> Long?) = unique.fold(0L) { total, row -> Math.addExact(total, value(row) ?: 0) }
    return UsageTotals(unique.size.toLong(), unique.count { it.inputTokens != null && it.outputTokens != null }.toLong(),
        sum { it.inputTokens }, sum { it.outputTokens }, sum { it.cachedTokens }, sum { it.reasoningTokens })
}
