package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class MarkdownProjection(
    val sources: List<String>,
    val blocks: Map<String, List<MarkdownBlock>>,
    val parser: (String) -> List<MarkdownBlock>,
    val displayText: Map<String, String> = blocks.mapValues { markdownBlocksDisplayText(it.value) },
)

internal data class MarkdownBodySnapshot(val text: String, val blocks: List<MarkdownBlock>)

/** Last committed fallback; publication is not new renderer input. */
internal class MarkdownBodyPublication { var body: MarkdownBodySnapshot? = null }

internal fun markdownBodySnapshot(
    text: String,
    blocks: List<MarkdownBlock>?,
    previous: MarkdownBodySnapshot?,
): MarkdownBodySnapshot? = if (blocks != null) MarkdownBodySnapshot(text, blocks)
    else previous?.takeIf { text.startsWith(it.text) }

internal val defaultMarkdownParser: (String) -> List<MarkdownBlock> = MarkdownParser::parse
internal val streamingMarkdownParser: (String) -> List<MarkdownBlock> = MarkdownParser::parseStreaming

// Each lane has one running parse; short replies cannot queue behind bulk Markdown.
internal const val INTERACTIVE_MARKDOWN_CHARS = 4_096L
private val markdownDispatcher = Dispatchers.Default.limitedParallelism(1)
private val interactiveMarkdownDispatcher = Dispatchers.Default.limitedParallelism(1)

internal suspend fun projectMarkdown(
    sources: List<String>,
    parser: (String) -> List<MarkdownBlock> = defaultMarkdownParser,
    cache: MarkdownProjectionCache = markdownProjectionCache,
    previous: MarkdownProjection? = null,
): MarkdownProjection {
    currentCoroutineContext().ensureActive()
    val cached = cache.get(sources, parser) ?: cache.getPrepared(sources, parser)
    val predecessor = previous?.takeIf { it.parser === parser && isMarkdownAppend(it.sources, sources) }
    if (predecessor == null) cached?.let { return it }
    val dispatcher = if (sources.sumOf { it.length.toLong() } <= INTERACTIVE_MARKDOWN_CHARS)
        interactiveMarkdownDispatcher else markdownDispatcher
    val projection = withContext(dispatcher) {
        val context = currentCoroutineContext()
        val blocks = linkedMapOf<String, List<MarkdownBlock>>()
        sources.forEachIndexed { index, source ->
            currentCoroutineContext().ensureActive()
            if (source !in blocks) {
                val parsed = predecessor?.blocks?.get(source) ?: cached?.blocks?.get(source) ?: cache.getBlocks(source, parser) ?: if (parser === defaultMarkdownParser) {
                    MarkdownParser.parse(source) { context.ensureActive() }
                } else if (parser === streamingMarkdownParser) {
                    MarkdownParser.parseStreaming(source) { context.ensureActive() }
                } else parser(source)
                val old = predecessor?.let { it.sources.getOrNull(index)?.let(it.blocks::get) }
                blocks[source] = reuseMarkdownBlocks(old, parsed)
            }
        }
        MarkdownProjection(sources.toList(), blocks, parser)
    }
    currentCoroutineContext().ensureActive()
    cache.put(projection)
    return projection
}

internal fun isMarkdownAppend(previous: List<String>, next: List<String>): Boolean =
    previous.size <= next.size && previous.indices.all { next[it].startsWith(previous[it]) }

// Finish each accepted snapshot; merge arriving appends instead of starving the parser.
internal suspend fun collectMarkdownProjections(
    requests: Flow<List<String>>,
    parser: (String) -> List<MarkdownBlock> = defaultMarkdownParser,
    cache: MarkdownProjectionCache = markdownProjectionCache,
    publish: (MarkdownProjection) -> Unit,
) = coroutineScope {
    var previous: List<String>? = null
    var pending = Channel<List<String>>(Channel.CONFLATED)
    var worker: Job? = null
    try {
        requests.collect { request ->
            val sources = request.toList()
            if (previous == null || !isMarkdownAppend(previous!!, sources)) {
                worker?.cancelAndJoin()
                pending.close()
                pending = Channel(Channel.CONFLATED)
                val queue = pending
                worker = launch {
                    var published: MarkdownProjection? = null
                    for (snapshot in queue) {
                        val projection = projectMarkdown(snapshot, parser, cache, published)
                        currentCoroutineContext().ensureActive()
                        publish(projection)
                        published = projection
                    }
                }
            }
            previous = sources
            pending.send(sources)
        }
    } finally { pending.close() }
    worker?.join()
}
