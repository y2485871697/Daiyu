package app.daiyu.android.model.catalog

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogModelEditTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun oldCodecDefaultsRemainUnknownAndNewOverridesRoundTrip() {
        val original = CatalogModelParameters()
        val old = JSONObject(CatalogModelParametersCodec.encode(original))
        listOf("context_window_override", "reasoning_override", "vision_override").forEach { old.remove(it) }
        assertEquals(original, CatalogModelParametersCodec.decode(old.toString()))
        val value = original.copy(contextWindowOverride = 272000, reasoningOverride = true, visionOverride = false)
        assertEquals(value, CatalogModelParametersCodec.decode(CatalogModelParametersCodec.encode(value)))
        old.put("context_window_override", "272000")
        assertThrows(IllegalStateException::class.java) { CatalogModelParametersCodec.decode(old.toString()) }
    }
    @Test fun editAndRestorePreserveIdentityAndExistingPolicy() {
        val original = SavedCatalogModel("local-model", "Original", CatalogModelParameters(batteryEnabled = true,
            requestOptions = app.daiyu.android.model.ProviderRequestOptions(customBody = "{\"temperature\":0.25}")))
        val edit = CatalogModelEdit("local-model", "Updated", "4096", true, false)
        val changed = edit.applyTo(original)
        assertEquals(original.settings.localIdentity, changed.settings.localIdentity)
        assertEquals(original.settings.requestOptions, changed.settings.requestOptions)
        assertTrue(changed.settings.batteryEnabled)
        val restored = CatalogModelEdit.from(changed).copy(contextWindow = "", reasoning = null, vision = null).applyTo(changed)
        assertEquals(original.settings, restored.settings)
        assertEquals("Updated", restored.displayName)
        assertThrows(IllegalArgumentException::class.java) { edit.copy(contextWindow = "0").validate() }
        assertThrows(IllegalArgumentException::class.java) { edit.copy(contextWindow = "2147483648").validate() }
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
