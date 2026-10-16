package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class ModelUsageContractTest {
    @Test fun unknownZeroAndCacheSubsetRemainDistinct() {
        assertNotEquals(ModelEvent.Usage(null, null), ModelEvent.Usage(0, 0, 0))
        val cacheOnly = ModelEvent.Usage(null, null, 7)
        assertNull(cacheOnly.inputTokens); assertEquals(7L, cacheOnly.cachedTokens)
        val allCached = ModelEvent.Usage(7, null, 7)
        assertEquals(allCached.inputTokens, allCached.cachedTokens)
        assertEquals(Long.MAX_VALUE, ModelEvent.Usage(Long.MAX_VALUE, null).inputTokens)
    }
    @Test fun negativeCountsAndCacheOutsideKnownInputAreRejected() {
        val invalid: List<() -> ModelEvent.Usage> = listOf(
            { ModelEvent.Usage(-1, null) }, { ModelEvent.Usage(null, -1) },
            { ModelEvent.Usage(null, null, -1) }, { ModelEvent.Usage(0, null, 1) })
        invalid.forEach { construct ->
            try { construct(); fail("invalid usage accepted") } catch (_: IllegalArgumentException) {}
        }
    }
}
