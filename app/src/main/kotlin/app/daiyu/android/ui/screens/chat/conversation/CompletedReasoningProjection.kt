package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.domain.RunStatus
import org.json.JSONObject

/** Read-only compatibility for the old stream-ID/final-ID pair within one source segment. */
internal fun normalizeCompletedReasoning(message: Message): Message {
    if (message.role != Message.ASSISTANT_ROLE || message.status != RunStatus.COMPLETED || message.reportedReasoning.size < 2 ||
        message.reportedReasoning.any { it.activeSinceNanos != null }) return message
    val protocol = try { message.protocolItems.map(::JSONObject) } catch (_: Exception) { return message }
    if (protocol.isEmpty() || message.reportedReasoning.map { it.itemId }.distinct().size != message.reportedReasoning.size) return message
    val ids = protocol.mapNotNull { it.optString("id").takeIf(String::isNotBlank) }
    if (ids.distinct().size != ids.size) return message
    fun matches(value: ReportedReasoning, id: String) = id.isNotBlank() &&
        (value.itemId == id || value.itemId.endsWith(":" + id))
    fun protocolIndex(value: ReportedReasoning): Int? = protocol.indices.filter {
        matches(value, protocol[it].optString("id"))
    }.singleOrNull()
    val replacements = mutableMapOf<String, ReportedReasoning>()
    val removed = mutableSetOf<String>()
    for ((text, group) in message.reportedReasoning.groupBy { it.text }) {
        if (text.isBlank() || group.size != 2 || group.map { it.itemId }.distinct().size != 2) continue
        val timed = group.singleOrNull { it.elapsedMillis != null } ?: continue
        val final = group.singleOrNull { it.elapsedMillis == null } ?: continue
        if (protocol.any { matches(timed, it.optString("id")) }) continue
        val position = protocolIndex(final) ?: continue
        val item = protocol[position]
        if (item.optString("type") != "reasoning") continue
        val summary = item.optJSONArray("summary") ?: continue
        val summaryText = (0 until summary.length()).mapNotNull { index ->
            summary.optJSONObject(index)?.takeIf { it.optString("type") == "summary_text" }?.optString("text")
        }.joinToString("")
        if (summaryText != text) continue
        val before = timed.blockOffset ?: continue
        val after = final.blockOffset ?: continue
        if (before !in 0..message.blocks.size || after !in before..message.blocks.size) continue
        if (before != after) {
            // Only the adjacent terminal answer may have arrived before the closing snapshot.
            if (after != before + 1 || after != message.blocks.size) continue
            val answer = message.blocks.getOrNull(before) as? MessageBlock.Text ?: continue
            val answerPosition = protocol.indices.filter {
                protocol[it].optString("type") == "message" && protocol[it].optString("id") == answer.itemId
            }.singleOrNull() ?: continue
            if (answerPosition != position + 1 || answerPosition != protocol.lastIndex ||
                protocol[answerPosition].optString("role") != Message.ASSISTANT_ROLE) continue
        }
        replacements[timed.itemId] = final.copy(elapsedMillis = timed.elapsedMillis, blockOffset = before)
        removed += final.itemId
    }
    if (replacements.isEmpty()) return message
    return message.copy(reportedReasoning = message.reportedReasoning.mapNotNull {
        if (it.itemId in removed) null else replacements[it.itemId] ?: it
    })
}
