package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import app.daiyu.android.MarkdownSourceDocument
import app.daiyu.android.MarkdownSourceWindow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only source-mapped documents live here; ordinary AST cache entries cannot stand in for a map. */
private object MarkdownDocuments {
    private data class Key(val source: String, val parser: MarkdownParserIdentity, val streaming: Boolean)
    private val entries = LinkedHashMap<Key, MarkdownSourceDocument>(16, .75f, true)
    private const val MAX_UNITS = 2_097_152L
    private var units = 0L

    @Synchronized
    fun get(source: String, parser: (String) -> List<MarkdownBlock>, streaming: Boolean): MarkdownSourceDocument? =
        entries[Key(source, MarkdownParserIdentity(parser), streaming)]

    @Synchronized
    fun put(source: String, parser: (String) -> List<MarkdownBlock>, streaming: Boolean, document: MarkdownSourceDocument) {
        val weight = source.length.toLong() * 8 + 1
        if (weight > MAX_UNITS) return
        val key = Key(source, MarkdownParserIdentity(parser), streaming)
        entries.remove(key)?.let { units -= it.source.length.toLong() * 8 + 1 }
        entries[key] = document
        units += weight
        val iterator = entries.entries.iterator()
        while (entries.size > 16 || units > MAX_UNITS) {
            units -= iterator.next().value.source.length.toLong() * 8 + 1
            iterator.remove()
        }
    }
}

internal suspend fun windowMarkdownBlocks(window: MarkdownSourceWindow,
    parser: (String) -> List<MarkdownBlock>): List<MarkdownBlock> {
    val context = currentCoroutineContext()
    context.ensureActive()
    val streaming = window.streaming || parser === streamingMarkdownParser
    val document = MarkdownDocuments.get(window.source, parser, streaming) ?: run {
        val parsed = MarkdownParser.parseDocument(window.source, streaming) { context.ensureActive() }
        if (parser !== defaultMarkdownParser && parser !== streamingMarkdownParser) {
            check(parser(window.source) == parsed.blocks) { "A window parser must preserve mapped Markdown syntax" }
        }
        MarkdownDocuments.put(window.source, parser, streaming, parsed)
        parsed
    }
    context.ensureActive()
    return document.window(window.displayStart, window.displayEnd)
}
