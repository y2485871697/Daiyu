package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.MessageHistory
import app.daiyu.android.domain.RunStatus

internal fun restoredMarkdownSources(messages: List<Message>): List<String> =
    MessageHistory.expand(messages).filter { it.role == Message.ASSISTANT_ROLE && it.status != RunStatus.RUNNING }
        .flatMap { message ->
            val texts = message.blocks.filterIsInstance<MessageBlock.Text>().map { it.text }
            val summaries = message.reportedReasoning.filter { hasReportedReasoningText(it.text) }.map { it.text }
            // The production mapper preserves Text item boundaries in finalContent (and mixed content).
            // A joined copy is not rendered; warming it first can evict every actual segment.
            summaries + texts
        }

/** Called by the recovery worker, before historical turns become visible. Only inert ASTs are kept. */
internal fun prepareRestoredMarkdown(
    messages: List<Message>,
    cache: MarkdownProjectionCache = markdownProjectionCache,
    parser: (String) -> List<MarkdownBlock> = defaultMarkdownParser,
    checkActive: () -> Unit = {},
) {
    val prepared = cache.preparationSources(restoredMarkdownSources(messages)).map { source ->
        checkActive()
        val blocks = cache.getBlocks(source, parser) ?: if (parser === defaultMarkdownParser)
            MarkdownParser.parse(source, checkActive) else parser(source)
        checkActive()
        MarkdownProjection(listOf(source), mapOf(source to blocks), parser)
    }
    checkActive()
    synchronized(cache) { prepared.forEach(cache::put) }
}
