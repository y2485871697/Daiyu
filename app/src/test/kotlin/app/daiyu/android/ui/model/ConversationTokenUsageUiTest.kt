package app.daiyu.android.ui.model

import org.junit.Assert.*
import org.junit.Test

class ConversationTokenUsageUiTest {
    @Test fun normalizedInputIncludesCacheExactlyOnce() {
        val value = ConversationTokenUsageUi(17, 8, 5, 2, 1)
        assertEquals(25L, value.totalTokens)
        assertEquals(10L, value.freshInputTokens)
        assertEquals(5.0 / 17.0 * 100.0, value.cachePercent!!, 0.0001)
    }
    @Test fun unknownAndActualZeroAreDifferent() {
        val unknown = ConversationTokenUsageUi(17, 8, 5, requestCount = 1)
        assertEquals(25L, unknown.totalTokens)
        assertNull(unknown.freshInputTokens)
        assertNull(unknown.cacheCreationTokens)
        val zero = ConversationTokenUsageUi(0, 0, 0, 0, 1)
        assertTrue(zero.hasUsage); assertEquals(0L, zero.totalTokens)
        assertEquals(0L, zero.freshInputTokens); assertNull(zero.cachePercent)
        assertFalse(ConversationTokenUsageUi().hasUsage)
        assertNull(ConversationTokenUsageUi(Long.MAX_VALUE, 1).totalTokens)
    }
    @Test fun invalidSubsetsDoNotInventFreshInput() {
        assertNull(ConversationTokenUsageUi(10, 0, 8, 3, 1).freshInputTokens)
    }
}
