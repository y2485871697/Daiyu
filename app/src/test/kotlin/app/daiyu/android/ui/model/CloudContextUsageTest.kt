package app.daiyu.android.ui.model

import app.daiyu.android.domain.ModelEvent
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CloudContextUsageTest {
    @Test fun usesOnlyNormalizedInputAndTheFullWindow() {
        val input = acceptedContextInput(ModelEvent.Usage(40_000, 10_000, 30_000, 2_000), 128_000)
        val value = CloudContextUsageUi(input, 128_000)
        assertEquals(0.3125f, value.fraction!!, 0.00001f)
        assertEquals("31.2% 上下文", value.label(Locale.US))
    }
    @Test fun unknownAndOutputOnlyDoNotFabricateAZeroPercent() {
        assertNull(acceptedContextInput(ModelEvent.Usage(null, 123), 128_000))
        assertNull(CloudContextUsageUi().fraction)
        assertEquals("未知 上下文", CloudContextUsageUi().label(Locale.US))
        assertNull(CloudContextUsageUi(40_000).fraction)
    }
    @Test fun zeroIsKnownAndRoundTripsToBothDisplayModes() {
        val input = acceptedContextInput(ModelEvent.Usage(0, 123), 272_000)
        assertEquals(0L, input)
        val value = CloudContextUsageUi(input, 272_000)
        assertEquals(0f, value.fraction!!, 0f)
        assertEquals("0.0% 上下文", value.label(Locale.US))
        assertEquals("0/272k", value.tokensLabel(Locale.US))
    }
    @Test fun compactActualAndConfiguredLimitHaveNoInventedMissingFields() {
        assertEquals("4k/272k", CloudContextUsageUi(4_000, 272_000).tokensLabel(Locale.US))
        assertEquals("4.1k/272k", CloudContextUsageUi(4_100, 272_000).tokensLabel(Locale.US))
        assertEquals("999/272k", CloudContextUsageUi(999, 272_000).tokensLabel(Locale.US))
        assertEquals("—/272k", CloudContextUsageUi(null, 272_000).tokensLabel(Locale.US))
        assertEquals("4k/—", CloudContextUsageUi(4_000).tokensLabel(Locale.US))
        assertEquals("0/—", CloudContextUsageUi(0, 0).tokensLabel(Locale.US))
        assertEquals("—/—", CloudContextUsageUi().tokensLabel(Locale.US))
    }
    @Test fun arcClampsButTheOneDecimalLabelRetainsOverflow() {
        val value = CloudContextUsageUi(120_000, 100_000)
        assertEquals(1f, value.fraction!!, 0f)
        assertEquals("120.0% 上下文", value.label(Locale.US))
        assertNull(acceptedContextInput(ModelEvent.Usage(130_001, 0), 100_000))
        assertThrows(IllegalArgumentException::class.java) { ModelEvent.Usage(90, 0, 91) }
        assertNull(acceptedContextInput(ModelEvent.Usage(120, 0, 101), 100))
        assertEquals(130L, acceptedContextInput(ModelEvent.Usage(130, 0), 100))
    }
}
