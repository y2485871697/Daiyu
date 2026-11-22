package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.sp
import app.daiyu.android.MarkdownText
import me.rerere.bettertext.markdown.Markdown
import me.rerere.bettertext.markdown.MarkdownDocument
import me.rerere.bettertext.markdown.rememberMarkdownDocument
import me.rerere.bettertext.text.BetterSelectionContainer
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class AnswerPaint(private val initialSource: String = "") {
    var last: MarkdownDocument? = null
        private set

    fun record(document: MarkdownDocument): Boolean {
        val previous = last
        if (previous === document) return false
        last = document
        val before = previous?.source ?: initialSource
        val next = document.source
        if (next.length <= before.length || !next.startsWith(before) || document.blocks.isEmpty() ||
            previous?.blocks == document.blocks) return false
        return (before.length until next.length).any { !next[it].isWhitespace() }
    }
}

/** Same parser, document publisher and block renderer as the reference app. No local character queue. */
@Composable
internal fun StreamingAnswer(source: String, streaming: Boolean, id: String, reveal: MarkdownReveal, modifier: Modifier) {
    val playback = LocalReplyPlayback.current
    val frozen = !LocalChatPresentationActive.current || playback?.let { it.paused || it.control.stopped } == true
    val receipt = remember(id, reveal.publication) { reveal.publication.answerPaint.getOrPut(id) {
        AnswerPaint(reveal.publication.initialSources.firstOrNull { it.id == id }?.text.orEmpty())
    } }
    val document = rememberMarkdownDocument(if (frozen) receipt.last?.source.orEmpty() else source, streaming = streaming)
    val shown = if (frozen) receipt.last else document
    val limit = reveal.limit(id)
    val following = LocalStreamingTextClearance.current != null && LocalChatBottomFollowIntent.current
    val bodySize = if (following) Modifier else Modifier.animateContentSize()
    val uri = LocalUriHandler.current
    SideEffect {
        reveal.publication.active = true
        if (shown?.source == source) { reveal.applied(id, limit); reveal.presented(id) }
    }
    if (shown != null) BetterSelectionContainer(modifier) {
        Markdown(document = shown, modifier = bodySize.padding(horizontal = 4.dp).drawWithContent {
                drawContent()
                if (receipt.record(shown) && !frozen) reveal.onDocumentAdvance()
            },
            style = MiuixTheme.textStyles.body1.copy(color = conversationColors().text, fontSize = 16.sp, lineHeight = 26.sp),
            onLinkClick = { if (MarkdownText.isSafeLink(it)) runCatching { uri.openUri(it) }; Unit })
    }
}
