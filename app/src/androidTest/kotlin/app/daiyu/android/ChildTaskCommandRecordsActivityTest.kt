package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.conversation.ChildTaskCommandDialogAction
import app.daiyu.android.ui.screens.conversation.ChildTaskRecordsScreen
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** UI consent tests; durable dispatch and real cleanup are separately covered by Controller/SQLite tests. */
class ChildTaskCommandRecordsActivityTest : WiredActivityTestCase() {
    private val source = ChildTaskRecord(ChildRunOwner("A", "parent", "group", 1, "child", "message"),
        "parent-message", "owner", 0, "review", "original prompt", ChildTaskStatus.UNKNOWN,
        detail = "unconfirmed", revision = 7)

    fun testUnknownRequiresVisibleRiskAndOneExplicitConfirmation() {
        val calls = AtomicInteger()
        val received = AtomicReference<ChildTaskCommand>()
        val release = CompletableDeferred<Unit>()
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChildTaskRecordsScreen("A", load = { listOf(source) }, onBack = {}, onCommand = {
                        received.set(it); calls.incrementAndGet(); release.await()
                        ChildTaskCommandReceipt.Rejected("child_source_stale")
                    })
                }
            }
        }
        ui.waitUntil(5_000) {
            node("child-tasks-refresh").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
        }
        node("page:${activity.getString(R.string.business_task_records)}")
            .performScrollToNode(hasTestTag("child-task-redispatch:child"))
        node("child-task-redispatch:child").performClick()
        node("child-command-execution-warning").assertExists()
        assertEquals(0, calls.get())
        node("child-command-confirm").assertTextEquals("确认可能已执行，仍新派一次").performClick()
        ui.waitUntil(5_000) { calls.get() == 1 }
        node("child-command-confirm").assertIsNotEnabled()
        assertEquals(source.owner, received.get().source.owner)
        assertEquals(7L, received.get().source.revision)
        assertTrue(received.get().acknowledgePossibleExecution)
        assertEquals(ChildTaskCommandKind.REDISPATCH, received.get().kind)
        release.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("child-command-confirm").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, calls.get()); assertEquals(ChildTaskStatus.UNKNOWN, source.status)
    }

    fun testCancelledCallbacksCannotAuthorizeOrDismissReopenedSameTask() {
        assertCancelledCallbacksCannotAffectReopenedDialog(source)
    }

    fun testCancelledUnknownCallbacksCannotAuthorizeOrDismissDifferentTask() {
        assertCancelledCallbacksCannotAffectReopenedDialog(source.copy(
            owner = source.owner.copy(childRunId = "other-child", assistantMessageId = "other-message"),
            prompt = "different original prompt", status = ChildTaskStatus.NOT_EXECUTED, revision = 11,
        ))
    }

    private fun commandAction(tag: String): () -> Unit = node(tag).assertIsEnabled()
        .fetchSemanticsNode().config[ChildTaskCommandDialogAction]

    private fun openRedispatch(record: ChildTaskRecord) {
        val tag = "child-task-redispatch:${record.owner.childRunId}"
        node("page:${activity.getString(R.string.business_task_records)}").performScrollToNode(hasTestTag(tag))
        node(tag).performClick()
    }

    private fun assertCancelledCallbacksCannotAffectReopenedDialog(target: ChildTaskRecord) {
        val calls = AtomicInteger()
        val received = AtomicReference<ChildTaskCommand>()
        val release = CompletableDeferred<Unit>()
        val records = if (target.owner == source.owner) listOf(source) else listOf(source, target)
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChildTaskRecordsScreen("A", load = { records }, onBack = {}, onCommand = {
                        received.set(it); calls.incrementAndGet(); release.await()
                        ChildTaskCommandReceipt.Rejected("test_no_dispatch")
                    })
                }
            }
        }
        ui.waitUntil(5_000) {
            node("child-tasks-refresh").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
        }
        openRedispatch(source)
        node("child-command-execution-warning").assertExists()
        node("child-command-instruction").performTextReplacement("cancelled instruction")
        // Keep the actual old closures: ClickableNode semantics wrappers can rebind or detach.
        val oldConfirm = commandAction("child-command-confirm")
        val oldDismiss = commandAction("child-command-cancel")
        mainThread {
            oldDismiss()
            oldConfirm() // Cancellation must revoke consent even before recomposition.
        }
        node("child-command-confirm").assertDoesNotExist()
        assertEquals(0, calls.get())

        openRedispatch(target)
        node("child-command-instruction").assertEditableText(target.prompt)
        node("child-command-instruction").performTextReplacement("current instruction")
        mainThread {
            oldConfirm()
            oldDismiss()
            oldConfirm()
        }
        ui.waitForIdle()
        assertEquals(0, calls.get())
        assertNull(received.get())
        node("child-command-confirm").assertExists().assertIsEnabled()
        node("child-command-instruction").assertEditableText("current instruction")
        val risk = target.status == ChildTaskStatus.UNKNOWN
        if (risk) node("child-command-execution-warning").assertExists()
        else node("child-command-execution-warning").assertDoesNotExist()

        val currentConfirm = commandAction("child-command-confirm")
        mainThread {
            currentConfirm()
            currentConfirm() // One current opening authorizes at most one command.
            oldDismiss()
            oldConfirm()
        }
        ui.waitUntil(5_000) { calls.get() == 1 }
        node("child-command-confirm").assertExists().assertIsNotEnabled()
        assertEquals(target, received.get().source)
        assertEquals(ChildTaskCommandKind.REDISPATCH, received.get().kind)
        assertEquals("current instruction", received.get().instruction)
        assertEquals(risk, received.get().acknowledgePossibleExecution)
        release.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("child-command-confirm").fetchSemanticsNodes().isEmpty() }
        mainThread { oldConfirm(); oldDismiss(); currentConfirm() }
        assertEquals(1, calls.get())
    }

    fun testNotExecutedDispatchIsExplicitWithoutUnknownAcknowledgement() {
        val calls = AtomicInteger()
        val received = AtomicReference<ChildTaskCommand>()
        val record = source.copy(status = ChildTaskStatus.NOT_EXECUTED)
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChildTaskRecordsScreen("A", load = { listOf(record) }, onBack = {}, onCommand = {
                        received.set(it); calls.incrementAndGet(); ChildTaskCommandReceipt.Rejected("test_no_dispatch")
                    })
                }
            }
        }
        ui.waitUntil(5_000) {
            node("child-tasks-refresh").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
        }
        node("page:${activity.getString(R.string.business_task_records)}")
            .performScrollToNode(hasTestTag("child-task-redispatch:child"))
        node("child-task-redispatch:child").performClick()
        node("child-command-execution-warning").assertDoesNotExist()
        assertEquals(0, calls.get())
        node("child-command-confirm").assertTextEquals("确认派发新请求").performClick()
        ui.waitUntil(5_000) { calls.get() == 1 }
        assertFalse(received.get().acknowledgePossibleExecution)
        assertEquals(record, received.get().source)
    }
}
