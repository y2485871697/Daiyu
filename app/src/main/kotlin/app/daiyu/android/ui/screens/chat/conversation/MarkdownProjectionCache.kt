package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownSourceWindow

internal class MarkdownParserIdentity(private val parser: (String) -> List<MarkdownBlock>) {
    override fun equals(other: Any?): Boolean = other is MarkdownParserIdentity && parser === other.parser
    override fun hashCode(): Int = System.identityHashCode(parser)
}

/** CPU-only ASTs survive page disposal; rendered colors, links and selection stay local. */
internal class MarkdownProjectionCache(
    private val maxEntries: Int = 64,
    private val maxSourceChars: Int = 262_144,
) {
    private data class Key(val sources: List<String>, val parser: MarkdownParserIdentity, val windows: List<MarkdownSourceWindow?> = emptyList())

    private data class Entry(val projection: MarkdownProjection, val sourceChars: Long)
    private val entries = LinkedHashMap<Key, Entry>(16, .75f, true)
    private var sourceChars = 0L

    @Synchronized
    fun get(sources: List<String>, parser: (String) -> List<MarkdownBlock>,
        windows: List<MarkdownSourceWindow?> = emptyList()): MarkdownProjection? =
        entries[Key(sources, MarkdownParserIdentity(parser), windows.takeIf { it.any { item -> item != null } }.orEmpty())]?.projection

    private fun sourceProjection(source: String, parser: (String) -> List<MarkdownBlock>): MarkdownProjection? {
        val key = entries.entries.lastOrNull {
            it.value.projection.windows.isEmpty() && it.value.projection.parser === parser && it.value.projection.blocks.containsKey(source)
        }?.key ?: return null
        return entries[key]?.projection
    }

    @Synchronized
    fun getBlocks(source: String, parser: (String) -> List<MarkdownBlock>): List<MarkdownBlock>? =
        sourceProjection(source, parser)?.blocks?.get(source)

    @Synchronized
    fun getPrepared(sources: List<String>, parser: (String) -> List<MarkdownBlock>): MarkdownProjection? {
        val blocks = linkedMapOf<String, List<MarkdownBlock>>()
        val displayText = linkedMapOf<String, String>()
        for (source in sources.distinct()) {
            val prepared = sourceProjection(source, parser) ?: return null
            blocks[source] = prepared.blocks.getValue(source)
            displayText[source] = prepared.displayText.getValue(source)
        }
        return MarkdownProjection(sources.toList(), blocks, parser, displayText)
    }

    fun preparationSources(sources: List<String>): List<String> {
        val selected = mutableListOf<String>()
        var chars = 0L
        for (source in sources.asReversed().distinct()) {
            if (selected.size >= maxEntries) break
            if (source.length.toLong() + chars > maxSourceChars) continue
            selected += source
            chars += source.length
        }
        return selected.asReversed()
    }

    @Synchronized
    fun put(projection: MarkdownProjection) {
        val chars = projection.sources.sumOf { it.length.toLong() } +
            projection.windows.filterNotNull().map { it.source }.distinct().sumOf { it.length.toLong() * 4 }
        if (chars > maxSourceChars) return
        val snapshot = MarkdownProjection(projection.sources.toList(), projection.blocks, projection.parser, projection.displayText, projection.windows.toList(), projection.windowBlocks, projection.windowDisplayText)
        val key = Key(snapshot.sources, MarkdownParserIdentity(snapshot.parser), snapshot.windows)
        entries.remove(key)?.let { sourceChars -= it.sourceChars }
        entries[key] = Entry(snapshot, chars)
        sourceChars += chars
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries || sourceChars > maxSourceChars) {
            sourceChars -= iterator.next().value.sourceChars
            iterator.remove()
        }
    }
}

internal val markdownProjectionCache = MarkdownProjectionCache()
