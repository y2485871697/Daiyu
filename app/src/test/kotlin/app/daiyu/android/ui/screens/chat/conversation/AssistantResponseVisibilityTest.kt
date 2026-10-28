package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.MessageImageOwner
import app.daiyu.android.domain.MessageImageReference
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.domain.RunStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantResponseVisibilityTest {
    private val empty = ChatTurnUi.Assistant("reply", status = RunStatus.RUNNING, isStreaming = true)
    private val step = WorkStepUi("tool", "fixture tool", "", WorkStepState.PENDING)

    @Test fun emptyRepliesDoNotShowIdentityInAnyRunState() {
        for (status in RunStatus.entries) assertFalse(empty.copy(status = status).hasVisibleResponse())
    }

    @Test fun whitespaceAndLiveEllipsisAreNotResponseContent() {
        for (text in listOf("", "  \n\t", "...", "…", " . … ")) {
            assertFalse(empty.copy(reportedReasoning = listOf(ReportedReasoning("r", text, 0L, 0L))).hasVisibleResponse())
        }
        assertFalse(empty.copy(finalText = " \n\t").hasVisibleResponse())
    }

    @Test fun realReasoningShowsIdentityWhileActiveAndAfterCompletion() {
        assertTrue(empty.copy(reportedReasoning = listOf(ReportedReasoning("r", "actual summary", 0L, 0L))).hasVisibleResponse())
        assertTrue(empty.copy(reportedReasoning = listOf(ReportedReasoning("r", "actual summary", 1_200L))).hasVisibleResponse())
    }

    @Test fun plainAndMixedTextShowIdentity() {
        assertTrue(empty.copy(finalText = "answer").hasVisibleResponse())
        assertTrue(empty.copy(content = listOf(MessageContentUi.Text("body", "answer"))).hasVisibleResponse())
    }

    @Test fun mixedContentDoesNotBorrowLegacyBodyOrWorkProcess() {
        assertFalse(empty.copy(finalText = "legacy body", content = listOf(MessageContentUi.Text("body", "  ")),
            workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step))), "工作中")).hasVisibleResponse())
    }

    @Test fun mixedReasoningNeedsActualSummaryText() {
        assertFalse(empty.copy(content = listOf(MessageContentUi.Reasoning(ReportedReasoning("r", "...", 0L, 0L)))).hasVisibleResponse())
        assertTrue(empty.copy(content = listOf(MessageContentUi.Reasoning(ReportedReasoning("r", "summary")))).hasVisibleResponse())
    }

    @Test fun integratedPlaceholdersDoNotBorrowAnUnrenderedStandaloneSummary() {
        val reported = listOf(ReportedReasoning("old", "standalone summary"))
        val placeholder = ReportedReasoning("integrated", "…")
        assertFalse(empty.copy(reportedReasoning = reported,
            content = listOf(MessageContentUi.Reasoning(placeholder))).hasVisibleResponse())
        assertFalse(empty.copy(reportedReasoning = reported,
            workProcess = WorkProcessUi(listOf(WorkSegmentUi(reasoning = placeholder)), "工作中")).hasVisibleResponse())
        assertTrue(empty.copy(reportedReasoning = reported,
            content = listOf(MessageContentUi.Text("body", "  "))).hasVisibleResponse())
    }

    @Test fun toolCallsAndImagesAreActualResponses() {
        assertTrue(empty.copy(content = listOf(MessageContentUi.Tool(step))).hasVisibleResponse())
        val owner = MessageImageOwner("conversation", "reply")
        val image = MessageImageUi(owner, MessageImageReference(owner, "00000000-0000-0000-0000-000000000001",
            "image/png", 1, "a".repeat(64), 1, 1))
        assertTrue(empty.copy(content = listOf(MessageContentUi.Image(image))).hasVisibleResponse())
    }

    @Test fun workProcessNeedsNarrationReasoningOrAToolNotJustAStatusSummary() {
        assertFalse(empty.copy(workProcess = WorkProcessUi(emptyList(), "工作中", running = true)).hasVisibleResponse())
        assertFalse(empty.copy(workProcess = WorkProcessUi(listOf(WorkSegmentUi(narration = "  ",
            reasoning = ReportedReasoning("r", "...", 0L, 0L))), "工作中")).hasVisibleResponse())
        for (segment in listOf(WorkSegmentUi(narration = "narration"), WorkSegmentUi(steps = listOf(step)),
            WorkSegmentUi(reasoning = ReportedReasoning("r", "summary")))) {
            assertTrue(empty.copy(workProcess = WorkProcessUi(listOf(segment), "工作中")).hasVisibleResponse())
        }
    }

    @Test fun errorsRemainVisibleBeforeAnyModelOutput() {
        assertTrue(empty.copy(status = RunStatus.FAILED, error = "connection failed").hasVisibleResponse())
        assertFalse(empty.copy(error = "  ").hasVisibleResponse())
    }
}
