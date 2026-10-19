package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class ToolCardActionTest {
    private val step = WorkStepUi("call", "工作区", "", owner = WorkStepOwnerUi("chat", "run", "message"),
        toolName = "workspace-inspect", category = "workspace")

    @Test fun workspaceMutationsAndDuplicateNavigationAreNotUserActions() {
        val names = ToolCardAction.entries.map { it.name }.toSet()
        assertTrue(names.intersect(setOf("CHECK_WORKSPACE", "MERGE_WORKSPACE", "DISCARD_WORKSPACE",
            "OPEN_WORKSPACE", "VIEW_DIFF", "OPEN_TERMINAL", "VIEW_TASKS", "OPEN_BROWSER", "VIEW_DOWNLOADS")).isEmpty())
        assertTrue(names.containsAll(setOf("PAUSE_TASK", "RESUME_TASK", "STOP_TASK", "OPEN_IMAGE", "SAVE_MEDIA")))
    }

    @Test fun fullInvocationIdentityIsRequired() {
        val request = step.actionRequest(ToolCardAction.SAVE_MEDIA, "asset")
        assertTrue(request.matches(step))
        val owner = requireNotNull(step.owner)
        listOf(step.copy(id = "other"), step.copy(toolName = "other"), step.copy(owner = null),
            step.copy(owner = owner.copy(conversationId = "other")),
            step.copy(owner = owner.copy(runId = "other")),
            step.copy(owner = owner.copy(messageId = "other"))).forEach { assertFalse(request.matches(it)) }
    }

    @Test fun bothLayoutsResolveCurrentRecordRatherThanCapturedText() {
        val request = step.actionRequest(ToolCardAction.SAVE_MEDIA)
        val current = step.copy(detail = "current")
        val mixed = ChatTurnUi.Assistant("message", content = listOf(MessageContentUi.Tool(current)))
        val grouped = ChatTurnUi.Assistant("message", workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(current))), ""))
        listOf(mixed, grouped).forEach { assertEquals(current, resolveToolCardAction(listOf(it), request)) }
        assertNull(resolveToolCardAction(emptyList(), request))
        assertNull(resolveToolCardAction(listOf(mixed), request.copy(owner = null)))
    }
    @Test fun mediaTimeUsesDurationRatherThanInventingPlayback() {
        assertEquals("0:00", mediaTime(-1))
        assertEquals("0:12", mediaTime(12000))
        assertEquals("2:05", mediaTime(125999))
    }

}
