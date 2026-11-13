package app.daiyu.android

import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.*
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
    private fun seedToolHistory(conversation: Conversation): List<Pair<Long, Message>> {
        val run = RunId("switch-tool-run")
        val user = Message(MessageId("switch-tool-user"),
            conversation.id, run, Message.USER_ROLE,
            listOf(MessageBlock.Text("q", "old question")), RunStatus.COMPLETED)
        val answer = user.copy(id = MessageId("switch-tool-answer"),
            role = Message.ASSISTANT_ROLE, status = RunStatus.RUNNING,
            blocks = List(4) { index -> MessageBlock.Tool(
                ToolInvocation(ToolCallId("tool-$index"), "unknown_tool", "{}"),
                "{\"ok\":true,\"data\":\"" + "x".repeat(70_000 + index) + "\"}") } +
                MessageBlock.Text("a", "prepared history body"))
        fixture.database.send(user, answer)
        fixture.database.save(answer.copy(status = RunStatus.COMPLETED))
        return fixture.database.messages(conversation.id)
    }

    fun testPreparedDisplayDoesNotReparseToolsOrHydrateAuthorityBehindDrawer() {
        val (first, second) = prepareConversations()
        val original = seedToolHistory(second)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hydrationEntered = CountDownLatch(1)
        val hydrationRelease = CountDownLatch(1)
        val builds = java.util.concurrent.atomic.AtomicInteger()
        val hydrationReads = java.util.concurrent.atomic.AtomicInteger()
        val onMain = AtomicBoolean()
        val preparer = activity.panePresentationPreparer
        val readerField = MainActivity::class.java.getDeclaredField("loadedWindowReader").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val reader = readerField.get(activity) as (ConversationId, Long, Int) ->
            List<Pair<Long, Message>>
        val displayField = MainActivity::class.java.getDeclaredField("displayWindow\$delegate").apply { isAccessible = true }
        mainThread {
            activity.panePresentationPreparer = { id, snapshot, checkActive ->
                if (Looper.myLooper() == Looper.getMainLooper()) onMain.set(true)
                val cache = ToolPresentationCache({ name, args, result ->
                    if (Looper.myLooper() == Looper.getMainLooper()) onMain.set(true)
                    builds.incrementAndGet()
                    ToolCardPresentations.present(name, args, result)
                }, maxEntries = 1, maxSourceChars = 64, maxLargeEntries = 1)
                val prepared = prepareHistoryPresentations(
                    id, snapshot.messages.map { it.second }, snapshot.display, cache, checkActive)
                entered.countDown()
                check(release.await(60, TimeUnit.SECONDS))
                prepared
            }
            val blocked: (ConversationId, Long, Int) -> List<Pair<Long, Message>> = { id, before, count ->
                if (id == second.id) {
                    hydrationReads.incrementAndGet()
                    if (Looper.myLooper() == Looper.getMainLooper()) onMain.set(true)
                    hydrationEntered.countDown()
                    check(hydrationRelease.await(60, TimeUnit.SECONDS))
                }
                reader(id, before, count)
            }
            readerField.set(activity, blocked)
        }
        try {
            click(second)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            mainThread {
                assertEquals(first.id, selectedConversation()?.id)
                assertEquals(second.id.value, paneState().selectedConversationId)
            }
            assertEquals(4, builds.get())
            release.countDown()
            assertSelected(second, "draft-1")
            mainThread {
                assertNotNull((displayField.get(activity) as MutableState<*>).value)
                repeat(3) { MainActivity::class.java.getDeclaredMethod("refresh").apply { isAccessible = true }.invoke(activity) }
                assertEquals(0, hydrationReads.get())
                assertEquals(4, builds.get())
                MainActivity::class.java.getDeclaredMethod("dismissSidebar").apply { isAccessible = true }.invoke(activity)
            }
            assertTrue(hydrationEntered.await(10, TimeUnit.SECONDS))
            mainThread { assertEquals(second.id, selectedConversation()?.id) }
            assertFalse(onMain.get())
            hydrationRelease.countDown()
            awaitControl("same-owner authoritative replacement") { (displayField.get(activity) as MutableState<*>).value == null }
            assertEquals(original, fixture.database.messages(second.id))
            assertEquals(0, fixture.opens.get())
        } finally {
            release.countDown(); hydrationRelease.countDown()
            mainThread { activity.panePresentationPreparer = preparer; readerField.set(activity, reader) }
        }
    }

    fun testCancelledPresentationPreparationCannotInstallItsCacheIntoLastSelection() {
        val (_, second, third) = prepareConversations()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val preparer = activity.panePresentationPreparer
        val acceptedCache = java.util.concurrent.atomic.AtomicReference<ToolPresentationCache>()
        val cacheField = MainActivity::class.java.getDeclaredField("toolPresentations").apply { isAccessible = true }
        mainThread {
            activity.panePresentationPreparer = { id, snapshot, checkActive ->
                val prepared = preparer(id, snapshot, checkActive)
                if (id == third.id.value) acceptedCache.set(prepared)
                if (id == second.id.value) {
                    entered.countDown()
                    check(release.await(60, TimeUnit.SECONDS))
                }
                prepared
            }
        }
        try {
            click(second)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            lateinit var old: Job
            mainThread { old = selectionJob() }
            click(third)
            assertSelected(third, "draft-2")
            release.countDown()
            awaitControl("cancelled preparation has returned") { old.isCompleted }
            assertSelected(third, "draft-2")
            mainThread { assertSame(acceptedCache.get(), cacheField.get(activity)) }
        } finally {
            release.countDown()
            mainThread { activity.panePresentationPreparer = preparer }
        }
    }

    fun testOptionalPresentationPreparationFailureStillPublishesIntactHistory() {
        val (_, second) = prepareConversations()
        val original = seedToolHistory(second)
        val preparer = activity.panePresentationPreparer
        mainThread { activity.panePresentationPreparer = { _, _, _ -> error("optional preparation failure") } }
        try {
            click(second)
            assertSelected(second, "draft-1")
            assertEquals(original, fixture.database.messages(second.id))
            val stateField = MainActivity::class.java.getDeclaredField("chatState\$delegate").apply { isAccessible = true }
            mainThread {
                val state = (stateField.get(activity) as MutableState<*>).value as
                    ChatScreenState
                assertEquals(second.id.value, state.conversationKey)
                assertTrue(state.turns.isNotEmpty())
            }
        } finally { mainThread { activity.panePresentationPreparer = preparer } }
    }

}
