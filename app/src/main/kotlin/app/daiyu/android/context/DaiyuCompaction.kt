package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.DaiyuContextEstimate
import app.daiyu.android.media.NativeScreenImage
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.TerminalToolResultProjection
import org.json.JSONArray
import org.json.JSONObject

internal object DaiyuCompaction {
    private val inlineImage = Regex("data:image/[a-z0-9.+-]+;base64,[a-z0-9+/=\\s]+", RegexOption.IGNORE_CASE)
    private fun text(value: String) = DaiyuContextEstimate.text(value)
    private fun plain(value: String) = text(if (value.contains("data:image", ignoreCase = true))
        inlineImage.replace(value, "[image]") else value)

    fun history(history: List<Message>, includeScreen: Boolean = true): Long = weights(history, includeScreen).sum()

    fun request(history: List<Message>, tools: List<ToolDefinition>, system: String, includeScreen: Boolean = true): Long {
        val leading = history.takeWhile { it.role == "system" }
        val systemText = listOf(system, leading.flatMap { it.blocks }.filterIsInstance<MessageBlock.Text>()
            .joinToString("") { it.text }).filter(String::isNotEmpty).joinToString("\n\n")
        val definitions = JSONArray()
        tools.forEach { tool -> definitions.put(JSONObject().put("type", "function").put("function",
            JSONObject().put("name", tool.name).put("description", tool.description).put("parameters", JSONObject(tool.parameters)))) }
        return history(history.drop(leading.size), includeScreen) +
            (if (systemText.isEmpty()) 0 else DaiyuContextEstimate.MESSAGE_OVERHEAD + plain(systemText)) +
            (if (definitions.length() == 0) 0 else text(definitions.toString()))
    }

