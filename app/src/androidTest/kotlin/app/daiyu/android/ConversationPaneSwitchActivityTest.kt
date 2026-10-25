package app.daiyu.android

import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import app.daiyu.android.domain.Conversation
import app.daiyu.android.ui.model.ConversationPaneUiState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job

class ConversationPaneSwitchActivityTest : WiredActivityTestCase() {
    @Suppress("UNCHECKED_CAST")
    private fun paneState() = (MainActivity::class.java.getDeclaredField("paneState\$delegate")
        .apply { isAccessible = true }.get(activity) as MutableState<ConversationPaneUiState>).value

    private fun selectedConversation() = MainActivity::class.java.getDeclaredField("selected")
        .apply { isAccessible = true }.get(activity) as Conversation?

    private fun selectionJob() = MainActivity::class.java.getDeclaredField("paneSelectionJob")
        .apply { isAccessible = true }.get(activity) as Job

    private fun hostValue(name: String) = MainActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    private fun updatePane() = MainActivity::class.java.getDeclaredMethod("updatePane")
        .apply { isAccessible = true }.invoke(activity)

    private fun readerRunning() = MainActivity::class.java.getDeclaredField("paneRefreshRunning")
        .apply { isAccessible = true }.getBoolean(activity)

    private fun prepareConversations(): List<Conversation> {
        lateinit var conversations: List<Conversation>
        mainThread {
            conversations = List(3) { index ->
                fixture.database.create().also {
                    fixture.database.rename(it.id, "switch-$index")
                    fixture.database.draft(it.id, "draft-$index")
                }
            }
            activity.select(conversations.first())
        }
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        conversations.forEach { awaitUniqueTag("drawer-conversation:${it.id.value}") }
        awaitControl("initial pane read drained") { !readerRunning() }
        return conversations
    }

    private fun click(conversation: Conversation) =
        node("drawer-conversation:${conversation.id.value}").assertIsDisplayed().performClick()

