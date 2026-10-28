package app.daiyu.android.voice.offline

import app.daiyu.android.ui.screens.chat.conversation.ComposerUi
import app.daiyu.android.ui.screens.chat.conversation.textActionEnabled
import org.junit.Assert.*
import org.junit.Test

class SpeechDraftPreviewTest {
    @Test fun partialsReplaceTheCapturedRegionWithoutConsumingTheFinalDraft() {
        val original = "before OLD after"
        val draft = SpeechDraft(original, 7, 10)
        assertEquals("before 你 after", draft.preview(original, "你")?.text)
        assertEquals("before 你们好 after", draft.preview(original, "你们好")?.text)
        assertEquals("before 你好 after", draft.preview(original, "你好")?.text)
        assertEquals(original, draft.expectedText)
        assertEquals(7, draft.cursor)
        assertEquals("before 你好！ after", draft.accept(original, "你好！"))
        assertEquals(10, draft.cursor)
    }

    @Test fun repeatedProjectionHasNoSideEffectsAndStaleTextIsRejected() {
        val draft = SpeechDraft("leftRIGHTtail", 9, 4)
        val first = draft.preview("leftRIGHTtail", "中间")
        assertEquals(first, draft.preview("leftRIGHTtail", "中间"))
        assertEquals("left中间tail", first?.text)
        assertEquals(6, first?.cursor)
        assertNull(draft.preview("user edited", "late"))
        assertEquals("leftRIGHTtail", draft.expectedText)
    }

    @Test fun cursorMovementMapsBackToOriginalTextWithoutConfirmingPartial() {
        val draft = SpeechDraft("before OLD after", 7, 10)
        val preview = checkNotNull(draft.preview(draft.expectedText, "你好"))
        assertEquals(2, preview.originalOffset(2))
        assertEquals(7, preview.originalOffset(8))
        assertEquals(10, preview.originalOffset(9))
        assertEquals(11, preview.originalOffset(10))
        assertEquals(0, preview.originalOffset(-10))
        assertEquals(draft.expectedText.length, preview.originalOffset(999))
        assertEquals("before OLD after", draft.expectedText)
    }

    @Test fun insertionAndUnicodeUseTheSameUtf16OffsetsAsTheComposer() {
        val insert = SpeechDraft("lefttail", 4, 4)
        val value = checkNotNull(insert.preview("lefttail", "你好"))
        assertEquals("left你好tail", value.text)
        assertEquals(6, value.cursor)
        assertEquals(4, value.originalOffset(5))
        assertEquals(4, value.originalOffset(6))
        val unicode = SpeechDraft("A😀B", 3, 1)
        assertEquals("A语音B", unicode.preview("A😀B", "语音")?.text)
        assertEquals("A😀B", unicode.expectedText)
        assertEquals("A好B", unicode.accept("A😀B", "好"))
        assertEquals(2, unicode.cursor)
    }

    @Test fun emptyHypothesisDoesNotChangeTheStoredDraft() {
        val draft = SpeechDraft("prefix OLD suffix", 7, 10)
        assertEquals("prefix  suffix", draft.preview(draft.expectedText, "")?.text)
        assertEquals("prefix OLD suffix", draft.expectedText)
    }

    @Test fun provisionalTextCannotEnableSendingButKeepsVoiceControlsEnabled() {
        val composer = ComposerUi(text = "实时识别", dictationPreview = true, listening = true)
        assertFalse(composer.textActionEnabled())
        assertTrue(composer.enabled)
        assertTrue(composer.listening)
        assertTrue(composer.copy(dictationPreview = false).textActionEnabled())
    }
}
