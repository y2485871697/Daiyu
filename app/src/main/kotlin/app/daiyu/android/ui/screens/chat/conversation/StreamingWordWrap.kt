package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult

/** Only layout boundaries are added; reveal offsets, links, selection and copied text stay native. */
internal class StreamingWordWrap {
    private var previous = ""
    private var breaks = emptyList<Int>()

    fun plan(body: AnnotatedString, painted: Int, measure: (AnnotatedString) -> TextLayoutResult): AnnotatedString {
        val anchor = if (body.text.startsWith(previous)) painted.coerceIn(0, previous.length) else 0
        val locked = breaks.takeWhile { it < anchor }.toMutableList()
        fun annotated(cuts: List<Int>): AnnotatedString {
            if (cuts.isEmpty()) return body
            return AnnotatedString.Builder(body).apply {
                var start = 0
                for (end in cuts) { addStyle(ParagraphStyle(), start, end); start = end }
            }.toAnnotatedString()
        }
        fun boundaries(input: AnnotatedString): List<Int> {
            val layout = measure(input)
            return (1 until layout.lineCount).map { layout.getLineStart(it) }.filter { i ->
                i in 1 until body.length && body[i - 1] != '\n' && body[i].isLatinLetter()
            }
        }
        var natural = boundaries(annotated(locked))
        // An incomplete word can span two network chunks. Do not move its already drawn beginning.
        if (anchor in 1 until body.length && natural.any { it < anchor && it !in locked }) {
            locked.add(anchor)
            natural = boundaries(annotated(locked))
        }
        previous = body.text
        breaks = (locked + natural).distinct().sorted()
        return annotated(breaks)
    }
}

internal fun Char.isLatinLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'
