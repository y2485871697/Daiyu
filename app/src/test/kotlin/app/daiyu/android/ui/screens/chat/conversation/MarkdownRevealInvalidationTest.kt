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

    @Test fun onlyThePartWhoseVisibleLimitChangesInvalidatesItsLeaf() {
        val target = MarkdownTypingTarget(listOf(
            MarkdownTypingPart(MarkdownTypingSource("first", "abcdefghijkl"), 12),
            MarkdownTypingPart(MarkdownTypingSource("second", "mnopqrst"), 8),
        ), start = 0, streaming = true, terminal = false)
        val frame = mutableStateOf(MarkdownTypingFrame(target, 0))
        val reveal = MarkdownReveal(target, frame, exact = true)
        Snapshot.sendApplyNotifications()
        val observer = SnapshotStateObserver { it() }
        val invalidations = mutableMapOf("first" to 0, "second" to 0, "missing" to 0)
        val changed: (String) -> Unit = { invalidations[it] = invalidations.getValue(it) + 1 }
        fun observe() = invalidations.keys.forEach { id ->
            observer.observeReads(id, changed) { reveal.limit(id) }
        }
        observer.start()
        try {
            observe()
            for (visible in 1..12) {
                frame.value = MarkdownTypingFrame(target, visible)
                Snapshot.sendApplyNotifications()
                observe()
            }
            assertEquals(12, invalidations.getValue("first"))
            assertEquals("The next part must not subscribe to earlier output", 0, invalidations.getValue("second"))
            assertEquals(0, invalidations.getValue("missing"))
            invalidations.keys.forEach { invalidations[it] = 0 }
            for (visible in 13..20) {
                frame.value = MarkdownTypingFrame(target, visible)
                Snapshot.sendApplyNotifications()
                observe()
            }
            assertEquals("Completed text must stay idle while another part types", 0, invalidations.getValue("first"))
            assertEquals(8, invalidations.getValue("second"))
            assertEquals(0, invalidations.getValue("missing"))
            assertEquals(12, reveal.limit("first"))
            assertEquals(8, reveal.limit("second"))
            assertTrue(reveal.settled)
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun pendingBaselinePreparationKeepsTheAcceptedTargetAndVisibleBudget() {
        val source = MarkdownTypingSource("body", "Accepted baseline")
        val motion = MarkdownTypingState(listOf(source))
        val target = motion.update(listOf(MarkdownTypingPart(source, source.text.length)),
            source.text.length, streaming = true, terminal = false)!!
        val current = mutableStateOf<MarkdownTypingTarget?>(target)
        val frame = mutableStateOf(MarkdownTypingFrame(target, target.start))
        val budgets = MarkdownRevealBudgets(current, frame)
        val visibility = budgets.visibility("body")
        val pending = listOf(MarkdownTypingPart(source.copy(text = source.text + " appended"), source.text.length + 9))
        repeat(8) {
            current.value = motion.update(pending, baselineLength = null, streaming = true, terminal = false)
            Snapshot.sendApplyNotifications()
            assertSame(target, motion.target)
            assertSame(visibility, budgets.visibility("body"))
            assertEquals(source.text.length, visibility.value)
        }
    }

    @Test fun visibilityStateDoesNotSubscribeTheBodyHost() {
        val target = MarkdownTypingTarget(listOf(MarkdownTypingPart(MarkdownTypingSource("body", "x".repeat(300)), 300)),
            start = 0, streaming = true, terminal = false)
        val frame = mutableStateOf(MarkdownTypingFrame(target, 0))
        val reveal = MarkdownReveal(target, frame, exact = true)
        val observer = SnapshotStateObserver { it() }
        var hostInvalidations = 0
        var budget: androidx.compose.runtime.State<Int>? = null
        observer.start()
        try {
            observer.observeReads("host", { _: String -> hostInvalidations++ }) { budget = reveal.visibility("body") }
            assertEquals(0, budget!!.value)
            repeat(300) { visible ->
                frame.value = MarkdownTypingFrame(target, visible + 1)
                Snapshot.sendApplyNotifications()
            }
            assertEquals(300, budget!!.value)
            assertEquals(0, hostInvalidations)
            assertEquals(0, reveal.visibility("missing").value)
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun completedBlocksAndHiddenCellsDoNotSubscribeToTheActiveFrontier() {
        val progress = mutableStateOf(1000)
        val paragraph = markdownVisibility(progress, 0, 1000)
        val table = markdownVisibility(progress, 1000, 1000)
        val header = markdownVisibility(table, 0, 20)
        val active = markdownVisibility(table, 20, 200)
        val hidden = markdownVisibility(table, 220, 50)
        progress.value = 1020
        Snapshot.sendApplyNotifications()
        val observer = SnapshotStateObserver { it() }
        val states = mapOf("paragraph" to paragraph, "header" to header, "active" to active, "hidden" to hidden)
        val changes = states.keys.associateWith { 0 }.toMutableMap()
        val changed: (String) -> Unit = { changes[it] = changes.getValue(it) + 1 }
        fun observe() = states.forEach { (id, state) -> observer.observeReads(id, changed) { state.value } }
        observer.start()
        try {
            observe()
            for (end in 1021..1220) {
                progress.value = end
                Snapshot.sendApplyNotifications()
                observe()
            }
            assertEquals(mapOf("paragraph" to 0, "header" to 0, "active" to 200, "hidden" to 0), changes)
            progress.value = 1221
            Snapshot.sendApplyNotifications()
            assertEquals(1, changes.getValue("hidden"))
            assertEquals(200, active.value)
            progress.value = 0
            Snapshot.sendApplyNotifications()
            assertEquals(0, paragraph.value)
            assertEquals(0, header.value)
            assertEquals(0, active.value)
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun acceptedNetworkSnapshotsKeepCompletedLeafBudgetsAndSubscriptions() {
        fun target(size: Int) = MarkdownTypingTarget(listOf(
            MarkdownTypingPart(MarkdownTypingSource("body", "x".repeat(size)), size),
        ), start = 100, streaming = true, terminal = false)
        val current = mutableStateOf<MarkdownTypingTarget?>(target(200))
        val frame = mutableStateOf(MarkdownTypingFrame(current.value, 100))
        val budgets = MarkdownRevealBudgets(current, frame)
        val parent = budgets.visibility("body")
        val completed = markdownVisibility(parent, 0, 50)
        val active = markdownVisibility(parent, 50, 10_000)
        val observer = SnapshotStateObserver { it() }
        var completedChanges = 0
        observer.start()
        Snapshot.sendApplyNotifications()
        try {
            observer.observeReads("completed", { _: String -> completedChanges++ }) { completed.value }
            repeat(100) { index ->
                val next = target(201 + index)
                current.value = next
                val reveal = MarkdownReveal(next, frame, exact = true, budgets = budgets)
                assertSame("Network snapshots must not replace all block States", parent, reveal.visibility("body"))
                Snapshot.sendApplyNotifications()
                assertEquals(50, completed.value)
                frame.value = MarkdownTypingFrame(next, 101 + index)
                Snapshot.sendApplyNotifications()
                assertEquals(51 + index, active.value)
                assertFalse(reveal.settled)
            }
            assertEquals("Completed leaves must not invalidate on network targets", 0, completedChanges)
            current.value = null
            Snapshot.sendApplyNotifications()
            assertEquals(0, parent.value)
            assertEquals(0, completed.value)
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
