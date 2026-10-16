package app.daiyu.android.ui.screens.chat.conversation

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import app.daiyu.android.MarkdownText
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared selectable body for legacy assistant answers and ordered mixed-content text. */
@Composable
internal fun MessageMarkdown(text: String, modifier: Modifier = Modifier, markdown: Boolean = true) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MiuixTheme.colorScheme.primary
    val body = remember(text, markdown, linkColor, uriHandler) {
        if (markdown) annotateMessageMarkdown(text, linkColor) { url ->
            // A listener is only invoked by a link click, never by parsing/composition.
            // Recheck at the capability boundary and tolerate a missing external handler.
            if (MarkdownText.isSafeLink(url)) runCatching { uriHandler.openUri(url) }
        } else AnnotatedString(text)
    }
    SelectionContainer {
        BasicText(body, modifier = modifier, style = MiuixTheme.textStyles.body1.copy(
            color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 25.sp,
        ))
    }
}

/** Convert the small existing Android-span vocabulary without flattening its ranges. */
internal fun annotateMessageMarkdown(
    source: String,
    linkColor: Color,
    onOpenLink: (String) -> Unit,
): AnnotatedString {
    val rendered = MarkdownText.render(source)
    return buildAnnotatedString {
        append(rendered.toString())
        if (rendered is Spanned) rendered.getSpans(0, rendered.length, Any::class.java).forEach { span ->
            val start = rendered.getSpanStart(span)
            val end = rendered.getSpanEnd(span)
            if (start >= 0 && end > start && end <= length) when (span) {
                is StyleSpan -> addStyle(SpanStyle(
                    fontWeight = if (span.style and Typeface.BOLD != 0) FontWeight.Bold else null,
                    fontStyle = if (span.style and Typeface.ITALIC != 0) FontStyle.Italic else null,
                ), start, end)
                is TypefaceSpan -> if (span.family == "monospace") {
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, end)
                }
                is URLSpan -> if (MarkdownText.isSafeLink(span.url)) {
                    addLink(LinkAnnotation.Url(
                        span.url,
                        styles = TextLinkStyles(style = SpanStyle(
                            color = linkColor, textDecoration = TextDecoration.Underline,
                        )),
                        linkInteractionListener = { link ->
                            val url = (link as? LinkAnnotation.Url)?.url
                            if (url != null && MarkdownText.isSafeLink(url)) onOpenLink(url)
                        },
                    ), start, end)
                }
            }
        }
    }
}
