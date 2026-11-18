package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.media.NativeScreenImage
import app.daiyu.android.model.TerminalToolResultProjection
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** Harness fixed-density surface pricing; media payload pricing remains route-owned. */
internal object TokenTailCompression {
    fun textTokens(text: String): Long = app.daiyu.android.domain.context.HarnessTokenEstimate.text(text)

    fun imageTokens(width: Int, height: Int): Long {
        val scale = minOf(1.0, 2048.0 / maxOf(width, height))
        return maxOf(85L, (ceil(width * scale / 32) * ceil(height * scale / 32)).toLong())
    }

    fun imageReferenceTokens(reference: MessageImageReference): Long {
        val attachment = JSONObject().put("attachmentId", reference.sha256).put("mediaType", reference.mime)
            .put("bytes", reference.bytes).put("width", reference.width).put("height", reference.height)
        return 4L + textTokens(JSONObject().put("type", "image").put("attachment", attachment).toString())
    }

    fun messageTokens(message: Message): Long = historyTokens(listOf(message))

    fun historyTokens(history: List<Message>, includeLatestScreen: Boolean = true): Long =
        messageWeights(history, includeLatestScreen).sum()

    private fun messageWeights(history: List<Message>, includeLatestScreen: Boolean = true): List<Long> {
        // The wire projection attaches only the latest screen result. Even a newer node-only
        // result retires the older image, while every receipt remains in canonical history.
        val latestScreen = if (!includeLatestScreen) null else history.asReversed().firstNotNullOfOrNull { message ->
            message.blocks.lastOrNull { it is MessageBlock.Tool && it.invocation.name == "screen" && it.result != null }
        }
        return history.map { message ->
            val payload = message.blocks.sumOf { block -> when (block) {
                is MessageBlock.Text -> 4L + textTokens(block.text)
                is MessageBlock.Image -> imageReferenceTokens(block.reference)
                is MessageBlock.Tool -> {
                    block.result?.let { result ->
                        val metadata = if (result.contains("\"image_asset\""))
                            NativeScreenImage.metadata(block.invocation.name, block.invocation.arguments, result)
                        else NativeScreenImage.parse(block.invocation.name, block.invocation.arguments, result)?.metadata
                        val image = metadata?.let(::JSONObject)
                        val attached = image != null && block === latestScreen
                        val output = if (image == null) TerminalToolResultProjection.output(block.invocation.name, result) else JSONObject(metadata).apply {
                            if (attached) put("image_attached", true) else put("image_omitted", "superseded_screen")
                        }.toString()
                        val pixels = if (image != null && attached)
                            imageTokens(image.getInt("width"), image.getInt("height")) + textTokens(
                                "screen工具图像（call_id=${block.invocation.id.value}），坐标使用原始${image.getInt("width")}×${image.getInt("height")}像素。") else 0L
                        8L + textTokens(output) + pixels
                    } ?: 0L
                }
            } }
            if (message.role == "system") {
                val rendered = message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("") { it.text }
                app.daiyu.android.domain.context.HarnessTokenEstimate.system(rendered)
            } else 4L + payload + visibleReasoningTokens(message) +
                message.blocks.filterIsInstance<MessageBlock.Tool>().sumOf {
                    app.daiyu.android.domain.context.HarnessTokenEstimate.toolCall(it.invocation.name, it.invocation.arguments)
                }
        }
    }

    private fun visibleReasoningTokens(message: Message): Long {
        if (message.role != Message.ASSISTANT_ROLE || message.status != RunStatus.COMPLETED) return 0
        fun parts(array: JSONArray?, reasoning: Boolean = false): Long = if (array == null) 0L else (0 until array.length()).sumOf { index ->
            val part = array.optJSONObject(index) ?: return@sumOf 0L
            when (part.optString("type")) {
                "summary_text", "reasoning_text" -> 4L + textTokens(part.optString("text"))
                "text", "input_text", "output_text" -> if (reasoning) 4L + textTokens(part.optString("text")) else 0L
                "refusal" -> if (reasoning) 4L + textTokens(part.optString("refusal")) else 0L
                "thinking" -> 4L + textTokens(part.optString("thinking"))
                else -> 0L
            }
        }
        return message.protocolItems.sumOf { raw ->
            val item = JSONObject(raw)
            val reasoning = item.optString("type") == "reasoning"
            (item.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { 4L + textTokens(it) } ?: 0L) + parts(item.optJSONArray("content"), reasoning) +
                if (reasoning) parts(item.optJSONArray("summary"), true) +
                    (item.optString("reasoning_text").takeIf { it.isNotEmpty() }?.let { 4L + textTokens(it) } ?: 0L) else 0L
        }
    }

    fun toolTokens(tools: List<ToolDefinition>): Long {
        if (tools.isEmpty()) return 0
        val definitions = JSONArray()
        tools.forEach { tool -> definitions.put(JSONObject().put("name", tool.name).put("description", tool.description)
            .put("parameters", JSONObject(tool.parameters))) }
        return 4L + textTokens(definitions.toString())
    }

    @Suppress("UNUSED_PARAMETER")
    fun localBudget(budget: Long, billed: Long?, local: Long?): Long = budget

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

    @Suppress("UNUSED_PARAMETER")
    fun selectStart(history: List<Message>, window: Long?, billed: Long? = null,
        local: Long? = null, overflow: Boolean = false, reservedCompletionTokens: Long = 0): Int {
        if (history.size < 2 || (!overflow && (window == null || window <= reservedCompletionTokens))) return 0
        val first = history.takeWhile { it.role == "system" }.size
        val retain = if (overflow) 0L else (checkNotNull(window) - reservedCompletionTokens) * 16 / 100
        val weights = messageWeights(history)
        var accumulated = 0L
        var keepFrom = history.size
        for (index in history.indices.reversed()) {
            accumulated += weights[index]
            keepFrom = index
            if (accumulated >= retain) break
        }
        if (keepFrom <= first) return 0
        return availableCuts(history).lastOrNull { it <= keepFrom && it > first } ?: 0
    }
}
