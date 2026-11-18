package app.daiyu.android.domain.context

import org.junit.Assert.*
import org.junit.Test

class HarnessContextPolicyTest {
    @Test fun fixedDensityUsesUtf16CeilingAndStructuredOverheads() {
        assertEquals(0L, HarnessTokenEstimate.text(""))
        assertEquals(1L, HarnessTokenEstimate.text("中文"))
        assertEquals(2L, HarnessTokenEstimate.text("😀😀😀😀"))
        assertEquals(6L, HarnessTokenEstimate.block("12345"))
        assertEquals(8L, HarnessTokenEstimate.toolCall("lookup", "{\"id\":1}"))
        assertEquals(0L, HarnessTokenEstimate.system(""))
        assertEquals(5L, HarnessTokenEstimate.system("sys"))
        assertEquals(0L, HarnessTokenEstimate.tools("[]", empty = true))
        assertEquals(5L, HarnessTokenEstimate.tools("[]"))
    }

    @Test fun signedAnchorNeverShrinksBelowLocalEstimateAndPressureUsesItsOwnProjection() {
        assertEquals(150L, HarnessTokenEstimate.measure(150))
        assertEquals(1050L, HarnessTokenEstimate.measure(150, 100, 1000))
        assertEquals(150L, HarnessTokenEstimate.measure(150, 100, 50))
        assertEquals(0L, HarnessTokenEstimate.project(10, 100, 20))
        assertEquals(850L, HarnessTokenEstimate.project(800, 100, 150))
    }

    @Test fun defaultSpecUsesHeadroomAndSixteenPercentOfMessageBudget() {
        val spec = HarnessContextPolicy.resolve(272000, 4096)
        assertEquals(202368L, spec.thresholdTokens)
        assertEquals(42864L, spec.retainTokens)
        val percentage = HarnessContextPolicy.resolve(1000000, 0)
        assertEquals(800000L, percentage.thresholdTokens)
        assertEquals(160000L, percentage.retainTokens)
        assertEquals(1, HarnessContextPolicy.COMPACTION_RETRIES)
        assertEquals(1, HarnessContextPolicy.MAX_OVERFLOW_RETRIES)
    }

    @Test fun impossibleDefaultHeadroomIsNotSilentlyClampedToAnotherPolicy() {
        assertThrows(IllegalArgumentException::class.java) { HarnessContextPolicy.resolve(65536, 0) }
        assertThrows(IllegalArgumentException::class.java) { HarnessContextPolicy.resolve(100000, 50000) }
        assertEquals(HarnessContextPolicy.Spec(800L, 160L),
            HarnessContextPolicy.resolve(1000, 0, headroomTokens = 0))
    }
}
