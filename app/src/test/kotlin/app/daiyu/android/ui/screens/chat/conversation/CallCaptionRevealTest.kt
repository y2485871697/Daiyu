package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class CallCaptionRevealTest {
    @Test fun firstFinalSnapshotUsesTheConversationClockAndDrainsWithoutMoreNetworkEvents() {
        val caption = CallCaptionTypingState()
        val target = caption.update("字".repeat(500), true)
        assertEquals(0, target.start)
        val conversation = MarkdownTypingState(emptyList())
        val reference = checkNotNull(conversation.update(target.parts, 0, false, true, true, true))
        for (frame in 1..600) {
            val nanos = frame * 16_000_000L
            assertEquals(conversation.advance(reference, nanos), caption.advance(target, nanos))
            if (frame <= 64) assertEquals(frame, caption.visible(target))
        }
        assertEquals(500, caption.visible(target))
    }
    @Test fun completeDoesNotFlushAndRepeatedSnapshotDoesNotRestart() {
        val typing = CallCaptionTypingState()
        val streaming = typing.update("答".repeat(500), false)
        for (frame in 1..8) typing.advance(streaming, frame * 16_000_000L)
        val before = typing.visible(streaming)
        val final = typing.update("答".repeat(500), true)
        assertEquals(before, final.start)
        assertSame(final, typing.update("答".repeat(500), true))
        for (frame in 9..24) typing.advance(final, frame * 16_000_000L)
        assertTrue(typing.visible(final) < before + 22)
        assertTrue(typing.visible(final) < 500)
    }
    @Test fun correctionAndPauseNeverRevealTheWholeReplacementOrHiddenTime() {
        val typing = CallCaptionTypingState()
        var target = typing.update("旧".repeat(100), false)
        typing.advance(target, 1L); typing.advance(target, 16_000_001L)
        target = typing.update("新".repeat(100), true)
        assertEquals(0, target.start)
        val beforePause = typing.advance(target, 32_000_001L)
        typing.pause(target)
        assertEquals(beforePause + 1, typing.advance(target, 10_000_000_001L))
        assertEquals("😀", "😀组合".take(MarkdownTextBoundaries("😀组合").end(1)))
    }
}
