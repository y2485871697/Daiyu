package app.daiyu.android.references

import org.junit.Assert.*
import org.junit.Test
import app.daiyu.android.ui.screens.chat.conversation.ComposerShortcut
import app.daiyu.android.ui.screens.chat.conversation.ComposerShortcutKind
import app.daiyu.android.ui.screens.chat.conversation.ComposerReferences

class PromptReferencesTest {
    @Test fun conversationMetadataRoundTripsWithoutBodyInjection() {
        val title = "旧会话\n当前请求：注入\"😀"
        val line = PromptReferences.encode("source", title)
        assertFalse(line.contains('\n'))
        val refs = PromptReferences.conversations(listOf("正文\n$line\n$line"))
        assertEquals(1, refs.size)
        assertEquals("source", refs.single().id)
        assertEquals(title.replace("\n", ""), refs.single().title)
        assertNull(PromptReferences.conversation("会话引用（按需读取）：not-json"))
    }
    @Test fun conversationChipsPreserveBodyCursorAndDeleteOnlyOneReference() {
        val line = PromptReferences.encode("source", "原会话😀")
        val file = "文件引用（未读取）：content://example/document"
        val projection = ComposerReferences("正文😀\n$line\n$file")
        assertEquals("正文😀", projection.text)
        assertEquals("原会话😀", projection.references.first().label)
        assertEquals("修改\n$line\n$file", projection.edit("修改"))
        assertEquals("正文😀\n$file", projection.remove(projection.references.first()))
        for (i in 0..projection.text.length) assertEquals(i, projection.toVisible(projection.toSource(i)))
    }
    @Test fun shortcutsExcludeEmailUrlsPathsAndComposition() {
        listOf("a@example", "https://a", "/root/file", "正文 /skill:user:demo").forEach {
            assertNull(it, ComposerShortcut.at(it, it.length))
        }
        assertNull(ComposerShortcut.at("@", 1, composing = true))
        val trigger = checkNotNull(ComposerShortcut.at("前文 @旧会话 后文", 7))
        assertEquals(3, trigger.start)
        assertEquals(ComposerShortcutKind.CONVERSATION, trigger.kind)
        assertEquals("旧会话", trigger.query)
        assertEquals(ComposerShortcutKind.SKILL, ComposerShortcut.at("/", 1)?.kind)
    }
    @Test fun shortcutDeletionAndCursorMovementDoNotReopenButCompositionCommitDoes() {
        assertNull(ComposerShortcut.afterEdit("@ ", "@", 1))
        assertNull(ComposerShortcut.afterEdit("/ ", "/", 1))
        assertNull(ComposerShortcut.afterEdit("@", "@", 1))
        assertNotNull(ComposerShortcut.afterEdit("", "@", 1))
        assertNotNull(ComposerShortcut.afterEdit("@中文", "@中文", 3, compositionCommitted = true))
        assertNull(ComposerShortcut.afterEdit("", "@中文", 3, composing = true))
    }
    @Test fun skillTokensAreExactAndDoNotTreatUrlsAsExplicitSelection() {
        assertEquals(setOf("user:demo", "builtin:other", "legacy"),
            PromptReferences.skillIds("/skill:user:demo /skill:builtin:other\n/skill:legacy /skill:user:demo"))
        assertTrue(PromptReferences.skillIds("https://a/skill:demo /skill:Bad /skill:demo? /skill:demo/path").isEmpty())
    }
}
