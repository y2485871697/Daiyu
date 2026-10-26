package app.daiyu.android.platform

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class DocumentTextWindowTest {
    @Test fun largeUtf8DocumentPagesWithoutWholeFileFailure() {
        val original = "日志😀：正常运行\r\n".repeat(10000)
        val bytes = original.toByteArray()
        var offset = 0L
        val all = StringBuilder()
        do {
            val page = DocumentTextWindow.read(ByteArrayInputStream(bytes), offset)
            all.append(page.text)
            assertTrue(page.nextOffset > offset)
            offset = page.nextOffset
        } while (page.truncated)
        assertEquals(original, all.toString())
        assertEquals(bytes.size.toLong(), offset)
    }
    @Test fun readLineRangeAndSearchDoNotReturnAllContent() {
        val input = "one\ntwo\nneedle here\nfour\n"
        val line = DocumentTextWindow.read(input.byteInputStream(), startLine = 2, lineCount = 1)
        assertEquals("two\n", line.text)
        assertEquals(2L, line.firstLine)
        val search = DocumentTextWindow.read(input.byteInputStream(), query = "NEEDLE")
        assertEquals("needle here\n", search.text)
    }
    @Test fun longLineMakesForwardProgressWithoutSplittingCodepoint() {
        val text = "😀".repeat(10000)
        val page = DocumentTextWindow.read(text.byteInputStream())
        assertEquals(DocumentTextWindow.MAX_OUTPUT_BYTES, page.text.toByteArray().size)
        assertEquals(page.text.toByteArray().size.toLong(), page.nextOffset)
        assertTrue(page.truncated)
        assertFalse(page.text.last().isHighSurrogate())
    }
    @Test fun utf8CutAtScanBoundaryIsNotMalformed() {
        val text = "a".repeat(DocumentTextWindow.MAX_SCAN_BYTES + 3) + "😀\n"
        val page = DocumentTextWindow.read(text.byteInputStream(), query = "missing")
        assertTrue(page.truncated)
        val tail = DocumentTextWindow.read(text.byteInputStream(), page.nextOffset)
        assertTrue(tail.text.endsWith("😀\n"))
        assertEquals(text.toByteArray().size.toLong(), tail.nextOffset)
    }
    @Test fun searchFindsCrossWindowKeywordAndSnippetContainsLateHit() {
        val text = "a".repeat(DocumentTextWindow.MAX_SCAN_BYTES + 1) + "needle" + "b".repeat(9000)
        val page = DocumentTextWindow.read(text.byteInputStream(), query = "needle")
        assertTrue(page.truncated)
        val next = DocumentTextWindow.read(text.byteInputStream(), page.nextOffset, query = "needle")
        assertTrue(next.text.contains("needle"))
        val late = DocumentTextWindow.read(("x".repeat(20000) + "NEEDLE").byteInputStream(), query = "needle")
        assertTrue(late.text.contains("NEEDLE")); assertTrue(late.partialLine)
    }
    @Test fun distantLineSeekCarriesRemainingSkipCount() {
        val text = "x\n".repeat(600000) + "target\n"
        val page = DocumentTextWindow.read(text.byteInputStream(), startLine = 600001)
        assertEquals("", page.text); assertTrue(page.remainingLines > 0)
        val bytes = text.toByteArray()
        val input = bytes.inputStream()
        val next = DocumentTextWindow.read(input, offset = page.nextOffset, skipLines = page.remainingLines,
            seek = { offset -> input.skip(offset) == offset })
        assertEquals("target\n", next.text)
    }
    @Test fun searchOverlapPreservesRemainingLineRange() {
        val bytes = (("x".repeat(262144) + "\n").repeat(4) + "HIT\nend\n").toByteArray()
        val first = DocumentTextWindow.read(bytes.inputStream(), startLine = 6, query = "HIT")
        assertEquals("", first.text)
        assertTrue(first.truncated)
        val input = bytes.inputStream()
        val next = DocumentTextWindow.read(input, offset = first.nextOffset, skipLines = first.remainingLines,
            query = "HIT", seek = { at -> input.skip(at) == at })
        assertEquals("", next.text)
        assertFalse(next.truncated)
        assertEquals(bytes.size.toLong(), next.nextOffset)
    }
    @Test fun rejectsBinaryMalformedUtf8AndCancellation() {
        assertThrows(IllegalArgumentException::class.java) { DocumentTextWindow.read("a\u0000b".byteInputStream()) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            DocumentTextWindow.read(byteArrayOf(0xc0.toByte()).inputStream())
        }
        assertThrows(InterruptedException::class.java) { DocumentTextWindow.read("abc".byteInputStream(), cancelled = { true }) }
    }
}
