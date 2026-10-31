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
        assertEquals("上下文", CloudContextUsageUi().label(Locale.US))
        assertNull(CloudContextUsageUi(40_000).fraction)
    }
    @Test fun missingInputOrInvalidWindowShowsOnlyContextWithoutInventingAZero() {
        for (value in listOf(CloudContextUsageUi(), CloudContextUsageUi(null, 272_000),
            CloudContextUsageUi(-1, 272_000), CloudContextUsageUi(40_000),
            CloudContextUsageUi(40_000, 0), CloudContextUsageUi(40_000, -1), CloudContextUsageUi(0))) {
            assertNull(value.fraction)
            assertEquals("上下文", value.label(Locale.US))
        }
    }
    @Test fun zeroIsKnownAndRoundTripsToBothDisplayModes() {
        val input = acceptedContextInput(ModelEvent.Usage(0, 123), 272_000)
        assertEquals(0L, input)
        val value = CloudContextUsageUi(input, 272_000)
        assertEquals(0f, value.fraction!!, 0f)
        assertEquals("0.0% 上下文", value.label(Locale.US))
        assertEquals("0/272K", value.tokensLabel(Locale.US))
    }
    @Test fun compactActualAndConfiguredLimitHaveNoInventedMissingFields() {
        assertEquals("4K/272K", CloudContextUsageUi(4_000, 272_000).tokensLabel(Locale.US))
        assertEquals("4.1K/272K", CloudContextUsageUi(4_100, 272_000).tokensLabel(Locale.US))
        assertEquals("999/272K", CloudContextUsageUi(999, 272_000).tokensLabel(Locale.US))
        assertEquals("—/272K", CloudContextUsageUi(null, 272_000).tokensLabel(Locale.US))
        assertEquals("4K/—", CloudContextUsageUi(4_000).tokensLabel(Locale.US))
        assertEquals("0/—", CloudContextUsageUi(0, 0).tokensLabel(Locale.US))
        assertEquals("—/—", CloudContextUsageUi().tokensLabel(Locale.US))
    }
    @Test fun uppercaseThousandsKeepTheThresholdAndLocalizedDecimals() {
        assertEquals("1K/272K", CloudContextUsageUi(1_000, 272_000).tokensLabel(Locale.US))
        assertEquals("4,1K/272K", CloudContextUsageUi(4_100, 272_000).tokensLabel(Locale.GERMANY))
        assertEquals("—/272K", CloudContextUsageUi(-1, 272_000).tokensLabel(Locale.US))
    }
    @Test fun millionWindowsAndActualsChooseTheirUnitsIndependently() {
        assertEquals("89K/1M", CloudContextUsageUi(89_000, 1_000_000).tokensLabel(Locale.US))
        assertEquals("1M/1M", CloudContextUsageUi(1_000_000, 1_000_000).tokensLabel(Locale.US))
        assertEquals("1.5M/2M", CloudContextUsageUi(1_500_000, 2_000_000).tokensLabel(Locale.US))
        assertEquals("1M/272K", CloudContextUsageUi(1_000_000, 272_000).tokensLabel(Locale.US))
        assertEquals("999K/1M", CloudContextUsageUi(999_000, 1_000_000).tokensLabel(Locale.US))
    }
    @Test fun millionFormattingKeepsLocalizedOneDecimalAndNoTrailingZero() {
        assertEquals("1,5M/2M", CloudContextUsageUi(1_500_000, 2_000_000).tokensLabel(Locale.GERMANY))
        assertEquals("1.2M/1.6M", CloudContextUsageUi(1_234_567, 1_560_000).tokensLabel(Locale.US))
        assertEquals("1M/1M", CloudContextUsageUi(1_000_001, 1_000_000).tokensLabel(Locale.US))
        assertEquals("2.1M/2.1M", CloudContextUsageUi(Int.MAX_VALUE.toLong() / 1_000,
            Int.MAX_VALUE.toLong() / 1_000).tokensLabel(Locale.US))
    }
    @Test fun unknownNegativeAndZeroValuesRetainTheirMeaningAlongsideM() {
        assertEquals("—/1M", CloudContextUsageUi(null, 1_000_000).tokensLabel(Locale.US))
        assertEquals("—/1M", CloudContextUsageUi(-1, 1_000_000).tokensLabel(Locale.US))
        assertEquals("0/1M", CloudContextUsageUi(0, 1_000_000).tokensLabel(Locale.US))
        assertEquals("1M/—", CloudContextUsageUi(1_000_000).tokensLabel(Locale.US))
        assertEquals("1M/—", CloudContextUsageUi(1_000_000, 0).tokensLabel(Locale.US))
        assertEquals("1M/—", CloudContextUsageUi(1_000_000, -1).tokensLabel(Locale.US))
    }
    @Test fun compactMillionsDoNotChangePercentageOrRawTokenValues() {
        val value = CloudContextUsageUi(89_000, 1_000_000)
        assertEquals("89K/1M", value.tokensLabel(Locale.US))
        assertEquals(89_000L, value.inputTokens)
        assertEquals(1_000_000L, value.windowTokens)
        assertEquals(0.089f, value.fraction!!, 0.000001f)
        assertEquals("8.9% 上下文", value.label(Locale.US))
        val overflow = CloudContextUsageUi(1_500_000, 1_000_000)
        assertEquals("1.5M/1M", overflow.tokensLabel(Locale.US))
        assertEquals(1f, overflow.fraction!!, 0f)
        assertEquals("150.0% 上下文", overflow.label(Locale.US))
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
    @Test fun stableReceiptSurvivesEditorAndRegistryRevisionsButNotAnotherAccountOrModel() {
        val identity = contextReceiptIdentity("account", "local-model", "responses", "https://example.test/v1/responses", "")
        val sent = ContextUsageModel("provider", "model", 52, 272_000, 4, identity)
        val reopened = sent.copy(configuration = 82, registryRevision = 9)
        assertTrue(reopened.accepts(sent))
        assertFalse(reopened.copy(provider = "other").accepts(sent))
        assertFalse(reopened.copy(model = "other").accepts(sent))
        assertFalse(reopened.copy(receiptIdentity = contextReceiptIdentity("replacement", "local-model", "responses",
            "https://example.test/v1/responses", "")).accepts(sent))
        assertFalse(reopened.copy(receiptIdentity = null).accepts(sent))
    }
    @Test fun legacyReceiptStillRequiresTheExactSavedConfiguration() {
        val legacy = ContextUsageModel("provider", "model", 52, 272_000, 4)
        assertTrue(legacy.accepts(legacy))
        assertTrue(legacy.copy(receiptIdentity = "a".repeat(64)).accepts(legacy))
        assertFalse(legacy.copy(configuration = 82).accepts(legacy))
        assertFalse(legacy.copy(registryRevision = 5).accepts(legacy))
        assertFalse(legacy.copy(provider = "other").accepts(legacy))
        assertFalse(legacy.copy(model = "other").accepts(legacy))
    }
    @Test fun receiptScopeIsStableAndSeparatesAccountRecordProtocolEndpointAndCustomBody() {
        val identity = contextReceiptIdentity("account", "record", "responses", "https://example.test/responses", "")
        assertEquals(identity, contextReceiptIdentity("account", "record", "responses", "https://example.test/responses", ""))
        assertTrue(identity.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(identity, contextReceiptIdentity("new-account", "record", "responses", "https://example.test/responses", ""))
        assertNotEquals(identity, contextReceiptIdentity("account", "new-record", "responses", "https://example.test/responses", ""))
        assertNotEquals(identity, contextReceiptIdentity("account", "record", "messages", "https://example.test/responses", ""))
        assertNotEquals(identity, contextReceiptIdentity("account", "record", "responses", "https://other.test/responses", ""))
        assertNotEquals(identity, contextReceiptIdentity("account", "record", "responses", "https://example.test/responses", "{}"))
        assertNotEquals(contextReceiptIdentity("ab", "c", "responses", "https://example.test", ""),
            contextReceiptIdentity("a", "bc", "responses", "https://example.test", ""))
    }

}
