package app.daiyu.android

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import java.net.URI

object MarkdownText {
    fun render(source: String): CharSequence {
        val text = SpannableStringBuilder(source)
        val styles = listOf(
            Regex("```(?:[^\\n]*\\n)?([\\s\\S]*?)```") to { TypefaceSpan("monospace") },
            Regex("`([^`\\n]+)`") to { TypefaceSpan("monospace") },
            Regex("\\*\\*([^*\\n]+)\\*\\*") to { StyleSpan(Typeface.BOLD) },
            Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)") to { StyleSpan(Typeface.ITALIC) },
        )
        for ((pattern, span) in styles) {
            text.matchesOutsideCode(pattern).asReversed().forEach { (offset, match) ->
                val start = offset + match.range.first
                val end = offset + match.range.last + 1
                val content = match.groupValues[1]
                text.replace(start, end, content)
                text.setSpan(span(), start, start + content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        text.matchesOutsideCode(Regex("\\[([^]\\n]+)]\\((https?://[^\\s)]+)\\)"))
            .asReversed().forEach { (offset, match) ->
                val start = offset + match.range.first
                val end = offset + match.range.last + 1
                val url = match.groupValues[2]
                if (isSafeLink(url)) {
                    val label = match.groupValues[1]
                    text.replace(start, end, label)
                    text.setSpan(URLSpan(url), start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        return text
    }

    private fun Spanned.matchesOutsideCode(pattern: Regex): List<Pair<Int, MatchResult>> {
        // Match each gap separately: filtering whole-text matches after findAll can let
        // a delimiter inside code consume a valid match outside it. This also keeps
        // inline-code scanning out of fenced code. All offsets remain UTF-16 indices.
        val snapshot = toString()
        val matches = mutableListOf<Pair<Int, MatchResult>>()
        var start = 0
        fun collect(end: Int) {
            if (start < end) {
                pattern.findAll(snapshot.substring(start, end)).forEach { matches += start to it }
            }
        }
        getSpans(0, length, TypefaceSpan::class.java).sortedBy { getSpanStart(it) }.forEach {
            collect(getSpanStart(it))
            start = maxOf(start, getSpanEnd(it))
        }
        collect(length)
        // Callers apply right to left; existing spans track preceding replacements.
        return matches
    }

    /** Keep the existing lowercase HTTP(S)-only syntax; never normalize an unsafe scheme. */
    internal fun isSafeLink(url: String): Boolean {
        if (!(url.startsWith("https://") || url.startsWith("http://")) ||
            url.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return false
        return runCatching {
            val uri = URI(url)
            (uri.scheme == "https" || uri.scheme == "http") &&
                !uri.isOpaque && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port in 1..65535)
        }.getOrDefault(false)
    }
}
