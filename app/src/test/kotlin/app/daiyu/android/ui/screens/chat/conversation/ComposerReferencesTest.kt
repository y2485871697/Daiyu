package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class ComposerReferencesTest {
    private val file = "文件引用（未读取）：content://documents/document/image%3A100"
    private val folder = "目录引用（未读取）：content://documents/tree/primary%3A中文+目录"
    @Test fun ordinaryContentAndWhitespaceAreUnchanged() {
        for (raw in listOf("", "  ", "😀\n\n 文本\n", "content://ordinary/text", "说明：$file", "路径引用（未读取）：")) {
            val p = ComposerReferences(raw)
            assertTrue(p.references.isEmpty()); assertEquals(raw, p.text); assertEquals(raw, p.edit(p.text))
            for (i in 0..raw.length) { assertEquals(i, p.toSource(i)); assertEquals(i, p.toVisible(i)) }
        }
    }
    @Test fun oldDraftDisplaysBodyOnlyAndOpeningDoesNotRewrite() {
        val raw = "  正文 😀\n$file\n$folder"
        val p = ComposerReferences(raw)
        assertEquals("  正文 😀", p.text); assertEquals(2, p.references.size)
        assertEquals(raw, p.edit(p.text)); assertEquals(p.text.length, p.toVisible(raw.length))
        for (i in 0..p.text.length) assertEquals(i, p.toVisible(p.toSource(i)))
    }
    @Test fun bodyEditsKeepExactReferenceEncodingAndOrder() {
        val p = ComposerReferences("old\n$file\n$folder")
        assertEquals("  新文\n\n😀\n$file\n$folder", p.edit("  新文\n\n😀"))
        assertEquals("$file\n$folder", p.edit(""))
    }
    @Test fun referencesOnlyHaveEmptyEditableBody() {
        val p = ComposerReferences("$file\n$folder")
        assertEquals("", p.text); assertEquals(0, p.toVisible(p.source.length))
        assertEquals("body\n$file\n$folder", p.edit("body"))
    }
    @Test fun removalDeletesOnlyOneOccurrenceAndKeepsBodyWhitespace() {
        val p = ComposerReferences("  body\n$file\n$file\n\n$folder")
        assertEquals("  body\n$file\n\n$folder", p.remove(p.references[0]))
        assertEquals("  body\n$file\n$file\n", p.remove(p.references[2]))
    }
    @Test fun interleavedReferencesHaveMonotonicUtf16Mapping() {
        val p = ComposerReferences("$file\n😀first\n$folder\nlast\n")
        assertEquals("😀first\nlast\n", p.text)
        for (i in 0..p.text.length) assertEquals(i, p.toVisible(p.toSource(i)))
        for (i in 1..p.source.length) assertTrue(p.toVisible(i) >= p.toVisible(i - 1))
    }
    @Test fun activeCompositionDoesNotHideTheLineBeingTyped() {
        val raw = "$file\nabc"
        val active = ComposerReferences(raw, 0 until file.length)
        assertEquals(raw, active.text); assertTrue(active.references.isEmpty())
        val committed = ComposerReferences(raw)
        assertEquals("abc", committed.text)
        assertEquals(1, committed.toVisible(file.length + 2))
        assertEquals(file.length + 2, committed.toSource(1))
    }
    @Test fun dictationReplacesOnlyVisibleBodyAcrossReferences() {
        for (raw in listOf("甲\n$file\n乙", file, "$file\n$folder")) {
            val p = ComposerReferences(raw)
            val speech = app.daiyu.android.voice.offline.SpeechDraft(p.text, p.text.length, 0)
            val body = checkNotNull(speech.accept(p.text, "听写😀"))
            val next = p.edit(body)
            assertEquals("听写😀", ComposerReferences(next).text)
            assertEquals(p.references.map { it.serialized }, ComposerReferences(next).references.map { it.serialized })
            assertEquals(body.length, ComposerReferences(next).toVisible(ComposerReferences(next).toSource(speech.cursor)))
        }
    }
    @Test fun labelsDecodeWithoutReadingSourcesOrConvertingPlusToSpace() {
        val p = ComposerReferences(folder)
        assertEquals("中文+目录", p.references.single().label)
        assertFalse(p.references.single().label.contains("content://"))
    }
    @org.junit.Test fun metadataMovesAndRemovesWithItsReference() {
        val reference = "文件引用（未读取）：content://fixture/document/one"
        val details = "附件信息（按需读取）：large.log（12345678 字节）；使用 read_text_file 按需读取"
        val source = "问题\n$reference\n$details"
        val projected = ComposerReferences(source)
        org.junit.Assert.assertEquals("问题", projected.text)
        org.junit.Assert.assertTrue(projected.references.single().label.contains("large.log"))
        org.junit.Assert.assertEquals("修改\n$reference\n$details", projected.edit("修改"))
        org.junit.Assert.assertEquals("问题", projected.remove(projected.references.single()))
    }
}
