package app.daiyu.android.ui.screens.settings

import app.daiyu.android.context.SummaryModelBinding
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test

class CompressionSettingsDraftTest {
    private val binding = SummaryModelBinding("summary-provider", "summary-model")

    @Test fun unknownWindowStaysUnknownAndDoesNotSelectSummary() {
        val result = CompressionSettingsDraftUi(binding).compression()
        assertNull(result.contextWindowTokens)
        assertFalse(result.automatic)
        assertNull(result.summaryModel)
    }

    @Test fun explicitFieldsKeepStableBindingAndExactBudgets() {
        val draft = CompressionSettingsDraftUi(binding, automatic = true, window = "32000",
            configureSummary = true, provider = ModelSettings("https://summary.invalid", "model", "test-key", false),
            summaryWindow = "32000", inputBudget = "27000", outputBudget = "2000",
            reasoningBudget = "1000", overheadBudget = "2000", deadlineMillis = "30000")
        assertEquals(binding, draft.compression().summaryModel)
        assertEquals(binding, draft.summary()!!.binding)
        assertEquals(27000L, draft.summary()!!.budgets.inputBudgetTokens)
        assertEquals(binding, draft.copy(window = "40000").summary()!!.binding)
    }

    @Test fun turningCustomSummaryOffAndOnRetainsTheSelectedBinding() {
        val selected = app.daiyu.android.context.CompressionSettingsEditor.Snapshot(
            app.daiyu.android.context.ProductionCompressionSettings(summaryModel = binding), null)
            .toDraft(SummaryModelBinding("unused", "placeholder"))
        val disabled = selected.copy(configureSummary = false).compression()
        assertNull(disabled.summaryModel)
        val readBack = app.daiyu.android.context.CompressionSettingsEditor.Snapshot(disabled, null)
            .toDraft(selected.binding)
        assertFalse(readBack.configureSummary)
        assertEquals(binding, readBack.copy(configureSummary = true).compression().summaryModel)
    }

    @Test fun invalidNumbersNeverTurnIntoDefaults() {
        assertTrue(runCatching { CompressionSettingsDraftUi(binding, window = "unknown").compression() }.isFailure)
        assertTrue(runCatching { CompressionSettingsDraftUi(binding, reserve = "-1").compression() }.isFailure)
        assertTrue(runCatching { CompressionSettingsDraftUi(binding, configureSummary = true).summary() }.isFailure)
    }

    @Test fun changingProtocolClearsKeyAndDoesNotApproveItself() {
        val original = CompressionSettingsDraftUi(binding,
            provider = ModelSettings("https://summary.invalid", "model", "never-print-key", false,
                responsesPath = "/responses"), savedProtocol = ProviderProtocol.RESPONSES, protocolChangeAuthorized = true)
        val changed = original.selectProtocol(ProviderProtocol.MESSAGES)
        assertEquals("", changed.provider.apiKey)
        assertEquals("", changed.provider.responsesPath)
        assertFalse(changed.protocolChangeAuthorized)
        assertEquals(ProviderProtocol.RESPONSES, changed.savedProtocol)
        assertEquals(changed, changed.selectProtocol(ProviderProtocol.MESSAGES))
        assertFalse(original.toString().contains("never-print-key"))
    }

    @Test fun missingProfileKeepsSavedProtocolUnknownUntilExplicitAuthorization() {
        val draft = CompressionSettingsDraftUi(binding, configureSummary = true)
        assertNull(draft.savedProtocol)
        assertTrue(draft.needsProtocolAuthorization)
        assertFalse(draft.protocolChangeAuthorized)
        assertTrue(draft.copy(protocolChangeAuthorized = true).protocolChangeAuthorized)
    }

    @Test fun protocolChangePreservesOptionsUntilExplicitReset() {
        val options = ProviderRequestOptions(
            customBody = """{"service_tier":"fast"}""",
            speedMode = GptSpeedMode.FAST,
        )
        val original = CompressionSettingsDraftUi(binding,
            provider = ModelSettings("https://summary.invalid", "model", "synthetic-key", false,
                requestOptions = options), savedProtocol = ProviderProtocol.RESPONSES)
        val changed = original.selectProtocol(ProviderProtocol.MESSAGES)
        assertEquals(options, changed.provider.requestOptions)
        assertFalse(changed.requestOptionsReplacementAuthorized)
        assertTrue(runCatching { changed.provider.requestOptions.validate(ProviderProtocol.MESSAGES) }.isFailure)
        val reset = changed.resetRequestOptions()
        assertEquals(ProviderRequestOptions(), reset.provider.requestOptions)
        assertTrue(reset.requestOptionsReplacementAuthorized)
        reset.provider.requestOptions.validate(ProviderProtocol.MESSAGES)
        assertEquals(binding, reset.binding)
    }
}
