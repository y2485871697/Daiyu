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
        fun blocks(values: List<MessageBlock>) {
            field(values.size.toString())
            values.forEach { b -> when (b) {
                is MessageBlock.Text -> { field("text"); field(b.itemId); field(b.text) }
                is MessageBlock.Tool -> { field("tool"); field(b.invocation.id.value); field(b.invocation.name)
                    field(b.invocation.arguments); field(if (b.result == null) "null" else "result"); field(b.result.orEmpty()) }
            } }
        }
        messages.forEach { m ->
            field(m.id.value); field(m.conversationId.value); field(m.runId.value); field(m.role)
            field(m.status.name); field(if (m.error == null) "null" else "error"); field(m.error.orEmpty())
            blocks(m.blocks)
            field(m.protocolItems.size.toString()); m.protocolItems.forEach(::field)
            // Do not append even a zero count for legacy rows: persisted archive revisions
            // with empty segments must remain byte-for-byte compatible.
            if (m.historySegments.isNotEmpty()) {
                field("history_segments-v1"); field(m.historySegments.size.toString())
                m.historySegments.forEach { segment ->
                    field(segment.messageId.value); field(segment.role); blocks(segment.blocks)
                    field(segment.protocolItems.size.toString()); segment.protocolItems.forEach(::field)
                }
            }
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

    /** Sending capacity is independent of automatic compression. Unknown stays unknown. */
    val sendBudgetTokens: Long? get() = contextTokens?.minus(reserveTokens)
    fun fitsSendBudget(estimatedTokens: Long): Boolean {
        require(estimatedTokens >= 0)
        return sendBudgetTokens?.let { estimatedTokens <= it } ?: true
    }
}

/** Deliberately an estimate, never presented as provider usage. UTF-8 bytes is conservative. */
fun estimateTokens(messages: List<Message>): Long = MessageHistory.expand(messages).sumOf { m ->
    16L + m.blocks.sumOf { b -> when (b) {
        is MessageBlock.Text -> b.text.toByteArray(Charsets.UTF_8).size.toLong()
        is MessageBlock.Tool -> b.invocation.arguments.toByteArray(Charsets.UTF_8).size.toLong() +
            (b.result?.toByteArray(Charsets.UTF_8)?.size ?: 0) + b.invocation.name.toByteArray(Charsets.UTF_8).size + 24L
    } } + m.protocolItems.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
}

/** Physical cuts must keep an owner and every present standalone supplement together.
 * Missing standalone rows are legal in 03; their content remains in the owner's segments. */
fun isHistoryClosedPrefix(messages: List<Message>, count: Int): Boolean {
    require(count in 0..messages.size)
    MessageHistory.expand(messages) // fail closed on conflicting identities/content
    val positions = messages.withIndex().associate { (i, m) -> Triple(m.conversationId, m.runId, m.id) to i }
    return messages.withIndex().all { (index, owner) ->
        owner.historySegments.filter { it.role == Message.USER_ROLE }.all { segment ->
            val row = positions[Triple(owner.conversationId, owner.runId, segment.messageId)]
            row == null || (index < count) == (row < count)
        }
    }
}

/** Revalidate persisted/injected physical cuts, not just cuts chosen by this policy. */
fun isCompleteContextPrefix(messages: List<Message>, count: Int): Boolean {
    if (!isHistoryClosedPrefix(messages, count)) return false
    val prefix = messages.take(count)
    return prefix.all { it.status == RunStatus.COMPLETED } &&
        prefix.map { it.runId }.toSet().intersect(messages.drop(count).map { it.runId }.toSet()).isEmpty() &&
        MessageHistory.expand(prefix).all { m -> m.blocks.none { it is MessageBlock.Tool && it.result == null } }
}

/** Only complete user/assistant turns; never split a call from its result or compress a live run. */
fun compressiblePrefix(messages: List<Message>, keepTurns: Int): Int {
    require(keepTurns >= 1)
    if (messages.any { it.historySegments.isNotEmpty() }) return segmentedPrefix(messages, keepTurns)
    val ends = mutableListOf<Int>()
    val atomicEnds = mutableListOf<Int>()
    val lastRunIndexes = messages.withIndex().associate { it.value.runId to it.index }
    var furthestRunIndex = -1
    var i = 0
    while (i + 1 < messages.size) {
        val user = messages[i]; val assistant = messages[i + 1]
        if (user.role != "user" || user.status != RunStatus.COMPLETED || assistant.role != "assistant" ||
            assistant.status != RunStatus.COMPLETED || user.runId != assistant.runId ||
            user.blocks.any { it is MessageBlock.Tool && it.result == null } ||
            assistant.blocks.any { it is MessageBlock.Tool && it.result == null }) break
        i += 2; ends.add(i)
        // A completed pair is not a complete run if more of that run remains in the tail.
        furthestRunIndex = maxOf(furthestRunIndex, lastRunIndexes.getValue(user.runId))
        if (furthestRunIndex < i) atomicEnds.add(i)
    }
    val tailBoundary = if (ends.size > keepTurns) ends[ends.size - keepTurns - 1] else 0
    return atomicEnds.lastOrNull { it <= tailBoundary } ?: 0
}

/** Segment continuations need not form physical user/assistant pairs. Treat a whole
 * contiguous run as atomic, retaining at least keepTurns complete runs (conservative). */
private fun segmentedPrefix(messages: List<Message>, keepTurns: Int): Int {
    MessageHistory.expand(messages)
    val ends = mutableListOf<Int>()
    var start = 0
    while (start < messages.size) {
        val run = messages[start].runId
        var end = start + 1
        while (end < messages.size && messages[end].runId == run) end++
        val group = messages.subList(start, end)
        if (group.first().role != Message.USER_ROLE || group.none { it.role == Message.ASSISTANT_ROLE } ||
            group.any { it.status != RunStatus.COMPLETED } ||
            MessageHistory.expand(group).any { m -> m.blocks.any { it is MessageBlock.Tool && it.result == null } }) break
        ends.add(end)
        start = end
    }
    val limit = if (ends.size > keepTurns) ends[ends.size - keepTurns - 1] else 0
    return ends.lastOrNull { end -> end <= limit && isHistoryClosedPrefix(messages, end) &&
        messages.take(end).map { it.runId }.toSet().intersect(messages.drop(end).map { it.runId }.toSet()).isEmpty()
    } ?: 0
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
