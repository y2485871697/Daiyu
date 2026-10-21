package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Deterministic admission/worker fixtures, not a claim that ChatController is wired. */
class ContextManagementControllerAdapterTest {
    private class Queue : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runOne() { tasks.removeFirst().run() }
        val size get() = tasks.size
    }
    private val owner = ContextManagementOwner(ConversationId("exact-conversation"), 7)
    private val message = Message(MessageId("original"), owner.conversationId, RunId("run"), "user",
        listOf(MessageBlock.Text("text", "untrusted original")), RunStatus.COMPLETED)
    private fun snapshot(archive: String? = "archive") = ContextManagementSnapshot(owner.conversationId,
        ContextRevision.digest(listOf(message)), listOf(message), PreparedContext(listOf(message), 42, archive),
        CompressionSettings(automatic = false, contextWindowTokens = 10000, reserveTokens = 1000,
            summaryModel = SummaryModelBinding("summary-only", "model"), keepTurns = 1),
        SummaryProfileDescriptor(SummaryModelBinding("summary-only", "model"), "revision-1",
            "https://summary.invalid/v1/responses", "actual-api-model"))

    private inner class Gate : ContextManagementAdmission {
        var current = true
        var busy = false
        var finishes = 0
        var last: ContextManagementLease? = null
        override fun isCurrent(owner: ContextManagementOwner) = current && owner == this@ContextManagementControllerAdapterTest.owner
        override fun tryReserve(owner: ContextManagementOwner): ContextManagementLease? {
            if (!isCurrent(owner) || busy) return null
            busy = true
            val requested = owner
            return object : ContextManagementLease {
                override val owner = requested
                override val cancellation = ContextPreparationCancellation()
                override fun checkCurrent() { check(isCurrent(owner)); cancellation.check() }
                override fun finish() { check(busy); busy = false; finishes++ }
            }.also { last = it }
        }
    }
    private inner class Backend : ContextManagementBackend {
        var reads = 0
        var archives = mutableListOf<Int>()
        var compressions = 0
        var failure = false
        var profileChanged = false
        var duringCompression: (() -> Unit)? = null
        var selection: ContextManagementSelection? = null
        var selected: ContextManagementSnapshot? = null
        var compressedSnapshot: ContextManagementSnapshot? = null
        override fun selectCompression(lease: ContextManagementLease, expected: ContextManagementSnapshot,
            selection: ContextManagementSelection): ContextManagementSnapshot {
            this.selection = selection
            lease.cancellation.check()
            val binding = selection.binding ?: SummaryModelBinding("explicit-conversation", "current-model")
            return expected.copy(configuration = expected.configuration!!.copy(summaryModel = binding),
                summaryTarget = SummaryProfileDescriptor(binding, "one-shot", "https://chosen.invalid/v1/responses", "chosen-api"))
                .also { selected = it }
        }
        override fun snapshot(lease: ContextManagementLease): ContextManagementSnapshot {
            reads++
            return this@ContextManagementControllerAdapterTest.snapshot()
        }
        override fun archive(lease: ContextManagementLease, snapshot: ContextManagementSnapshot,
            offset: Int): ContextManagementArchive {
            archives.add(offset)
            return ContextManagementArchive("archive", offset, ArchivePage(listOf(message), if (offset == 0) 50 else null, 51))
        }
        override fun compress(lease: ContextManagementLease, expected: ContextManagementSnapshot): ContextCompressionReceipt {
            compressions++
            compressedSnapshot = expected
            duringCompression?.invoke()
            lease.cancellation.check()
            if (profileChanged) throw SummaryProfileChangedException()
            if (failure) error("unknown persistence outcome")
            return ContextCompressionReceipt("new-archive", expected.historyRevision)
        }
    }
    private inner class Fixture(workerOverride: Executor? = null) {
        val gate = Gate()
        val backend = Backend()
        val worker = Queue()
        val ui = Queue()
        val adapter = ControlledContextManagementAdapter(owner, gate, backend, workerOverride ?: worker, ui)
        fun load() { adapter.refresh(); worker.runOne(); ui.runOne() }
        fun startCompression() { adapter.requestCompression(); adapter.confirmCompression() }
    }

    @Test fun selectionRetainsSavedBaselineAndRequiresNewExactSnapshotConsent() {
        val f = Fixture(); f.load()
        val before = f.adapter.state.value.snapshot!!
        f.adapter.requestCompression()
        val selection = ContextManagementSelection(SummaryModelBinding("other", "chosen"),
            app.daiyu.android.model.ProviderProtocol.RESPONSES)
        assertTrue(f.adapter.selectCompression(selection))
        assertFalse(f.adapter.state.value.confirmingCompression)
        assertEquals(ContextManagementOperation.SELECT, f.adapter.state.value.operation)
        f.adapter.confirmCompression()
        assertEquals(1, f.worker.size)
        f.worker.runOne(); f.ui.runOne()
        val selected = f.adapter.state.value.snapshot!!
        assertSame(f.backend.selected, selected)
        assertSame(before.savedConfiguration, selected.savedConfiguration)
        assertEquals(selection.binding, selected.configuration!!.summaryModel)
        assertEquals(selection, f.backend.selection)
        assertTrue(f.adapter.state.value.confirmingCompression)
        assertEquals(0, f.backend.compressions)
        f.adapter.confirmCompression(); f.worker.runOne(); f.ui.runOne()
        assertSame(selected, f.backend.compressedSnapshot)
        assertEquals(1, f.backend.compressions)
    }

    @Test fun cancelledSelectionDrainsOriginalSlotAndCannotPublishOrCompress() {
        val f = Fixture(); f.load()
        assertTrue(f.adapter.selectCompression(ContextManagementSelection(null,
            app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS)))
        f.adapter.cancel()
        assertTrue(f.gate.busy)
        assertTrue(f.gate.last!!.cancellation.isCancelled)
        f.worker.runOne(); f.ui.runOne()
        assertFalse(f.gate.busy)
        assertTrue(f.adapter.state.value.requiresRefresh)
        assertFalse(f.adapter.state.value.confirmingCompression)
        assertFalse(f.adapter.selectCompression(ContextManagementSelection(null,
            app.daiyu.android.model.ProviderProtocol.RESPONSES)))
        assertNull(f.backend.selection)
        assertEquals(0, f.backend.compressions)
    }

    @Test fun selectionCancelledAfterWorkerDrainBeforeUiCannotRearmConfirmation() {
        val f = Fixture(); f.load()
        val before = f.adapter.state.value.snapshot
        assertTrue(f.adapter.selectCompression(ContextManagementSelection(null,
            app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS)))
        f.worker.runOne()
        assertFalse(f.gate.busy)
        f.adapter.cancel()
        f.ui.runOne()
        assertSame(before, f.adapter.state.value.snapshot)
        assertTrue(f.adapter.state.value.requiresRefresh)
        assertFalse(f.adapter.state.value.confirmingCompression)
        assertTrue(f.adapter.state.value.notice!!.contains("已取消"))
        f.adapter.confirmCompression()
        assertEquals(0, f.worker.size)
        assertEquals(0, f.backend.compressions)
    }

    @Test fun constructorBrowseAndConfirmationNeverCompressOrSend() {
        val f = Fixture()
        assertEquals(0, f.worker.size)
        assertEquals(0, f.backend.reads)
        f.load()
        f.adapter.confirmCompression() // Confirmation cannot be bypassed.
        assertEquals(0, f.worker.size)
        f.adapter.requestCompression()
        assertTrue(f.adapter.state.value.confirmingCompression)
        assertEquals(0, f.backend.compressions)
        f.adapter.dismissCompression()
        assertFalse(f.adapter.state.value.confirmingCompression)
        assertEquals(0, f.worker.size)
    }

    @Test fun sharedBusyAdmissionRejectsBeforeBackendAndRetainsSnapshot() {
        val f = Fixture(); f.load()
        val before = f.adapter.state.value.snapshot
        f.gate.busy = true // Same gate that a parent send would hold.
        f.startCompression()
        assertEquals(0, f.worker.size)
        assertEquals(0, f.backend.compressions)
        assertSame(before, f.adapter.state.value.snapshot)
        assertFalse(f.adapter.state.value.confirmingCompression)
    }

    @Test fun cancellationKeepsSlotUntilWorkerDrainAndUsesSameToken() {
        val f = Fixture(); f.load(); f.startCompression()
        val token = f.gate.last!!.cancellation
        f.adapter.cancel()
        assertTrue(token.isCancelled)
        assertTrue(f.gate.busy)
        assertTrue(f.adapter.state.value.cancellationRequested)
        f.adapter.refresh(); f.adapter.confirmCompression()
        assertEquals(1, f.worker.size)
        f.worker.runOne()
        assertFalse(f.gate.busy)
        assertEquals(0, f.backend.compressions)
        assertEquals(ContextManagementOperation.COMPRESS, f.adapter.state.value.operation)
        f.ui.runOne()
        assertNull(f.adapter.state.value.operation)
        assertTrue(f.adapter.state.value.requiresRefresh)
        assertEquals(2, f.gate.finishes)
    }

    @Test fun activeTransportCancellationDoesNotAdmitAnotherOperation() {
        val f = Fixture(); f.load()
        f.backend.duringCompression = {
            f.adapter.cancel()
            assertTrue(f.gate.busy)
            f.adapter.refresh()
            assertEquals(0, f.worker.size)
        }
        f.startCompression(); f.worker.runOne(); f.ui.runOne()
        assertEquals(1, f.backend.compressions)
        assertTrue(f.adapter.state.value.requiresRefresh)
        assertFalse(f.gate.busy)
    }

    @Test fun closeAndSelectionChangeFenceLateContentsButStillFinishOldLease() {
        val f = Fixture(); f.adapter.refresh()
        f.worker.runOne()
        f.gate.current = false
        f.ui.runOne()
        assertNull(f.adapter.state.value.snapshot)
        assertEquals(1, f.gate.finishes)
        f.gate.current = true
        f.adapter.refresh()
        f.adapter.close()
        assertTrue(f.gate.busy)
        f.worker.runOne(); f.ui.runOne()
        assertNull(f.adapter.state.value.snapshot)
        assertEquals(2, f.gate.finishes)
        f.adapter.refresh()
        assertEquals(0, f.worker.size)
    }

    @Test fun leavingPageOrInvalidatingVisitMakesOldConfirmationUnusable() {
        val closed = Fixture(); closed.load(); closed.adapter.requestCompression()
        closed.adapter.close(); closed.adapter.confirmCompression()
        assertEquals(0, closed.worker.size)
        assertEquals(0, closed.backend.compressions)
        assertFalse(closed.adapter.state.value.confirmingCompression)
        assertNull(closed.adapter.state.value.snapshot)

        val changed = Fixture(); changed.load(); changed.adapter.requestCompression()
        changed.gate.current = false
        changed.adapter.confirmCompression()
        assertEquals(0, changed.worker.size)
        assertFalse(changed.adapter.state.value.confirmingCompression)
        changed.gate.current = true // Even a bad host reusing owner cannot replay dismissed consent.
        changed.adapter.confirmCompression()
        assertEquals(0, changed.worker.size)
        assertEquals(0, changed.backend.compressions)
    }

    @Test fun refreshDiscardsOldConfirmationAndRequiresNewExplicitConsent() {
        val f = Fixture(); f.load(); f.adapter.requestCompression()
        f.adapter.refresh(); f.worker.runOne(); f.ui.runOne()
        f.adapter.confirmCompression()
        assertEquals(0, f.worker.size)
        f.startCompression(); f.worker.runOne(); f.ui.runOne()
        assertEquals(1, f.backend.compressions)
    }

    @Test fun changedProfileRequiresRefreshAndNewConfirmationWithoutAutomaticRetry() {
        val f = Fixture(); f.load(); f.backend.profileChanged = true
        f.startCompression(); f.worker.runOne(); f.ui.runOne()
        assertTrue(f.adapter.state.value.requiresRefresh)
        assertTrue(f.adapter.state.value.notice!!.contains("未发起请求"))
        assertTrue(f.adapter.state.value.notice!!.contains("重新明确确认"))
        f.startCompression()
        assertEquals(0, f.worker.size)
        f.adapter.refresh(); f.worker.runOne(); f.ui.runOne()
        f.adapter.confirmCompression()
        assertEquals(0, f.worker.size)
        f.backend.profileChanged = false
        f.startCompression(); f.worker.runOne(); f.ui.runOne()
        assertEquals(2, f.backend.compressions)
    }

    @Test fun pagesAreReadOnlyAndUseExplicitOffsets() {
        val f = Fixture(); f.load()
        f.adapter.openArchive(); f.worker.runOne(); f.ui.runOne()
        f.adapter.nextArchivePage(); f.worker.runOne(); f.ui.runOne()
        f.adapter.nextArchivePage()
        assertEquals(0, f.worker.size)
        f.adapter.previousArchivePage(); f.worker.runOne(); f.ui.runOne()
        assertEquals(listOf(0, 50, 0), f.backend.archives)
        assertEquals(0, f.backend.compressions)
    }

    @Test fun unknownMutationRequiresReadOnlyRefreshAndNeverAutomaticallyRetries() {
        val f = Fixture(); f.load(); f.backend.failure = true
        f.startCompression(); f.worker.runOne(); f.ui.runOne()
        assertTrue(f.adapter.state.value.requiresRefresh)
        assertTrue(f.adapter.state.value.notice!!.contains("未确认"))
        f.startCompression(); f.adapter.openArchive()
        assertEquals(0, f.worker.size)
        assertEquals(1, f.backend.compressions)
        f.adapter.refresh(); f.worker.runOne(); f.ui.runOne()
        assertFalse(f.adapter.state.value.requiresRefresh)
        assertEquals(1, f.backend.compressions)
    }

    @Test fun confirmedSuccessIsNotRetriedAndSnapshotExplicitlyBecomesStale() {
        val f = Fixture(); f.load(); f.startCompression()
        f.adapter.confirmCompression()
        assertEquals(1, f.worker.size)
        f.worker.runOne(); f.ui.runOne()
        assertEquals(1, f.backend.compressions)
        assertTrue(f.adapter.state.value.requiresRefresh)
        assertEquals("会话已压缩，原始消息保留。", f.adapter.state.value.notice)
        f.startCompression()
        assertEquals(0, f.worker.size)
    }

    @Test fun executorRejectionReleasesExactlyOnceWithoutStartingWork() {
        val f = Fixture(Executor { throw RejectedExecutionException() })
        f.adapter.refresh()
        assertFalse(f.gate.busy)
        assertEquals(1, f.gate.finishes)
        assertEquals(0, f.backend.reads)
        assertNull(f.adapter.state.value.operation)
    }
}
