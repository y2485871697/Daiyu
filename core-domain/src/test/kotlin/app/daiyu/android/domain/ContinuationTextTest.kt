package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class ContinuationTextTest {
    @Test fun overlapIsOnlyAtTheFirstNewItemAndFinalIsCumulative() {
        val text = ContinuationText("delivered tail ")
        assertEquals("", text.suffix("a", "tail "))
        assertEquals("new", text.suffix("a", "tail new"))
        assertEquals("new final", text.suffix("a", "tail new final"))
        assertEquals("tail repeated intentionally", text.suffix("b", "tail repeated intentionally"))
    }
    @Test fun whitespaceIsNeverTrimmedAndMiddleRepetitionsAreKept() {
        val text = ContinuationText("prefix ")
        assertEquals(" next prefix ", text.suffix("a", "  next prefix "))
        assertEquals(" next prefix prefix ", text.suffix("a", "  next prefix prefix "))
    }
    @Test fun ambiguousStartNeverPublishesTextThatALaterCumulativeFinalRetracts() {
        val text = ContinuationText("abcabc")
        assertEquals("", text.suffix("a", "a"))
        assertEquals("", text.suffix("a", "ab"))
        assertEquals("", text.suffix("a", "abc"))
        assertEquals("d", text.suffix("a", "abcd"))
        assertEquals("dabcabc", text.suffix("a", "abcdabcabc"))
        assertEquals("abcabc", text.suffix("next", "abcabc"))
    }
    @Test fun failureFlushKeepsShortNonOverlapAndNextContinuationUsesItsDeliveredPrefix() {
        val text = ContinuationText("abcabc")
        assertEquals("", text.suffix("a", "ab"))
        assertEquals("a" to "ab", text.pendingBefore())
        assertEquals("ab", text.suffix("a", "ab", final = true))
        val again = ContinuationText("abcabcab")
        assertEquals("", again.suffix("b", "ab"))
        assertEquals("", again.suffix("b", "ab", final = true))
        assertEquals(" deliberate ab", again.suffix("b", "ab deliberate ab"))
    }
    @Test fun overlapIsBoundedAndRollbackCanRestoreBoundaryState() {
        val text = ContinuationText("0123456789", bound = 4)
        val before = text.fork()
        assertEquals("56789new", text.suffix("rejected", "56789new"))
        assertEquals("new", before.suffix("accepted", "6789new"))
    }
}
