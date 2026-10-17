package app.daiyu.android

import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.Conversation
import app.daiyu.android.ui.model.ConversationPaneUiState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Delay real database reads, not a fake projection, at the Activity publication boundary. */
@Suppress("DEPRECATION")
class ConversationPaneProjectionActivityTest : WiredActivityTestCase() {
    private fun updatePane() = MainActivity::class.java.getDeclaredMethod("updatePane")
        .apply { isAccessible = true }.invoke(activity)
    @Suppress("UNCHECKED_CAST")
    private fun paneState() = MainActivity::class.java.getDeclaredField("paneState\$delegate")
        .apply { isAccessible = true }.get(activity) as MutableState<ConversationPaneUiState>
    private fun readerRunning() = MainActivity::class.java.getDeclaredField("paneRefreshRunning")
        .apply { isAccessible = true }.getBoolean(activity)

    fun testStreamingRefreshPublishesBeforeTheActiveRunCompletes() {
        val session = fixture.session("stream complete", holdSeconds = 120)
        setDraft("streaming pane projection")
        node("chat-send").performClick()
        awaitTextSendResult(activity)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(fixture.controller.active)
        awaitControl("initial pane reader idle") { !readerRunning() }
        val entered = List(2) { CountDownLatch(1) }
        val release = List(2) { CountDownLatch(1) }
        val reads = AtomicInteger()
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val readOnMain = AtomicBoolean()
        val producing = AtomicBoolean(true)
        val produced = CountDownLatch(2)
        val producerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        lateinit var retained: Conversation
        mainThread {
            retained = fixture.database.create()
            fixture.database.rename(retained.id, "visible while streaming")
            val realReader = activity.paneSnapshotReader
            activity.paneSnapshotReader = { active ->
                if (Looper.myLooper() == Looper.getMainLooper()) readOnMain.set(true)
                val concurrent = inFlight.incrementAndGet()
                peak.updateAndGet { maxOf(it, concurrent) }
                val index = reads.getAndIncrement()
                try {
                    val snapshot = realReader(active)
                    if (index in entered.indices) {
                        entered[index].countDown()
                        check(release[index].await(60, TimeUnit.SECONDS))
                    }
                    snapshot
                } finally { inFlight.decrementAndGet() }
            }
        }
        val producer = Thread({
            try {
                var delta = 0
                while (producing.get()) {
                    checkNotNull(session.sink.get())(ModelEvent.TextDelta("body", " stream-${delta++}"))
                    produced.countDown()
                    Thread.sleep(30)
                }
            } catch (failure: Throwable) { producerFailure.set(failure) }
        }, "pane-streaming-fixture")
        try {
            ui.onNodeWithContentDescription("打开侧边栏").performClick()
            assertTrue("first real snapshot entered", entered[0].await(5, TimeUnit.SECONDS))
            producer.start()
            assertTrue("real streaming deltas emitted", produced.await(5, TimeUnit.SECONDS))
            release[0].countDown()
            assertTrue("coalesced follow-up reader entered", entered[1].await(5, TimeUnit.SECONDS))
            awaitControl("snapshot published while stream and follow-up read remain active") {
                fixture.controller.active?.id == owner.id &&
                    paneState().value.historyConversations.any {
                        it.id == retained.id.value && it.title == "visible while streaming"
                    }
            }
            assertEquals(1L, session.release.count)
            assertTrue(producer.isAlive)
            assertNull(producerFailure.get())
            assertEquals(1, peak.get())
            assertFalse(readOnMain.get())
            assertEquals(1, fixture.opens.get())
        } finally {
            producing.set(false)
            producer.join(5_000)
            release.forEach { it.countDown() }
            session.release.countDown()
        }
        assertFalse("streaming producer drained", producer.isAlive)
        producerFailure.get()?.let { throw it }
    }

    fun testSingleFlightDropsStaleSnapshotUsesLatestSelectionAndFencesDestroyedActivity() {
        val session = fixture.session("completed behind the pane", holdSeconds = 120)
        setDraft("pane projection request")
        node("chat-send").performClick()
        awaitTextSendResult(activity)
        val owner = checkNotNull(fixture.controller.active)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        awaitControl("previous pane reader idle") { !readerRunning() }
        val entered = List(3) { CountDownLatch(1) }
        val release = List(3) { CountDownLatch(1) }
        val reads = AtomicInteger()
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val readOnMain = AtomicBoolean()
        lateinit var other: Conversation
        lateinit var before: ConversationPaneUiState
        mainThread {
            other = fixture.database.create()
            val realReader = activity.paneSnapshotReader
            activity.paneSnapshotReader = { active ->
                if (Looper.myLooper() == Looper.getMainLooper()) readOnMain.set(true)
                val index = reads.getAndIncrement()
                check(index in entered.indices) { "unexpected extra pane read" }
                val concurrent = inFlight.incrementAndGet()
                peak.updateAndGet { maxOf(it, concurrent) }
                try {
                    val realSnapshot = realReader(active)
                    entered[index].countDown()
                    check(release[index].await(60, TimeUnit.SECONDS))
                    realSnapshot
                } finally { inFlight.decrementAndGet() }
            }
            before = paneState().value
        }
        try {
            ui.onNodeWithContentDescription("打开侧边栏").performClick()
            assertTrue("first real read entered", entered[0].await(5, TimeUnit.SECONDS))
            mainThread {
                fixture.database.rename(owner.conversationId, "renamed while reader blocked")
                fixture.database.organize(other.id, "folder-B", false)
                activity.select(other)
                paneState().value = paneState().value.copy(selectedFolderId = "folder-B")
                repeat(100) { updatePane() }
            }
            session.release.countDown()
            awaitControl("real run completed") { fixture.controller.active == null }
            ui.waitForIdle()
            mainThread { updatePane() }
            assertEquals(1, reads.get())
            assertEquals(1, peak.get())
            release[0].countDown()
            assertTrue("one coalesced replacement", entered[1].await(5, TimeUnit.SECONDS))
            mainThread { assertEquals(before.historyConversations, paneState().value.historyConversations) }
            assertEquals(2, reads.get())
            release[1].countDown()
            awaitControl("fresh retained projection") {
                val pane = paneState().value
                val completed = pane.historyConversations.singleOrNull { it.id == owner.conversationId.value }
                !readerRunning() && pane.selectedConversationId == other.id.value &&
                    pane.selectedFolderId == "folder-B" &&
                    pane.conversations.map { it.id } == listOf(other.id.value) &&
                    pane.folders.any { it.id == "folder-B" } &&
                    completed?.title == "renamed while reader blocked" &&
                    completed.hasCompletionMarker && !completed.isActiveRun
            }
            assertEquals(2, reads.get())
            assertFalse(readOnMain.get())
            assertEquals(1, fixture.opens.get())
            lateinit var beforeClose: ConversationPaneUiState
            mainThread { beforeClose = paneState().value; updatePane() }
            assertTrue("closing read entered", entered[2].await(5, TimeUnit.SECONDS))
            mainThread { activity.finish() }
            awaitControl("Activity destroyed") { activity.isDestroyed }
            release[2].countDown()
            awaitControl("late reader drained without restart") { inFlight.get() == 0 && !readerRunning() }
            mainThread { assertEquals(beforeClose, paneState().value) }
            assertEquals(3, reads.get())
            assertEquals(1, peak.get())
        } finally {
            release.forEach { it.countDown() }
            session.release.countDown()
        }
    }
}
