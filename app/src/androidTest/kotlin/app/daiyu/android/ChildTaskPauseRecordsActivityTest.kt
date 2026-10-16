package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.conversation.ChildTaskRecordsScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Leaf UI contracts only: fake observations never dispatch or recover a historical task. */
class ChildTaskPauseRecordsActivityTest : WiredActivityTestCase() {
    private val record = ChildTaskRecord(ChildRunOwner("A", "parent", "group", 1, "child", "message"),
        "parent-message", "owner", 0, "review", "prompt", ChildTaskStatus.RUNNING)
    private fun live(state: ChildTaskPauseState = ChildTaskPauseState.ACTIVE) =
        RetainedChildTask(record, false, false, false, state)
    private fun page() = node("page:${activity.getString(R.string.business_task_records)}")
    private fun button(action: String): SemanticsNodeInteraction {
        val tag = "child-task-$action:child"
        page().performScrollToNode(hasTestTag(tag))
        return node(tag)
    }
    private fun awaitReads(reads: AtomicInteger, count: Int) {
        ui.waitUntil(5_000) { reads.get() >= count }
        ui.waitUntil(5_000) {
            node("child-tasks-refresh").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
        }
        ui.waitForIdle()
    }
    private fun awaitText(id: Int) {
        val text = activity.getString(id)
        page().performScrollToNode(hasText(text))
        ui.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }

    fun testPendingCanBeWithdrawnAndPausedContinuesWithOriginalRecord() {
        val reads = AtomicInteger()
        var snapshot = live()
        val pauses = AtomicInteger()
        val resumes = AtomicInteger()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChildTaskRecordsScreen("A", load = { listOf(record) }, onBack = {},
                        loadLive = { reads.incrementAndGet(); listOf(snapshot) },
                        onPause = {
                            assertSame(record, it); pauses.incrementAndGet()
                            snapshot = live(ChildTaskPauseState.PENDING)
                            ChildTaskControlReceipt.PENDING
                        }, onResume = {
                            assertSame(record, it); resumes.incrementAndGet()
                            snapshot = live()
                            ChildTaskControlReceipt.RESUMED
                        }, onStop = {
                            assertSame(record, it)
                            snapshot = live().copy(stopRequested = true)
                            ChildTaskStop.REQUESTED
                        })
                }
            }
        }
        awaitReads(reads, 1)
        button("resume").assertIsNotEnabled()
        button("pause").assertIsEnabled().performClick()
        awaitReads(reads, 2)
        awaitText(R.string.business_task_pause_pending)
        button("pause").assertIsNotEnabled()
        button("resume").assertTextEquals(activity.getString(R.string.business_task_retract_pause))
            .assertIsEnabled().performClick()
        awaitReads(reads, 3)
        awaitText(R.string.business_task_pause_retracted)
        assertEquals(1, pauses.get()); assertEquals(1, resumes.get())

        // A boundary acknowledgement arrives later and is read only when requested, not polled.
        mainThread { snapshot = live(ChildTaskPauseState.PAUSED) }
        node("child-tasks-refresh").performClick()
        awaitReads(reads, 4)
        awaitText(R.string.business_task_paused)
        button("resume").assertTextEquals(activity.getString(R.string.business_task_resume))
            .assertIsEnabled().performClick()
        awaitReads(reads, 5)
        awaitText(R.string.business_task_resumed)
        assertEquals(2, resumes.get())
        assertEquals(ChildTaskStatus.RUNNING, record.status)

        button("stop").performClick()
        awaitReads(reads, 6)
        awaitText(R.string.business_task_stop_requested)
        node("child-task-resume:child").assertDoesNotExist()
        node("child-task-pause:child").assertDoesNotExist()
        ui.onAllNodesWithText(activity.getString(R.string.business_task_paused)).assertCountEquals(0)
        assertEquals(6, reads.get())
    }

    fun testStaleReceiptDoesNotClaimContinuedAndRemovesSettledControls() {
        val reads = AtomicInteger()
        var snapshot = live(ChildTaskPauseState.PAUSED)
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChildTaskRecordsScreen("A", load = { listOf(record) }, onBack = {},
                        loadLive = { reads.incrementAndGet(); listOf(snapshot) },
                        onPause = { error("not active") }, onResume = {
                            assertSame(record, it)
                            snapshot = snapshot.copy(settled = true)
                            ChildTaskControlReceipt.STALE
                        })
                }
            }
        }
        awaitReads(reads, 1)
        button("resume").performClick()
        awaitReads(reads, 2)
        awaitText(R.string.business_task_control_stale)
        node("child-task-resume:child").assertDoesNotExist()
        ui.onAllNodesWithText(activity.getString(R.string.business_task_resumed)).assertCountEquals(0)
    }

    fun testUnknownHistoryNeverOffersContinue() {
        val reads = AtomicInteger()
        val history = record.copy(status = ChildTaskStatus.UNKNOWN)
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChildTaskRecordsScreen("A", load = { listOf(history) }, onBack = {},
                        loadLive = { reads.incrementAndGet(); listOf(live(ChildTaskPauseState.PAUSED)) },
                        onPause = { error("history") }, onResume = { error("history") })
                }
            }
        }
        awaitReads(reads, 1)
        page().performScrollToNode(hasTestTag("child-task:child"))
        node("child-task-resume:child").assertDoesNotExist()
        node("child-task-pause:child").assertDoesNotExist()
        ui.onAllNodesWithText(activity.getString(R.string.business_task_paused)).assertCountEquals(0)
    }

    fun testForeignConversationSnapshotFailsClosedButKeepsHistoryReadable() {
        val reads = AtomicInteger()
        val foreign = live(ChildTaskPauseState.PAUSED).copy(record =
            record.copy(owner = record.owner.copy(conversationId = "B")))
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChildTaskRecordsScreen("A", load = { listOf(record) }, onBack = {},
                        loadLive = { reads.incrementAndGet(); listOf(foreign) },
                        onPause = { error("foreign") }, onResume = { error("foreign") })
                }
            }
        }
        awaitReads(reads, 1)
        awaitText(R.string.business_task_live_load_failed)
        page().performScrollToNode(hasTestTag("child-task:child"))
        ui.onNodeWithText("review").assertExists()
        node("child-task-resume:child").assertDoesNotExist()
        node("child-task-pause:child").assertDoesNotExist()
    }

    fun testLateLiveLoadFromOldConversationCannotReplaceNewPage() {
        val conversation = mutableStateOf("A")
        val entered = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val newRecord = record.copy(owner = record.owner.copy(conversationId = "B"), profileId = "new-page")
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChildTaskRecordsScreen(conversation.value,
                        load = { if (it == "A") listOf(record) else listOf(newRecord) }, onBack = {},
                        loadLive = { id ->
                            if (id == "A") withContext(NonCancellable) {
                                entered.incrementAndGet()
                                release.await()
                                listOf(live(ChildTaskPauseState.PAUSED))
                            } else emptyList()
                        }, onPause = { error("old page") }, onResume = { error("old page") })
                }
            }
        }
        try {
            ui.waitUntil(5_000) { entered.get() == 1 }
            mainThread { conversation.value = "B" }
            ui.waitUntil(5_000) { ui.onAllNodesWithText("new-page").fetchSemanticsNodes().isNotEmpty() }
            mainThread { release.complete(Unit) }
            ui.waitForIdle()
            ui.onNodeWithText("new-page").assertExists()
            ui.onAllNodesWithText("review").assertCountEquals(0)
            node("child-task-resume:child").assertDoesNotExist()
        } finally { release.complete(Unit) }
    }
}