    private fun assertSelected(conversation: Conversation, draft: String) {
        awaitControl("last selected owner and row published") {
            selectedConversation()?.id == conversation.id && paneState().selectedConversationId == conversation.id.value
        }
        node("drawer-conversation:${conversation.id.value}").assertIsSelected()
        mainThread {
            assertEquals(true, hostValue("getSidebarVisible"))
            assertEquals("chat", hostValue("getRetainedPage"))
            assertEquals(draft, hostValue("getDraft"))
            assertEquals(conversation.id.value, fixture.controller.preferences.getString("selected", null))
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testSelectionFeedbackPrecedesSlowHistoryAndPaneRefresh() {
        val (first, second) = prepareConversations()
        val paneEntered = CountDownLatch(1)
        val paneRelease = CountDownLatch(1)
        val historyEntered = CountDownLatch(1)
        val historyRelease = CountDownLatch(1)
        val readOnMain = AtomicBoolean()
        mainThread {
            val realPaneReader = activity.paneSnapshotReader
            activity.paneSnapshotReader = { active ->
                val snapshot = realPaneReader(active)
                paneEntered.countDown()
                check(paneRelease.await(60, TimeUnit.SECONDS))
                snapshot
            }
            val realReader = activity.paneConversationReader
            activity.paneConversationReader = { id ->
                if (Looper.myLooper() == Looper.getMainLooper()) readOnMain.set(true)
                val snapshot = realReader(id)
                historyEntered.countDown()
                check(historyRelease.await(60, TimeUnit.SECONDS))
                snapshot
            }
            updatePane()
        }
        try {
            assertTrue(paneEntered.await(5, TimeUnit.SECONDS))
            click(second)
            assertTrue(historyEntered.await(5, TimeUnit.SECONDS))
            ui.mainClock.advanceTimeByFrame()
            node("drawer-conversation:${second.id.value}").assertIsSelected()
            node("drawer-conversation:${first.id.value}").assertIsNotSelected()
            mainThread {
                assertEquals(first.id, selectedConversation()?.id)
                assertEquals("draft-0", hostValue("getDraft"))
                assertEquals(first.id.value, fixture.controller.preferences.getString("selected", null))
            }
            paneRelease.countDown()
            awaitControl("pane refresh finishes while history remains blocked") { !readerRunning() }
            node("drawer-conversation:${second.id.value}").assertIsSelected()
            mainThread { assertEquals(first.id, selectedConversation()?.id) }
            assertFalse(readOnMain.get())
            historyRelease.countDown()
            assertSelected(second, "draft-1")
        } finally {
            paneRelease.countDown()
            historyRelease.countDown()
        }
    }

    fun testLastClickWinsWhenCancelledReadFailsLate() {
        val (_, second, third) = prepareConversations()
        val oldEntered = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val oldDrained = CountDownLatch(1)
        val latestEntered = CountDownLatch(1)
        val latestRelease = CountDownLatch(1)
        mainThread {
            val realReader = activity.paneConversationReader
            activity.paneConversationReader = { id ->
                val snapshot = realReader(id)
                if (id == second.id.value) {
                    try {
                        oldEntered.countDown()
                        check(oldRelease.await(60, TimeUnit.SECONDS))
                        error("cancelled old history read failed late")
                    } finally { oldDrained.countDown() }
                } else {
                    latestEntered.countDown()
                    check(latestRelease.await(60, TimeUnit.SECONDS))
                    snapshot
                }
            }
        }
        try {
            click(second)
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
            lateinit var oldJob: Job
            mainThread { oldJob = selectionJob() }
            click(third)
            assertTrue(latestEntered.await(5, TimeUnit.SECONDS))
            ui.mainClock.advanceTimeByFrame()
            node("drawer-conversation:${third.id.value}").assertIsSelected()
            node("drawer-conversation:${second.id.value}").assertIsNotSelected()
            latestRelease.countDown()
            assertSelected(third, "draft-2")
            oldRelease.countDown()
            assertTrue(oldDrained.await(5, TimeUnit.SECONDS))
            awaitControl("cancelled selection coroutine including its cleanup completed") { oldJob.isCompleted }
            ui.waitForIdle()
            assertSelected(third, "draft-2")
        } finally {
            oldRelease.countDown()
            latestRelease.countDown()
        }
    }

    fun testFailedHistoryReadRestoresOriginalSelectionAndDraft() {
        val (first, second) = prepareConversations()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        mainThread {
            val realReader = activity.paneConversationReader
            activity.paneConversationReader = { id ->
                realReader(id)
                entered.countDown()
                check(release.await(60, TimeUnit.SECONDS))
                error("history read failed")
            }
        }
        try {
            click(second)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            node("drawer-conversation:${second.id.value}").assertIsSelected()
            release.countDown()
            assertSelected(first, "draft-0")
            node("drawer-conversation:${second.id.value}").assertIsNotSelected()
        } finally { release.countDown() }
    }

    fun testDestroyedActivityRejectsLateConversationRead() {
        val (first, second) = prepareConversations()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        mainThread {
            val realReader = activity.paneConversationReader
            activity.paneConversationReader = { id ->
                try {
                    val snapshot = realReader(id)
                    entered.countDown()
                    check(release.await(60, TimeUnit.SECONDS))
                    snapshot
                } finally { drained.countDown() }
            }
        }
        try {
            click(second)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            lateinit var closingJob: Job
            mainThread { closingJob = selectionJob(); activity.finish() }
            awaitControl("Activity destroyed") { activity.isDestroyed }
            lateinit var closedPane: ConversationPaneUiState
            mainThread { closedPane = paneState() }
            release.countDown()
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            awaitControl("destroyed selection coroutine including its cleanup completed") { closingJob.isCompleted }
            ui.waitForIdle()
            mainThread {
                assertEquals(first.id, selectedConversation()?.id)
                assertEquals(first.id.value, fixture.controller.preferences.getString("selected", null))
                assertEquals(closedPane, paneState())
            }
            assertEquals(0, fixture.opens.get())
        } finally { release.countDown() }
    }
}
