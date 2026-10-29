package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.*
import org.junit.Test

class MarkdownRevealInvalidationTest {
    @Test fun intermediateFramesInvalidateTheTextLeafButNotWorkflowReadiness() {
        val target = MarkdownTypingTarget(listOf(
            MarkdownTypingPart(MarkdownTypingSource("first", "abcdefghijkl"), 12),
            MarkdownTypingPart(MarkdownTypingSource("second", "mnopqrst"), 8),
        ), start = 0, streaming = true, terminal = false)
        val frame = mutableStateOf(MarkdownTypingFrame(target, 0))
        val reveal = MarkdownReveal(target, frame, exact = true)
        Snapshot.sendApplyNotifications()
        val observer = SnapshotStateObserver { it() }
        var workflowInvalidations = 0
        var firstInvalidations = 0
        var leafInvalidations = 0
        observer.start()
        try {
            observer.observeReads("workflow", { _: String -> workflowInvalidations++ }) {
                assertFalse(reveal.settled)
                assertFalse(reveal.ready("second", "mnopqrst"))
            }
            observer.observeReads("first", { _: String -> firstInvalidations++ }) {
                assertFalse(reveal.ready("first", "abcdefghijkl"))
            }
            val onLeafChanged: (String) -> Unit = { leafInvalidations++ }
            fun observeLeaf() = observer.observeReads("leaf", onLeafChanged) {
                reveal.limit("first")
            }
            observeLeaf()
            for (visible in 1..11) {
                frame.value = MarkdownTypingFrame(target, visible)
                Snapshot.sendApplyNotifications()
                observeLeaf()
            }
            assertEquals(11, leafInvalidations)
            assertEquals(0, firstInvalidations)
            assertEquals(0, workflowInvalidations)
            frame.value = MarkdownTypingFrame(target, 12)
            Snapshot.sendApplyNotifications()
            assertEquals(1, firstInvalidations)
            assertEquals(0, workflowInvalidations)
            frame.value = MarkdownTypingFrame(target, 20)
            Snapshot.sendApplyNotifications()
            assertEquals(1, workflowInvalidations)
            assertTrue(reveal.settled)
            assertTrue(reveal.ready("second", "mnopqrst"))
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun oldFrameAndMismatchedSourceCannotOpenTheNextContent() {
        val old = MarkdownTypingTarget(listOf(MarkdownTypingPart(MarkdownTypingSource("id", "old"), 3)),
            start = 0, streaming = true, terminal = false)
        val next = MarkdownTypingTarget(listOf(MarkdownTypingPart(MarkdownTypingSource("id", "new text"), 8)),
            start = 2, streaming = true, terminal = false)
        val frame = mutableStateOf(MarkdownTypingFrame(old, 100))
        val reveal = MarkdownReveal(next, frame, exact = true)
        assertEquals(2, reveal.limit("id"))
        assertFalse(reveal.settled)
        assertFalse(reveal.ready("id", "new text"))
        frame.value = MarkdownTypingFrame(next, 8)
        assertTrue(reveal.settled)
        assertTrue(reveal.ready("id", "new text"))
        assertFalse(reveal.ready("id", "old"))
        assertFalse(reveal.ready("missing", ""))
    }

    @Test fun aFullyRevealedStaleProjectionIsNotSettled() {
        val target = MarkdownTypingTarget(listOf(MarkdownTypingPart(MarkdownTypingSource("id", "text"), 4)),
            start = 4, streaming = false, terminal = true)
        val reveal = MarkdownReveal(target, mutableStateOf(MarkdownTypingFrame(target, 4)), exact = false)
        assertFalse(reveal.settled)
    }
}