    private fun weights(history: List<Message>, includeScreen: Boolean): List<Long> {
        val latestScreen = if (includeScreen) history.asReversed().firstNotNullOfOrNull { row ->
            row.blocks.lastOrNull { it is MessageBlock.Tool && it.invocation.name == "screen" && it.result != null }
        } else null
        return history.map { row ->
            val calls = JSONArray()
            var results = 0L
            var images = 0L
            val content = StringBuilder()
            row.blocks.forEach { block -> when (block) {
                is MessageBlock.Text -> content.append(block.text)
                is MessageBlock.Image -> images += maxOf(85L, block.reference.bytes.toLong() / 4096)
                is MessageBlock.Tool -> {
                    val call = block.invocation
                    calls.put(JSONObject().put("id", call.id.value).put("type", "function")
                        .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)))
                    block.result?.let { result ->
                        val screen = if (call.name == "screen") NativeScreenImage.metadata(call.name, call.arguments, result)
                            ?: NativeScreenImage.parse(call.name, call.arguments, result)?.metadata else null
                        val output = if (screen == null) TerminalToolResultProjection.output(call.name, result)
                            else JSONObject(screen).apply {
                                if (block === latestScreen) {
                                    put("image_attached", true)
                                    images += DaiyuContextEstimate.image(getInt("width"), getInt("height"))
                                } else put("image_omitted", "superseded_screen")
                            }.toString()
                        results += DaiyuContextEstimate.MESSAGE_OVERHEAD + text(call.id.value) + plain(output)
                    }
                }
            } }
            val reasoning = row.protocolItems.sumOf { raw ->
                val item = JSONObject(raw)
                val fields = listOf("reasoning_content", "reasoning_text", "thinking").sumOf {
                    text(item.optString(it).takeIf(String::isNotBlank).orEmpty())
                }
                fields + if (item.optString("type") == "reasoning") listOf("summary", "content").sumOf { key ->
                    val parts = item.optJSONArray(key) ?: return@sumOf 0L
                    (0 until parts.length()).sumOf { index -> text(parts.optJSONObject(index)?.optString("text").orEmpty()) }
                } else 0L
            }
            DaiyuContextEstimate.MESSAGE_OVERHEAD + plain(content.toString()) + results + images + reasoning +
                (if (calls.length() == 0) 0 else text(calls.toString()))
        }
    }

    fun cut(history: List<Message>, window: Long?, billed: Long? = null, local: Long? = null,
        overflow: Boolean = false, idle: Boolean = false, protocol: ProviderProtocol? = null): Int {
        val system = history.takeWhile { it.role == "system" }.size
        if (history.size <= system || window == null || window <= 0) return 0
        val available = TokenTailCompression.availableCuts(history)
        // Idle compression can consume all finished exchanges, but not an orphaned user suffix.
        val cuts = available.filter { cut ->
            if (cut <= system) false
            else if (!idle) cut < history.size
            else history[cut - 1].let { it.role == Message.ASSISTANT_ROLE && it.status != RunStatus.RUNNING }
        }
        if (cuts.isEmpty()) return 0
        val protectedStart = opaqueTailCut(history, available, protocol)
        if (protectedStart != null) return protectedStart.takeIf { it in cuts } ?: 0
        if (idle) return cuts.last()
        val weights = weights(history, includeScreen = true)
        fun boundary(budget: Long): Int {
            var total = 0L
            var start = history.lastIndex
            for (index in history.lastIndex downTo system) {
                start = index
                total += weights[index]
                if (total >= budget) break
            }
            return cuts.lastOrNull { it <= start } ?: 0
        }
        val budget = maxOf(1L, minOf(window, Int.MAX_VALUE.toLong()) * 16 / 100)
        val calibrated = if (billed != null && billed > 0 && local != null && local > 0)
            (budget * minOf(local, Int.MAX_VALUE.toLong()) / billed).coerceIn(1L, budget * 3 / 2) else budget
        if (overflow) return boundary(1)
        return boundary(calibrated).takeIf { it > 0 }
            ?: boundary(maxOf(1, weights.drop(system).sum() / 2)).takeIf { it > 0 }
            ?: boundary(1)
    }

    fun replayedOpaqueItems(row: Message, protocol: ProviderProtocol?): Int {
        if (protocol != ProviderProtocol.RESPONSES || row.role != "assistant" ||
            row.status != RunStatus.COMPLETED) return 0
        return row.protocolItems.count { JSONObject(it).optString("type") == "reasoning" }
    }

    private fun opaqueTailCut(rows: List<Message>, cuts: List<Int>, protocol: ProviderProtocol?): Int? {
        val opaque = rows.map { replayedOpaqueItems(it, protocol) > 0 }
        if (opaque.none { it }) return null
        val newestComplete = cuts.getOrNull(cuts.lastIndex - 1) ?: return null
        val lastUser = rows.indexOfLast { it.role == Message.USER_ROLE &&
            it.blocks.filterIsInstance<MessageBlock.Text>().none { block -> SummaryCheckpoint.unframe(block.text) != null } }
        val userBoundary = cuts.lastOrNull { it in 1..lastUser } ?: 0
        return if (userBoundary > 0 && userBoundary <= newestComplete &&
            opaque.subList(userBoundary, newestComplete).none { it }) userBoundary
        else newestComplete.takeIf { it > 0 }
    }

    fun summaryProgressBudget(window: Long): Long = if (window <= 0) 0 else minOf(
        maxOf(1, window.coerceAtMost(Int.MAX_VALUE.toLong()) * 16 / 100),
        (window - 4096 - maxOf(512L, window / 20) - window / 100 * 12 - window % 100 * 12 / 100)
            .coerceAtLeast(0))

    fun reduced(before: List<Message>, after: List<Message>, tailSize: Int,
        window: Long, protocol: ProviderProtocol?): Boolean {
        if (tailSize !in 0..minOf(before.size, after.size) || before.takeLast(tailSize) != after.takeLast(tailSize)) return false
        val replayedBefore = before.count { replayedOpaqueItems(it, protocol) > 0 }
        val replayedAfter = after.count { replayedOpaqueItems(it, protocol) > 0 }
        if (replayedAfter > replayedBefore) return false
        if (history(after, includeScreen = false) < history(before, includeScreen = false)) return true
        return replayedAfter < replayedBefore &&
            history(after.dropLast(tailSize), includeScreen = false) <= summaryProgressBudget(window)
    }
}
