package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.delegation.ChildRunOwner
import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.domain.delegation.ChildTaskStatus
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.conversation.ChildTaskRecordsScreen
import app.daiyu.android.ui.screens.conversation.ConversationExportScreen
import java.util.concurrent.atomic.AtomicInteger

class ConversationBusinessPagesActivityTest : WiredActivityTestCase() {
    private fun text(id: Int) = activity.getString(id)

    private fun showRecords(load: suspend (String) -> List<ChildTaskRecord>) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChildTaskRecordsScreen("conversation-A", load, onBack = {})
                }
            }
        }
    }

    private fun awaitText(id: Int) {
        val expected = text(id)
        ui.waitUntil(5_000) { ui.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(expected).assertIsDisplayed()
    }

    private fun record(conversationId: String, status: ChildTaskStatus) = ChildTaskRecord(
        owner = ChildRunOwner(conversationId, "parent", "group", 0, "child-$status", "assistant-$status"),
        parentMessageId = "parent-message", parentOwnerId = "owner", index = status.ordinal,
        profileId = "profile-$status", prompt = "prompt-$status", status = status,
        text = if (status == ChildTaskStatus.COMPLETED) "completed-result" else "",
    )

    fun testExportButtonRequiresAvailabilityAndNoPendingOrBusyOperation() {
        var exports = 0
        for ((available, pending, busy) in listOf(
            Triple(false, false, false), Triple(true, true, false),
            Triple(true, false, true), Triple(true, false, false),
        )) {
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        ConversationExportScreen("Frozen conversation", available, pending, busy,
                            onExport = { exports++ }, onBack = {})
                    }
                }
            }
            val button = node("conversation-export-choose")
            node("page:${text(R.string.business_export_title)}").performScrollToNode(hasTestTag("conversation-export-choose"))
            if (available && !pending && !busy) button.assertIsEnabled().performClick()
            else button.assertIsNotEnabled()
        }
        ui.runOnIdle { assertEquals(1, exports) }
    }

    fun testEmptyRecordsDoNotLookLikeReadFailure() {
        val reads = AtomicInteger()
        showRecords { conversationId ->
            check(conversationId == "conversation-A")
            reads.incrementAndGet()
            emptyList()
        }
        awaitText(R.string.business_task_empty)
        ui.onNodeWithText(text(R.string.business_task_load_failed)).assertDoesNotExist()
        node("child-tasks-refresh").assertIsEnabled().performClick()
        ui.waitUntil(5_000) { reads.get() == 2 }
        awaitText(R.string.business_task_empty)
    }

    fun testReadFailureCanRefreshWithoutDispatchingTask() {
        val reads = AtomicInteger()
        showRecords {
            if (reads.incrementAndGet() == 1) error("read unavailable")
            emptyList()
        }
        awaitText(R.string.business_task_load_failed)
        ui.onNodeWithText(text(R.string.business_task_empty)).assertDoesNotExist()
        node("child-tasks-refresh").assertIsEnabled().performClick()
        awaitText(R.string.business_task_empty)
        assertEquals(2, reads.get())
    }

    fun testCrossConversationResultIsNotDisplayed() {
        showRecords { listOf(record("conversation-B", ChildTaskStatus.COMPLETED)) }
        awaitText(R.string.business_task_load_failed)
        ui.onNodeWithText("completed-result").assertDoesNotExist()
        node("child-task:child-COMPLETED").assertDoesNotExist()
    }

    fun testAllRecordStatusesAreReadableAndUnknownDoesNotOfferReplay() {
        val statuses = listOf(
            ChildTaskStatus.QUEUED to R.string.business_task_queued,
            ChildTaskStatus.RUNNING to R.string.business_task_running,
            ChildTaskStatus.COMPLETED to R.string.business_task_completed,
            ChildTaskStatus.NOT_EXECUTED to R.string.business_task_not_executed,
            ChildTaskStatus.UNKNOWN to R.string.business_task_unknown,
        )
        showRecords { statuses.map { (status, _) -> record("conversation-A", status) } }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("child-task:child-QUEUED").fetchSemanticsNodes().isNotEmpty() }
        val page = node("page:${text(R.string.business_task_records)}")
        statuses.forEach { (status, label) ->
            page.performScrollToNode(hasTestTag("child-task:child-$status"))
            ui.onNodeWithText(text(label)).assertIsDisplayed()
        }
        page.performScrollToNode(hasText(text(R.string.business_task_unknown_note)))
        ui.onNodeWithText(text(R.string.business_task_unknown_note)).assertIsDisplayed()
        page.performScrollToNode(hasTestTag("child-task-expand:child-UNKNOWN"))
        node("child-task-expand:child-UNKNOWN").performClick()
        ui.onNodeWithText("重试").assertDoesNotExist()
        ui.onNodeWithText("Retry").assertDoesNotExist()
    }
}
