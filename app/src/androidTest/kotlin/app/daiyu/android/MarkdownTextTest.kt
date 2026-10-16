package app.daiyu.android

import android.graphics.Typeface
import android.test.InstrumentationTestCase
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

@Suppress("DEPRECATION")
class MarkdownTextTest : InstrumentationTestCase() {
    fun testCodeIsLiteralAcrossAllLaterPassesAndRangesFollowEarlierReplacements() {
        val inline = "**not bold** *not italic* [not link](https://code.test)"
        val fenced = "`nested` **literal** [literal](http://code.test)\n"
        val source = "😀 **bold** `$inline` *italic*\n```kotlin\n$fenced```\n[site](https://example.test)"
        val result = MarkdownText.render(source) as Spanned
        val expected = "😀 bold $inline italic\n$fenced\nsite"
        assertEquals(expected, result.toString())
        val code = result.getSpans(0, result.length, TypefaceSpan::class.java)
        assertEquals(2, code.size)
        assertEquals(setOf(inline, fenced), code.map {
            assertEquals("monospace", it.family)
            result.subSequence(result.getSpanStart(it), result.getSpanEnd(it)).toString()
        }.toSet())
        val styles = result.getSpans(0, result.length, StyleSpan::class.java)
        assertEquals(2, styles.size)
        styles.forEach {
            val literal = if (it.style == Typeface.BOLD) "bold" else "italic"
            val start = if (it.style == Typeface.BOLD) expected.indexOf(literal) else expected.lastIndexOf(literal)
            assertEquals(start, result.getSpanStart(it))
            assertEquals(start + literal.length, result.getSpanEnd(it))
        }
        val link = result.getSpans(0, result.length, URLSpan::class.java).single()
        assertEquals("https://example.test", link.url)
        assertEquals(expected.lastIndexOf("site"), result.getSpanStart(link))
        assertEquals(expected.length, result.getSpanEnd(link))
    }

    fun testCodeAtStyleAndLinkBoundariesIsNeverReparsed() {
        val result = MarkdownText.render("**b**`*x*`[s](http://example.test)`[c](https://code.test)`*i*") as Spanned
        assertEquals("b*x*s[c](https://code.test)i", result.toString())
        assertEquals(2, result.getSpans(0, result.length, TypefaceSpan::class.java).size)
        assertEquals(2, result.getSpans(0, result.length, StyleSpan::class.java).size)
        assertEquals(1, result.getSpans(0, result.length, URLSpan::class.java).size)
    }

    fun testCodeAsteriskDoesNotConsumeFollowingItalicMatch() {
        for (prefix in listOf("", "😀 ")) {
            val result = MarkdownText.render("${prefix}`*` *italic*") as Spanned
            assertEquals("${prefix}* italic", result.toString())
            val code = result.getSpans(0, result.length, TypefaceSpan::class.java).single()
            assertEquals("monospace", code.family)
            assertEquals(prefix.length, result.getSpanStart(code))
            assertEquals(prefix.length + 1, result.getSpanEnd(code))
            val italic = result.getSpans(0, result.length, StyleSpan::class.java).single()
            assertEquals(Typeface.ITALIC, italic.style)
            assertEquals(prefix.length + 2, result.getSpanStart(italic))
            assertEquals(prefix.length + 8, result.getSpanEnd(italic))
            assertEquals(0, result.getSpans(0, result.length, URLSpan::class.java).size)
        }
    }

    fun testCodeBracketDoesNotConsumeFollowingLinkMatch() {
        for (prefix in listOf("", "😀 ")) {
            val result = MarkdownText.render("${prefix}`[` [site](https://example.test)") as Spanned
            assertEquals("${prefix}[ site", result.toString())
            val code = result.getSpans(0, result.length, TypefaceSpan::class.java).single()
            assertEquals("monospace", code.family)
            assertEquals(prefix.length, result.getSpanStart(code))
            assertEquals(prefix.length + 1, result.getSpanEnd(code))
            val link = result.getSpans(0, result.length, URLSpan::class.java).single()
            assertEquals("https://example.test", link.url)
            assertEquals(prefix.length + 2, result.getSpanStart(link))
            assertEquals(prefix.length + 6, result.getSpanEnd(link))
            assertEquals(0, result.getSpans(0, result.length, StyleSpan::class.java).size)
        }
    }

    fun testInlineCodeScanningExcludesFencedCode() {
        val result = MarkdownText.render("```\n` ``` `outside`") as Spanned
        assertEquals("`  outside", result.toString())
        val code = result.getSpans(0, result.length, TypefaceSpan::class.java)
            .sortedBy { result.getSpanStart(it) }
        assertEquals(2, code.size)
        assertTrue(code.all { it.family == "monospace" })
        assertEquals(0, result.getSpanStart(code[0]))
        assertEquals(2, result.getSpanEnd(code[0]))
        assertEquals(3, result.getSpanStart(code[1]))
        assertEquals(10, result.getSpanEnd(code[1]))
        assertEquals(0, result.getSpans(0, result.length, StyleSpan::class.java).size)
        assertEquals(0, result.getSpans(0, result.length, URLSpan::class.java).size)
    }

    fun testOnlyStrictHttpLinksBecomeSpansAndUnsupportedMarkupStaysText() {
        val rejected = listOf(
            "javascript:alert", "data:text/plain,hello", "file:///private", "content://private/1",
            "intent://example.test", "//example.test", "HTTPS://example.test", " https://example.test",
            "https://", "https:///example.test", "https://user@example.test", "https://example.test:99999",
            "https://example.test\\@evil.test", "https://example.test/\u0000", "https://example.test/\npath",
        )
        rejected.forEach { url ->
            assertFalse(url, MarkdownText.isSafeLink(url))
            val source = "[unsafe]($url)"
            val result = MarkdownText.render(source) as Spanned
            assertEquals(source, result.toString())
            assertEquals(0, result.getSpans(0, result.length, URLSpan::class.java).size)
        }
        listOf("http://example.test/path", "https://example.test:443/a?q=b#c").forEach { url ->
            assertTrue(url, MarkdownText.isSafeLink(url))
            val result = MarkdownText.render("[safe]($url)") as Spanned
            assertEquals("safe", result.toString())
            assertEquals(url, result.getSpans(0, result.length, URLSpan::class.java).single().url)
        }
        val unsupported = "# heading\n<div>raw HTML</div>\n**unclosed\n`unclosed"
        assertEquals(unsupported, MarkdownText.render(unsupported).toString())
    }
}
