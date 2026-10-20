package app.daiyu.android.ui.screens.chat.conversation

import java.net.URLDecoder

/** Presentation of the existing literal-reference draft format. Does not resolve or read a URI. */
internal class ComposerReferences(val source: String, composing: IntRange? = null) {
    data class Reference(val line: Int, val kind: String, val value: String, val serialized: String) {
        val label: String get() {
            val tail = value.trimEnd('/').substringAfterLast('/').substringAfterLast('\\')
            val decoded = runCatching { URLDecoder.decode(tail.replace("+", "%2B"), "UTF-8") }.getOrDefault(tail)
            return decoded.substringAfterLast(':').filterNot { it.isISOControl() }.take(120).ifBlank { kind }
        }
    }

    private val lines = source.split('\n')
    private var parsedOffset = 0
    val references = lines.mapIndexedNotNull { index, line ->
        val start = parsedOffset
        parsedOffset += line.length + 1
        if (composing != null && !composing.isEmpty() && composing.first < start + line.length && composing.last >= start)
            return@mapIndexedNotNull null
        val kind = listOf("文件", "目录", "路径").firstOrNull { line.startsWith("${it}引用（未读取）：") }
            ?: return@mapIndexedNotNull null
        val value = line.substringAfter('：')
        if (value.isBlank() || value.any { it.isISOControl() }) null else Reference(index, kind, value, line)
    }
    private val referenceLines = references.map { it.line }.toSet()
    private val offsets: IntArray
    val text: String

    init {
        val body = StringBuilder()
        val positions = ArrayList<Int>()
        var raw = 0
        var previousEnd: Int? = null
        lines.forEachIndexed { index, line ->
            if (index !in referenceLines) {
                previousEnd?.let { body.append('\n'); positions.add(it) }
                line.forEachIndexed { column, char -> body.append(char); positions.add(raw + column) }
                previousEnd = raw + line.length
            }
            raw += line.length + 1
        }
        text = body.toString()
        offsets = (positions + (previousEnd ?: 0)).toIntArray()
    }

    fun toSource(offset: Int): Int = offsets[offset.coerceIn(0, text.length)]
    fun toVisible(offset: Int): Int {
        val target = offset.coerceIn(0, source.length)
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high) / 2
            if (offsets[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    // On a real body edit keep the exact reference payloads, in their original order, after the body.
    // Merely opening/recomposing a draft never rewrites its persisted text.
    fun edit(body: String): String = if (body == text) source else
        if (references.isEmpty()) body else
            (if (body.isEmpty()) "" else "$body\n") + references.joinToString("\n") { it.serialized }

    fun remove(reference: Reference): String = if (reference !in references) source else
        lines.filterIndexed { index, _ -> index != reference.line }.joinToString("\n")
}
