package app.daiyu.android.model.catalog

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogModelEditTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun oldCodecDefaultsAreEnabledWithoutRewritingStoredOverrides() {
        val original = CatalogModelParameters()
        val old = JSONObject(CatalogModelParametersCodec.encode(original))
        listOf("context_window_override", "reasoning_override", "vision_override").forEach { old.remove(it) }
        val decoded = CatalogModelParametersCodec.decode(old.toString())
        assertEquals(original, decoded)
        assertNull(decoded.reasoningOverride)
        assertNull(decoded.visionOverride)
        assertTrue(decoded.reasoningSupported)
        assertTrue(decoded.visionSupported)
        assertEquals(CatalogModelEdit("legacy", "Legacy"),
            CatalogModelEdit.from(SavedCatalogModel("legacy", "Legacy", decoded)))
        val value = original.copy(contextWindowOverride = 272000, reasoningOverride = true, visionOverride = false)
        assertEquals(value, CatalogModelParametersCodec.decode(CatalogModelParametersCodec.encode(value)))
        old.put("context_window_override", "272000")
        assertThrows(IllegalStateException::class.java) { CatalogModelParametersCodec.decode(old.toString()) }
    }
    @Test fun editAndClearContextPreserveIdentityPolicyAndCapabilityChoices() {
        val original = SavedCatalogModel("local-model", "Original", CatalogModelParameters(batteryEnabled = true,
            requestOptions = app.daiyu.android.model.ProviderRequestOptions(customBody = "{\"temperature\":0.25}")))
        val edit = CatalogModelEdit("local-model", "Updated", "4096", true, false)
        val changed = edit.applyTo(original)
        assertEquals(original.settings.localIdentity, changed.settings.localIdentity)
        assertEquals(original.settings.requestOptions, changed.settings.requestOptions)
        assertTrue(changed.settings.batteryEnabled)
        val restored = CatalogModelEdit.from(changed).copy(contextWindow = "").applyTo(changed)
        assertEquals(original.settings.copy(reasoningOverride = true, visionOverride = false), restored.settings)
        assertEquals("Updated", restored.displayName)
        assertThrows(IllegalArgumentException::class.java) { edit.copy(contextWindow = "0").validate() }
        assertThrows(IllegalArgumentException::class.java) { edit.copy(contextWindow = "2147483648").validate() }
    }
    @Test fun newModelsDefaultOnAndSavedOffSurvivesReopenAndPolicyUpdate() {
        val form = CatalogModelEdit("model", "Model")
        assertTrue(form.reasoning)
        assertTrue(form.vision)
        val fresh = form.newRecord()
        assertTrue(fresh.settings.reasoningSupported)
        assertTrue(fresh.settings.visionSupported)
        for (reasoning in listOf(false, true)) for (vision in listOf(false, true)) {
            val changed = form.copy(reasoning = reasoning, vision = vision).applyTo(fresh)
            val persistence = FileSavedModelCatalogPersistence(
                File(temporary.root, "models-$reasoning-$vision"), CatalogModelParametersCodec)
            persistence.register(listOf(changed))
            val reopened = FileSavedModelCatalogPersistence(
                File(temporary.root, "models-$reasoning-$vision"), CatalogModelParametersCodec).current()!!.models.single()
            assertEquals(form.copy(reasoning = reasoning, vision = vision), CatalogModelEdit.from(reopened))
            assertEquals(fresh.settings.localIdentity, reopened.settings.localIdentity)
            val updated = CatalogModelParameters.updated(app.daiyu.android.storage.ModelSettings(
                "https://example.invalid", "model", "synthetic-key", false), reopened)
            assertEquals(reasoning, updated.settings.reasoningSupported)
            assertEquals(vision, updated.settings.visionSupported)
        }
    }
    @Test fun nativeSearchIsIndependentAndSurvivesDiskReopenAndProviderUpdates() {
        for (search in listOf(false, true)) {
            val original = SavedCatalogModel("a", "A", CatalogModelParameters(batteryEnabled = true,
                requestOptions = app.daiyu.android.model.ProviderRequestOptions(customBody = "{\"temperature\":0.5}")))
            val form = CatalogModelEdit.from(original).copy(nativeSearch = search)
            val edited = form.applyTo(original)
            assertEquals(original.settings.localIdentity, edited.settings.localIdentity)
            assertEquals(original.settings.requestOptions.copy(openAiWebSearchEnabled = search), edited.settings.requestOptions)
            assertTrue(edited.settings.batteryEnabled)
            val file = File(temporary.root, "search-$search")
            FileSavedModelCatalogPersistence(file, CatalogModelParametersCodec).register(listOf(edited))
            val reopened = FileSavedModelCatalogPersistence(file, CatalogModelParametersCodec).current()!!.models.single()
            assertEquals(search, CatalogModelEdit.from(reopened).nativeSearch)
            val stale = app.daiyu.android.storage.ModelSettings("https://example.invalid", "a", "key", false,
                requestOptions = app.daiyu.android.model.ProviderRequestOptions(openAiWebSearchEnabled = !search))
            assertEquals(search, CatalogModelParameters.updated(stale, reopened).settings.requestOptions.openAiWebSearchEnabled)
            assertEquals(search, reopened.settings.applyTo(stale, "a").requestOptions.openAiWebSearchEnabled)
            assertFalse(CatalogModelParameters.newModel(RemoteModel("b")).settings.requestOptions.openAiWebSearchEnabled)
        }
        assertFalse(CatalogModelEdit("new", "New").nativeSearch)
        val legacy = app.daiyu.android.storage.ModelSettings("https://example.invalid", "a", "key", false,
            requestOptions = app.daiyu.android.model.ProviderRequestOptions(openAiWebSearchEnabled = true))
        assertTrue(CatalogModelEdit.from(CatalogModelParameters.fromCurrent(legacy)).nativeSearch)
        assertTrue(CatalogModelParameters.updated(legacy, null).settings.requestOptions.openAiWebSearchEnabled)
    }

    @Test fun atomicModelFormSaveRejectsStaleForgedAndCancelledTickets() {
        val persistence = FileSavedModelCatalogPersistence(File(temporary.root, "models"), CatalogModelParametersCodec)
        val first = CatalogModelEdit("one", "One").newRecord()
        val second = CatalogModelEdit("two", "Two").newRecord()
        val before = persistence.register(listOf(first, second))
        val ticket = before.selectionFor("one")!!
        val replacement = CatalogModelEdit("one", "Edited", "8192", true).applyTo(first)
        assertEquals(CatalogLocalEditResult.CANCELLED, persistence.replaceSelection(ticket, replacement) { true })
        assertEquals(before, persistence.current())
        assertEquals(CatalogLocalEditResult.STALE, persistence.replaceSelection(ticket.copy(record = first.copy(displayName = "forged")), replacement) { false })
        assertEquals(CatalogLocalEditResult.APPLIED, persistence.replaceSelection(ticket, replacement) { false })
        assertEquals(listOf(replacement, second), persistence.current()!!.models)
        assertEquals(CatalogLocalEditResult.STALE, persistence.replaceSelection(ticket, first) { false })
        assertEquals(listOf(replacement, second), persistence.current()!!.models)
    }
}
