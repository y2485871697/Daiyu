package app.daiyu.android.context

import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Compression-only pricing and logical boundaries; send-capacity accounting remains independent. */
internal object TokenTailCompression {
    fun textTokens(text: String): Long {
        if (text.isEmpty()) return 0
        var cjk = 0L
        var other = 0L
        var index = 0
        while (index < text.length) {
            val code = Character.codePointAt(text, index)
            if (code in 0x3400..0x4dbf || code in 0x4e00..0x9fff || code in 0xf900..0xfaff ||
                code in 0x3040..0x30ff || code in 0xac00..0xd7af || code in 0x3000..0x303f ||
                code in 0xff00..0xffef || code in 0x20000..0x2a6df) cjk++ else other++
            index += Character.charCount(code)
        }
        return (cjk * 1.5 + other * 0.25).roundToLong().coerceAtLeast(1)
    }

    fun messageTokens(message: Message): Long {
        var total = 3L
        message.blocks.forEach { block -> total += when (block) {
            is MessageBlock.Text -> textTokens(block.text)
            is MessageBlock.Image -> {
                val image = block.reference
                val scale = minOf(1.0, 2048.0 / maxOf(image.width, image.height))
                maxOf(85L, (ceil(image.width * scale / 32) * ceil(image.height * scale / 32)).toLong())
            }
            is MessageBlock.Tool -> {
                val call = JSONArray().put(JSONObject().put("id", block.invocation.id.value)
                    .put("type", "function").put("function", JSONObject().put("name", block.invocation.name)
                        .put("arguments", block.invocation.arguments)))
                textTokens(call.toString()) + (block.result?.let { result ->
                    3 + textTokens(block.invocation.id.value) + textTokens(result)
                } ?: 0L)
            }
        } }
        // Encrypted/signature protocol state is deliberately not priced as ordinary text.
        return total
    }

    fun localBudget(budget: Long, billed: Long?, local: Long?): Long {
        if (billed == null || billed <= 0 || local == null || local <= 0) return budget
        val scaled = (budget.toDouble() * local / billed).toLong()
        return scaled.coerceIn(1, budget * 3 / 2)
    }

    fun isSafeCut(history: List<Message>, cut: Int): Boolean = cut in availableCuts(history)

    private fun availableCuts(history: List<Message>): List<Int> {
        val cuts = mutableListOf(0)
        var blocked = false
        history.forEachIndexed { index, message ->
            if (message.blocks.any { it is MessageBlock.Tool && it.result == null }) blocked = true
            if (!blocked) cuts.add(index + 1)
        }
        return cuts
    }

    fun selectStart(history: List<Message>, window: Long?, billed: Long? = null,
        local: Long? = null, overflow: Boolean = false): Int {
        if (history.size < 2 || window == null || window <= 0) return 0
        val cuts = availableCuts(history)
        fun start(budget: Long): Int {
            var total = 0L
            var from = history.lastIndex
            for (index in history.indices.reversed()) {
                total += messageTokens(history[index]); from = index
                if (total >= budget.coerceAtLeast(1)) break
            }
            return cuts.lastOrNull { it <= from && it < history.size } ?: 0
        }
        val budget = localBudget(if (overflow) 1 else maxOf(1, window * 16 / 100), billed, local)
        var cut = start(budget)
        if (cut <= 0) cut = if (overflow) start(1) else {
            val half = history.sumOf(::messageTokens) / 2
            start(half.coerceAtLeast(1)).takeIf { it > 0 } ?: start(1)
        }
        if (history.any(::hasOpaque)) {
            val end = history.size
            val inFlight = if (cuts.last() == end) end else cuts.last()
            val bounds = cuts.filter { it <= inFlight }
            if (bounds.size >= 2) {
                val newest = bounds[bounds.lastIndex - 1]
                var required = newest
                val user = history.indexOfLast { it.role == Message.USER_ROLE }
                val userCut = cuts.lastOrNull { it <= user && it < end } ?: 0
                if (userCut > 0 && userCut <= newest && history.subList(userCut, newest).none(::hasOpaque))
                    required = userCut
                if (inFlight < end) required = minOf(required, inFlight)
                if (required in 1 until end && required in cuts) cut = required
            }
        }
        return cut
    }

    private fun hasOpaque(message: Message): Boolean = message.protocolItems.any { raw ->
        val item = JSONObject(raw)
        if (item.optString("type") == "reasoning") true
        else item.optJSONArray("content")?.let { content ->
            (0 until content.length()).any { index ->
                content.optJSONObject(index)?.optString("type") in setOf("thinking", "redacted_thinking")
            }
        } == true
    }
}
