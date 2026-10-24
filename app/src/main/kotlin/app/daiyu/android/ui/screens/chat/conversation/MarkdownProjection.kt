package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class MarkdownProjection(
    val sources: List<String>,
    val blocks: Map<String, List<MarkdownBlock>>,
    val parser: (String) -> List<MarkdownBlock>,
)

internal data class MarkdownBodySnapshot(val text: String, val blocks: List<MarkdownBlock>)

internal fun markdownBodySnapshot(
    text: String,
    blocks: List<MarkdownBlock>?,
    previous: MarkdownBodySnapshot?,
): MarkdownBodySnapshot? = if (blocks != null) MarkdownBodySnapshot(text, blocks)
    else previous?.takeIf { text.startsWith(it.text) }

internal val defaultMarkdownParser: (String) -> List<MarkdownBlock> = MarkdownParser::parse

// Each lane has one running parse; short replies cannot queue behind bulk Markdown.
internal const val INTERACTIVE_MARKDOWN_CHARS = 4_096L
private val markdownDispatcher = Dispatchers.Default.limitedParallelism(1)
private val interactiveMarkdownDispatcher = Dispatchers.Default.limitedParallelism(1)

internal suspend fun projectMarkdown(
    sources: List<String>,
    parser: (String) -> List<MarkdownBlock> = defaultMarkdownParser,
    cache: MarkdownProjectionCache = markdownProjectionCache,
): MarkdownProjection {
    currentCoroutineContext().ensureActive()
    cache.get(sources, parser)?.let { return it }
    cache.getPrepared(sources, parser)?.let { return it }
    val dispatcher = if (sources.sumOf { it.length.toLong() } <= INTERACTIVE_MARKDOWN_CHARS)
        interactiveMarkdownDispatcher else markdownDispatcher
    val projection = withContext(dispatcher) {
        val context = currentCoroutineContext()
        val blocks = sources.distinct().associateWith { source ->
            currentCoroutineContext().ensureActive()
            cache.getBlocks(source, parser) ?: if (parser === defaultMarkdownParser) {
                MarkdownParser.parse(source) { context.ensureActive() }
            } else parser(source)
        }
        MarkdownProjection(sources.toList(), blocks, parser)
    }
    currentCoroutineContext().ensureActive()
    cache.put(projection)
    return projection
}
