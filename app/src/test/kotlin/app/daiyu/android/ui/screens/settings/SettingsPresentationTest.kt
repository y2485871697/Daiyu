package app.daiyu.android.ui.screens.settings

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.FeatureCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPresentationTest {
    @Test
    fun everyCatalogEntryHasAnExplicitIcon() {
        val ids = FeatureCatalog.groups.flatMap { it.entries }.map { it.id }
        assertEquals("Catalog entry IDs must be unique", ids.size, ids.toSet().size)
        assertEquals(ids.toSet(), settingsEntryIcons.keys)
    }

    @Test
    fun blankSearchPreservesAllEightGroupsAndTheirOrder() {
        assertEquals(8, FeatureCatalog.groups.size)
        assertEquals(FeatureCatalog.groups, filterSettingsGroups("  "))
    }

    @Test
    fun searchMatchesTitlesIgnoringCaseAndKeepsCatalogOrder() {
        val result = filterSettingsGroups("  gpt  ")
        assertEquals(listOf("model"), result.map { it.id })
        assertEquals(listOf("P02"), result.flatMap { it.entries }.map { it.id })
        assertEquals(
            FeatureCatalog.groups.flatMap { it.entries }.filter { it.title.contains("模型") },
            filterSettingsGroups("模型").flatMap { it.entries },
        )
    }

    @Test
    fun searchDoesNotMatchGroupTitlesOrEntryIds() {
        assertTrue(filterSettingsGroups("外观与阅读").isEmpty())
        assertTrue(filterSettingsGroups("R03").isEmpty())
        assertTrue(filterSettingsGroups("not-a-setting").isEmpty())
    }

    @Test
    fun protocolSwitchClearsSecretsAndRequiresFreshAuthorization() {
        val original = ModelSettingsDraftUi(apiKey = "test-only-secret", responsesPath = "/responses",
            protocolChangeAuthorized = true)
        val messages = original.selectProtocol(ProviderProtocol.MESSAGES)
        assertEquals("", messages.apiKey)
        assertEquals("", messages.responsesPath)
        assertEquals(ProviderProtocol.RESPONSES, messages.savedProtocol)
        assertFalse(messages.protocolChangeAuthorized)
        assertEquals(messages, messages.selectProtocol(ProviderProtocol.MESSAGES))
        assertEquals(ProviderProtocol.RESPONSES, messages.selectProtocol(ProviderProtocol.RESPONSES).savedProtocol)
    }

    @Test
    fun draftStringDoesNotRevealKeyOrCustomJson() {
        val draft = ModelSettingsDraftUi(apiKey = "never-print-key", customBody = "never-print-body")
        assertFalse(draft.toString().contains("never-print-key"))
        assertFalse(draft.toString().contains("never-print-body"))
    }
}
