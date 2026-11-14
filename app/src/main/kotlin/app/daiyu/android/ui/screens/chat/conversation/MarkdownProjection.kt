package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import app.daiyu.android.MarkdownSourceWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class MarkdownProjection(
    val sources: List<String>,
    val blocks: Map<String, List<MarkdownBlock>>,
    val parser: (String) -> List<MarkdownBlock>,
    val displayText: Map<String, String> = blocks.mapValues { markdownBlocksDisplayText(it.value) },
    val windows: List<MarkdownSourceWindow?> = emptyList(),
    val windowBlocks: Map<MarkdownSourceWindow, List<MarkdownBlock>> = emptyMap(),
    val windowDisplayText: Map<MarkdownSourceWindow, String> = windowBlocks.mapValues { markdownBlocksDisplayText(it.value) },
) {
    fun blocksAt(index: Int): List<MarkdownBlock>? {
        val window = windows.getOrNull(index)
        return if (window == null) sources.getOrNull(index)?.let(blocks::get) else windowBlocks[window]
    }
    fun displayTextAt(index: Int): String = windows.getOrNull(index)?.let(windowDisplayText::get)
        ?: displayText.getValue(sources[index])
}

internal data class MarkdownBodySnapshot(val text: String, val blocks: List<MarkdownBlock>,
    val window: MarkdownSourceWindow? = null)

/** Last committed fallback; publication is not new renderer input. */
internal class MarkdownBodyPublication { var body: MarkdownBodySnapshot? = null }

internal fun markdownBodySnapshot(
    text: String,
    blocks: List<MarkdownBlock>?,
    previous: MarkdownBodySnapshot?,
    window: MarkdownSourceWindow? = null,
): MarkdownBodySnapshot? = if (blocks != null) MarkdownBodySnapshot(text, blocks, window)
    else previous?.takeIf { text.startsWith(it.text) && markdownWindowsAppend(listOf(it.window), listOf(window)) }

internal fun markdownWindowsAppend(previous: List<MarkdownSourceWindow?>, next: List<MarkdownSourceWindow?>): Boolean {
    if (previous.isEmpty() || next.isEmpty()) return previous.isEmpty() && next.isEmpty()
    return previous.size <= next.size && previous.indices.all { index ->
        val before = previous[index]
        val after = next[index]
        if (before == null || after == null) before == after else before.coordinates == after.coordinates && before.streaming == after.streaming && before.start == after.start &&
            before.end <= after.end && after.source.startsWith(before.source)
    }
}

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
    windows: List<MarkdownSourceWindow?> = emptyList(),
): MarkdownProjection {
    currentCoroutineContext().ensureActive()
    val windowed = windows.any { it != null }
    require(!windowed || windows.size == sources.size)
    val cached = cache.get(sources, parser, windows) ?: if (windowed) null else cache.getPrepared(sources, parser)
    val predecessor = previous?.takeIf { it.parser === parser && it.windows == windows && isMarkdownAppend(it.sources, sources) }
    if (predecessor == null) cached?.let { return it }
    val dispatcher = if (sources.sumOf { it.length.toLong() } <= INTERACTIVE_MARKDOWN_CHARS)
        interactiveMarkdownDispatcher else markdownDispatcher
    val projection = withContext(dispatcher) {
        val context = currentCoroutineContext()
        val blocks = linkedMapOf<String, List<MarkdownBlock>>()
        val windowBlocks = linkedMapOf<MarkdownSourceWindow, List<MarkdownBlock>>()
        sources.forEachIndexed { index, source ->
            currentCoroutineContext().ensureActive()
            val window = windows.getOrNull(index)
            if (window != null) {
                if (window !in windowBlocks) windowBlocks[window] = cached?.windowBlocks?.get(window)
                    ?: windowMarkdownBlocks(window, parser)
            } else if (source !in blocks) {
                val parsed = predecessor?.blocks?.get(source) ?: cached?.blocks?.get(source) ?: cache.getBlocks(source, parser) ?: if (parser === defaultMarkdownParser) {
                    MarkdownParser.parse(source) { context.ensureActive() }
                } else if (parser === streamingMarkdownParser) {
                    MarkdownParser.parseStreaming(source) { context.ensureActive() }
                } else parser(source)
                val old = predecessor?.let { it.sources.getOrNull(index)?.let(it.blocks::get) }
                blocks[source] = reuseMarkdownBlocks(old, parsed)
            }
        }
        MarkdownProjection(sources.toList(), blocks, parser, windows = if (windowed) windows.toList() else emptyList(), windowBlocks = windowBlocks)
    }
    currentCoroutineContext().ensureActive()
    cache.put(projection)
    return projection
}

internal fun isMarkdownAppend(previous: List<String>, next: List<String>): Boolean =
    previous.size <= next.size && previous.indices.all { next[it].startsWith(previous[it]) }

// Finish each accepted snapshot; merge arriving appends instead of starving the parser.
internal data class MarkdownProjectionRequest(val sources: List<String>, val windows: List<MarkdownSourceWindow?> = emptyList())

internal suspend fun collectMarkdownProjections(
    requests: Flow<List<String>>,
    parser: (String) -> List<MarkdownBlock> = defaultMarkdownParser,
    cache: MarkdownProjectionCache = markdownProjectionCache,
    publish: (MarkdownProjection) -> Unit,
) = collectMarkdownRequests(requests.map { MarkdownProjectionRequest(it) }, parser, cache, publish)

internal suspend fun collectMarkdownRequests(
    requests: Flow<MarkdownProjectionRequest>,
    parser: (String) -> List<MarkdownBlock>,
    cache: MarkdownProjectionCache = markdownProjectionCache,
    publish: (MarkdownProjection) -> Unit,
) = coroutineScope {
    var previous: MarkdownProjectionRequest? = null
    var pending = Channel<MarkdownProjectionRequest>(Channel.CONFLATED)
    var worker: Job? = null
    try {
        requests.collect { request ->
            val snapshot = request.copy(sources = request.sources.toList(), windows = request.windows.toList())
            if (previous?.let { isMarkdownAppend(it.sources, snapshot.sources) &&
                    markdownWindowsAppend(it.windows, snapshot.windows) } != true) {
                worker?.cancelAndJoin()
                pending.close()
                pending = Channel(Channel.CONFLATED)
                val queue = pending
                worker = launch {
                    var published: MarkdownProjection? = null
                    for (next in queue) {
                        val projection = projectMarkdown(next.sources, parser, cache, published, next.windows)
                        currentCoroutineContext().ensureActive()
                        publish(projection)
                        published = projection
                    }
                }
            }
            previous = snapshot
            pending.send(snapshot)
        }
    } finally { pending.close() }
    worker?.join()
}
