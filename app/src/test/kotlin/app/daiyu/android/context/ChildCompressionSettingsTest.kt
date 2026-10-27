package app.daiyu.android.context

import app.daiyu.android.model.catalog.*
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test

class ChildCompressionSettingsTest {
    private fun model(id: String, window: Int?) = ModelSettings("https://example.invalid", id,
        "fixture-key", false, contextWindowOverride = window).requestSnapshot()

    @Test fun childDefaultIsAutomaticWithItsOwnWindowEvenWhenMainAutomaticIsOff() {
        val main = ProductionCompressionSettings(contextWindowTokens = 128000)
        val child = main.childSettings(model("child", 272000))
        val policy = ProductionCompressionPolicyResolver.resolve(child)
        assertTrue(child.automatic); assertTrue(policy.enabled)
        assertEquals(272000L, policy.contextTokens)
        assertFalse(policy.shouldCompress(217599)); assertTrue(policy.shouldCompress(217600))
        assertEquals(267904L, policy.sendBudgetTokens)
        assertFalse(main.automatic); assertEquals(128000L, main.contextWindowTokens)
    }
    @Test fun eachChildHasIndependentWindowAndClearingItNeverBorrowsTheGlobalWindow() {
        for (mainAutomatic in listOf(false, true)) {
            val main = ProductionCompressionSettings(mainAutomatic, 128000)
            for (window in listOf(32768, 272000, null)) {
                val child = main.childSettings(model("child-$window", window))
                val policy = ProductionCompressionPolicyResolver.resolve(child)
                assertTrue(child.automatic)
                assertEquals(window?.toLong(), policy.contextTokens)
                assertEquals(window != null, policy.enabled)
            }
        }
    }
    @Test fun childKeepsCustomSummaryAndReservationWithoutMutatingSavedSettings() {
        val binding = SummaryModelBinding("custom", "summary")
        val main = ProductionCompressionSettings(false, 128000, 8192, summaryModel = binding)
        val snapshot = model("child", 272000)
        val child = main.childSettings(snapshot)
        assertEquals(binding, child.summaryModel); assertEquals(8192L, child.reserveTokens)
        assertTrue(child.tokenTail); assertEquals(80, child.triggerPercent)
        assertEquals(main.coordinatorSettings().copy(automatic = true, contextWindowTokens = 272000), child)
        assertEquals(272000, snapshot.contextWindowOverride)
        assertFalse(main.automatic); assertEquals(128000L, main.contextWindowTokens)
    }
    @Test fun editingModelCapacityFlowsThroughTheSavedRecordAndFrozenSnapshot() {
        val base = model("main", 128000).asModelSettings()
        val record = SavedCatalogModel("child", "Child", CatalogModelParameters())
        val edited = CatalogModelEdit.from(record).copy(contextWindow = "272000").applyTo(record)
        val frozen = edited.settings.applyTo(base, edited.id).requestSnapshot()
        val main = ProductionCompressionSettings(contextWindowTokens = 96000)
        assertEquals(272000L, main.childSettings(frozen).contextWindowTokens)
        val cleared = CatalogModelEdit.from(edited).copy(contextWindow = "").applyTo(edited)
        val next = cleared.settings.applyTo(base, cleared.id).requestSnapshot()
        assertNull(main.childSettings(next).contextWindowTokens)
        assertEquals(272000L, main.childSettings(frozen).contextWindowTokens)
    }
    @Test fun noCustomSummaryUsesEachChildsFrozenIdentityAndOwnWindow() {
        for ((id, window) in listOf("child-a" to 32768, "child-b" to 272000)) {
            val snapshot = model(id, window)
            val config = CatalogSummaryExecutionPolicy.childConfiguration(snapshot, window.toLong(), null) {
                error("no custom summary selected")
            }
            assertEquals(id, config.apiModel); assertEquals(snapshot.endpoint, config.provider.endpoint)
            assertEquals(snapshot.apiKey, config.provider.apiKey); assertEquals(snapshot.protocol, config.provider.protocol)
            assertEquals(window.toLong(), config.contextWindowTokens)
            assertFalse(config.provider.batteryEnabled); assertFalse(config.provider.deviceStatusEnabled)
        }
    }
    @Test fun customSummaryWinsWithoutReplacingChildCapacityAndMissingSelectionNeverFallsBack() {
        val snapshot = model("child", 272000)
        val binding = SummaryModelBinding("custom-provider", "custom-summary")
        val main = ProductionCompressionSettings(false, 128000, summaryModel = binding)
        val child = main.childSettings(snapshot)
        val selected = CatalogSummaryExecutionPolicy.configuration(binding, model("custom-summary", 64000).asModelSettings())
        var resolutions = 0
        var savedSelection = selected
        val actual = CatalogSummaryExecutionPolicy.childConfiguration(snapshot, 272000, child.summaryModel) {
            assertEquals(binding, it); resolutions++; savedSelection
        }
        savedSelection = CatalogSummaryExecutionPolicy.configuration(binding, model("next-summary", 96000).asModelSettings())
        assertSame(selected, actual); assertEquals(1, resolutions)
        assertEquals("custom-summary", actual.apiModel)
        val next = CatalogSummaryExecutionPolicy.childConfiguration(snapshot, 272000, binding) { savedSelection }
        assertEquals("next-summary", next.apiModel); assertEquals(64000L, actual.contextWindowTokens)
        assertEquals(64000L, actual.contextWindowTokens); assertEquals(272000L, child.contextWindowTokens)
        assertThrows(IllegalArgumentException::class.java) {
            CatalogSummaryExecutionPolicy.childConfiguration(snapshot, 272000, binding) { null }
        }
    }
}
