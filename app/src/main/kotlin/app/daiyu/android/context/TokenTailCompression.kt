package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.media.NativeScreenImage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Local input units for token-tail selection and send-capacity checks, not billed usage. */
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

    fun imageTokens(width: Int, height: Int): Long {
        val scale = minOf(1.0, 2048.0 / maxOf(width, height))
        return maxOf(85L, (ceil(width * scale / 32) * ceil(height * scale / 32)).toLong())
    }

    fun messageTokens(message: Message): Long = historyTokens(listOf(message))

    fun historyTokens(history: List<Message>): Long = messageWeights(history).sum()

    private fun messageWeights(history: List<Message>): List<Long> {
        // The wire projection attaches only the latest screen result. Even a newer node-only
        // result retires the older image, while every receipt remains in canonical history.
        val latestScreen = history.asReversed().firstNotNullOfOrNull { message ->
            message.blocks.lastOrNull { it is MessageBlock.Tool && it.invocation.name == "screen" && it.result != null }
        }
        return history.map { message ->
            3L + message.blocks.sumOf { block -> when (block) {
                is MessageBlock.Text -> textTokens(block.text)
                is MessageBlock.Image -> imageTokens(block.reference.width, block.reference.height)
                is MessageBlock.Tool -> {
                    val call = JSONArray().put(JSONObject().put("id", block.invocation.id.value)
                        .put("type", "function").put("function", JSONObject().put("name", block.invocation.name)
                            .put("arguments", block.invocation.arguments)))
                    textTokens(call.toString()) + (block.result?.let { result ->
                        val metadata = if (result.contains("\"image_asset\""))
                            NativeScreenImage.metadata(block.invocation.name, block.invocation.arguments, result)
                        else NativeScreenImage.parse(block.invocation.name, block.invocation.arguments, result)?.metadata
                        val image = metadata?.let(::JSONObject)
                        val attached = image != null && block === latestScreen
                        val output = if (image == null) result else JSONObject(metadata).apply {
                            if (attached) put("image_attached", true) else put("image_omitted", "superseded_screen")
                        }.toString()
                        val pixels = if (image != null && attached)
                            imageTokens(image.getInt("width"), image.getInt("height")) + textTokens(
                                "screen工具图像（call_id=${block.invocation.id.value}），坐标使用原始${image.getInt("width")}×${image.getInt("height")}像素。") else 0L
                        3 + textTokens(block.invocation.id.value) + textTokens(output) + pixels
                    } ?: 0L)
                }
            } }
        }
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
        val weights = messageWeights(history)
        fun start(budget: Long): Int {
            var total = 0L
            var from = history.lastIndex
            for (index in history.indices.reversed()) {
                total += weights[index]; from = index
                if (total >= budget.coerceAtLeast(1)) break
            }
            return cuts.lastOrNull { it <= from && it < history.size } ?: 0
        }
        val budget = localBudget(if (overflow) 1 else maxOf(1, window * 16 / 100), billed, local)
        var cut = start(budget)
        if (cut <= 0) cut = if (overflow) start(1) else {
            val half = weights.sum() / 2
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
