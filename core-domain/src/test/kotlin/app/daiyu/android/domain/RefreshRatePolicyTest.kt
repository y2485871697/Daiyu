package app.daiyu.android.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshRatePolicyTest {
    @Test
    fun selectsTheHighestSupportedRefreshRate() {
        assertEquals(120f, highestSupportedRefreshRate(listOf(60f, 90f, 120f)), 0f)
    }

    @Test
    fun ignoresNonPositiveAndNonFiniteValues() {
        assertEquals(90f, highestSupportedRefreshRate(listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY, 90f)), 0f)
    }

    @Test
    fun returnsZeroWhenNoUsableRefreshRateExists() {
        assertEquals(0f, highestSupportedRefreshRate(emptyList()), 0f)
    }

    @Test
    fun returnsZeroWhenEveryReportedRateIsInvalid() {
        assertEquals(0f, highestSupportedRefreshRate(listOf(-1f, 0f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)), 0f)
    }

    @Test
    fun selectionIsIndependentOfOrderAndRetainsFractionalRates() {
        assertEquals(119.88f, highestSupportedRefreshRate(listOf(119.88f, 60f, 90f)), 0f)
    }

}
