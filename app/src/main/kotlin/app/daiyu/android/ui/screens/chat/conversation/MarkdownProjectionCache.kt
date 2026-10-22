package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock

internal class MarkdownParserIdentity(private val parser: (String) -> List<MarkdownBlock>) {
    override fun equals(other: Any?): Boolean = other is MarkdownParserIdentity && parser === other.parser
    override fun hashCode(): Int = System.identityHashCode(parser)
}

/** CPU-only ASTs survive page disposal; rendered colors, links and selection stay local. */
internal class MarkdownProjectionCache(
    private val maxEntries: Int = 64,
    private val maxSourceChars: Int = 262_144,
) {
    private data class Key(val sources: List<String>, val parser: MarkdownParserIdentity)

    private data class Entry(val projection: MarkdownProjection, val sourceChars: Long)
    private val entries = LinkedHashMap<Key, Entry>(16, .75f, true)
    private var sourceChars = 0L

    @Synchronized
    fun get(sources: List<String>, parser: (String) -> List<MarkdownBlock>): MarkdownProjection? =
        entries[Key(sources, MarkdownParserIdentity(parser))]?.projection

    @Synchronized
    fun getBlocks(source: String, parser: (String) -> List<MarkdownBlock>): List<MarkdownBlock>? {
        val key = entries.entries.lastOrNull {
            it.value.projection.parser === parser && it.value.projection.blocks.containsKey(source)
        }?.key ?: return null
        return entries[key]?.projection?.blocks?.get(source)
    }

    @Synchronized
    fun put(projection: MarkdownProjection) {
        val chars = projection.sources.sumOf { it.length.toLong() }
        if (chars > maxSourceChars) return
        val snapshot = MarkdownProjection(projection.sources.toList(), projection.blocks, projection.parser)
        val key = Key(snapshot.sources, MarkdownParserIdentity(snapshot.parser))
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
