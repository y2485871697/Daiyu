package app.daiyu.android

import android.os.Looper
import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.ui.model.ConversationPaneUiState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConversationPaneStartupActivityTest : WiredActivityTestCase() {
    private var conversation: ConversationId by kotlin.properties.Delegates.notNull()
    override fun createFixture() = super.createFixture().also {
        val row = it.database.create()
        conversation = row.id
        it.database.rename(row.id, "Prepared before drawer opens")
    }

    @Suppress("UNCHECKED_CAST")
    private fun pane() = MainActivity::class.java.getDeclaredField("paneState\$delegate")
        .apply { isAccessible = true }.get(activity) as MutableState<ConversationPaneUiState>
    private fun refresh() = MainActivity::class.java.getDeclaredMethod("refresh")
        .apply { isAccessible = true }.invoke(activity)
    private fun readerRunning() = MainActivity::class.java.getDeclaredField("paneRefreshRunning")
        .apply { isAccessible = true }.getBoolean(activity)

    fun testColdStartPreparesClosedPaneAndReopenKeepsRowsWhileReadIsBlocked() {
        awaitControl("closed drawer already has initial snapshot") {
            pane().value.conversationsLoaded && pane().value.historyConversations.any { it.id == conversation.value }
                && !readerRunning()
        }
        val before = fixture.database.exportBackup()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val onMain = AtomicBoolean()
        mainThread {
            val read = activity.paneSnapshotReader
            activity.paneSnapshotReader = { active ->
                onMain.set(Looper.myLooper() == Looper.getMainLooper())
                entered.countDown()
                check(gate.await(60, TimeUnit.SECONDS))
                read(active)
            }
        }
        try {
            repeat(2) {
                ui.onNodeWithContentDescription("打开侧边栏").performClick()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                node("drawer-conversation:${conversation.value}").assertIsDisplayed()
                ui.onNodeWithText(activity.getString(R.string.conversation_empty)).assertDoesNotExist()
                assertTrue(pane().value.conversationsLoaded)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                ui.waitForIdle()
            }
            assertFalse(onMain.get())
        } finally { gate.countDown() }
        awaitControl("pane reader drained") { !readerRunning() }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testClosedPaneWarmsChangedTitleAndDoesNotReadAgainForUnchangedRevision() {
        awaitControl("initial projection ready") { pane().value.conversationsLoaded && !readerRunning() }
        val reads = AtomicInteger()
        mainThread {
            val read = activity.paneSnapshotReader
            activity.paneSnapshotReader = { active -> reads.incrementAndGet(); read(active) }
            fixture.database.rename(conversation, "Changed while drawer is closed")
            ChatController::class.java.getDeclaredField("titleRevision").apply { isAccessible = true }
                .setLong(fixture.controller, fixture.controller.titleRevision + 1)
            refresh()
        }
        awaitControl("closed pane title updated") {
            !readerRunning() && pane().value.historyConversations.any {
                it.id == conversation.value && it.title == "Changed while drawer is closed"
            }
        }
        val completedReads = reads.get()
        assertTrue(completedReads > 0)
        mainThread { repeat(3) { refresh() } }
        ui.waitForIdle()
        assertEquals(completedReads, reads.get())
        assertEquals(0, fixture.opens.get())
    }
}
