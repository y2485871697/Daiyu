package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.ReportedReasoning
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportedReasoningTextTest {
    @Test fun emptyAndWhitespaceAreNotSummaries() {
        listOf("", " ", "\n\t", "\u00a0\u3000").forEach { text ->
            assertFalse(text, hasReportedReasoningText(text))
        }
    }

    @Test fun placeholderEllipsesAreNotSummaries() {
        listOf(".", "..", "...", "…", "……", " \n... \t", ". … .").forEach { text ->
            assertFalse(text, hasReportedReasoningText(text))
        }
    }

    @Test fun realTextAndEmbeddedEllipsesRemainVisible() {
        listOf("摘要", "A", "正在检查…", "...checking", "\n summary \n", "0", "?", "1.2").forEach { text ->
            assertTrue(text, hasReportedReasoningText(text))
        }
    }

    @Test fun activeReasoningFollowsItsAnchorRatherThanTimingPermission() {
        val active = listOf(ReportedReasoning("r", "...", 0L, 0L))
        assertTrue(isReportedReasoningActive(active))
        assertFalse(isReportedReasoningActive(active.map { it.frozen(1_000_000L) }))
        assertFalse(isReportedReasoningActive(emptyList()))
    }

    @Test fun finishedAndUnknownTimeSummariesDoNotReplaceTheLiveFallback() {
        assertFalse(isReportedReasoningActive(listOf(ReportedReasoning("r", "summary", 200L))))
        assertFalse(isReportedReasoningActive(listOf(ReportedReasoning("r", "summary"))))
        assertTrue(hasReportedReasoningText("**Planning concise warm Chinese story answer**"))
    }
    @Test fun reasoningLabelsUseGenerationStateEvenWhenLocalDisplayIsPaused() {
        org.junit.Assert.assertEquals("正在思考…", reasoningDisplayLabel(thinking = true, paused = false))
        org.junit.Assert.assertEquals("已暂停", reasoningDisplayLabel(thinking = true, paused = true))
        org.junit.Assert.assertEquals("已思考", reasoningDisplayLabel(thinking = false, paused = false))
        org.junit.Assert.assertEquals("已思考", reasoningDisplayLabel(thinking = false, paused = true))
    }

}
