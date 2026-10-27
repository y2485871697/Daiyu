package app.daiyu.android.context

import app.daiyu.android.domain.ModelEvent
import org.junit.Assert.*
import org.junit.Test

class SummaryOutputBudgetTest {
    private fun reject(action: () -> Unit) { try { action(); fail("must reject") } catch (_: IllegalArgumentException) {} }
    @Test fun cumulativeFinalAndTerminalAreReplacementsNotDoubleCounting() {
        val budget = SummaryOutputBudget(6, textLimit = 6)
        budget.observe(ModelEvent.TextFinal("one", "abc"))
        budget.observe(ModelEvent.TextFinal("one", "abcdef"))
        budget.observe(ModelEvent.TextFinal("one", "abcdef"))
        budget.validateTerminal(listOf("abcdef"))
        reject { budget.observe(ModelEvent.TextDelta("one", "g")) }
    }
    @Test fun unicodeAndAllItemIdsConsumeTheSameBudget() {
        val budget = SummaryOutputBudget(7, textLimit = 7)
        budget.observe(ModelEvent.TextFinal("one", "中"))
        budget.observe(ModelEvent.TextFinal("two", "😀"))
        reject { budget.observe(ModelEvent.TextFinal("three", "x")) }
    }
    @Test fun visibleReasoningIsSeparateFromProseButNotFromOutputLimit() {
        val budget = SummaryOutputBudget(6, textLimit = 6)
        budget.observe(ModelEvent.ReasoningFinal("same", "abc"))
        budget.observe(ModelEvent.TextFinal("same", "def"))
        reject { budget.observe(ModelEvent.ReasoningDelta("same", "g")) }
    }
    @Test fun terminalOnlyBodyAndSeparatorsCannotBypassTheLimit() {
        reject { SummaryOutputBudget(5, textLimit = 5).validateTerminal(listOf("abcdef")) }
        reject { SummaryOutputBudget(6, textLimit = 6).validateTerminal(listOf("abc", "def")) }
    }
    @Test fun reportedTotalOutputIncludesHiddenReasoningAndIsNotAccumulated() {
        val budget = SummaryOutputBudget(6, textLimit = 6)
        budget.observe(ModelEvent.Usage(1, null))
        budget.observe(ModelEvent.Usage(1, 6)); budget.observe(ModelEvent.Usage(1, 6))
        reject { budget.observe(ModelEvent.Usage(1, 7)) }
    }
}
