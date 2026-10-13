package app.daiyu.android

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

object MarkdownText {
    fun render(source:String):CharSequence {
        val text=SpannableStringBuilder(source)
        val styles=listOf(Regex("```(?:[^\\n]*\\n)?([\\s\\S]*?)```") to {TypefaceSpan("monospace")},
            Regex("`([^`\\n]+)`") to {TypefaceSpan("monospace")},
            Regex("\\*\\*([^*\\n]+)\\*\\*") to {StyleSpan(Typeface.BOLD)},
            Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)") to {StyleSpan(Typeface.ITALIC)})
        for((pattern,span) in styles) {
            pattern.findAll(text).toList().asReversed().forEach { match ->
                val content=match.groupValues[1];val start=match.range.first
                text.replace(start,match.range.last+1,content);text.setSpan(span(),start,start+content.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        Regex("\\[([^]\\n]+)]\\((https?://[^\\s)]+)\\)").findAll(text).toList().asReversed().forEach { match ->
            val start=match.range.first;val label=match.groupValues[1]
            text.replace(start,match.range.last+1,label);text.setSpan(URLSpan(match.groupValues[2]),start,start+label.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }
}
