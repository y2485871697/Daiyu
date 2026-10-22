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

// One running CPU parse; cancelled snapshots do not accumulate runnable parse work.
private val markdownDispatcher = Dispatchers.Default.limitedParallelism(1)

internal suspend fun projectMarkdown(
    sources: List<String>,
    parser: (String) -> List<MarkdownBlock> = MarkdownParser::parse,
    cache: MarkdownProjectionCache = markdownProjectionCache,
): MarkdownProjection {
    currentCoroutineContext().ensureActive()
    cache.get(sources, parser)?.let { return it }
    val projection = withContext(markdownDispatcher) {
        val blocks = sources.distinct().associateWith { source ->
            currentCoroutineContext().ensureActive()
            parser(source)
        }
        MarkdownProjection(sources.toList(), blocks, parser)
    }
    currentCoroutineContext().ensureActive()
    cache.put(projection)
    return projection
}
