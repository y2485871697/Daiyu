package app.daiyu.android.model.catalog

import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.EditingSettings
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogModelParametersTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unavailablePolicyCannotPersistFallbackDefaultsDuringMigration() {
        val file = File(temporary.root, "catalog")
        val store = FileSavedModelCatalogPersistence(file, CatalogModelParametersCodec)
        val fallback = EditingSettings(ModelSettings("https://provider.invalid", "model", ""),
            credentialUnavailable = true, requestOptionsUnavailable = true)
        assertThrows(IllegalStateException::class.java) {
            store.registerFrom { listOf(CatalogModelParameters.fromEditing(fallback)) }
        }
        assertFalse(file.exists())
        assertFalse(File(file.path + ".pending").exists())
        assertNull(store.current())
        val repaired = fallback.copy(settings = fallback.settings.copy(
            requestOptions = ProviderRequestOptions("{\"temperature\":0.25}")), requestOptionsUnavailable = false)
        // An unavailable credential alone does not discard otherwise valid model parameters.
        val saved = store.registerFrom { listOf(CatalogModelParameters.fromEditing(repaired)) }
        assertEquals(repaired.settings.requestOptions, saved.models.single().settings.requestOptions)
        assertEquals(saved, FileSavedModelCatalogPersistence(file, CatalogModelParametersCodec).current())
    }

    @Test fun migrationPreservesCurrentParametersButNeverStoresCredentialsOrEndpoint() {
        val settings = ModelSettings("https://private.invalid", "customized", "never-persist-this-key", true,
            deviceStatusEnabled = true,
            requestOptions = ProviderRequestOptions("{\"temperature\":0.25}", GptSpeedMode.FAST, true))
        val migrated = CatalogModelParameters.fromCurrent(settings)
        val encoded = CatalogModelParametersCodec.encode(migrated.settings)
        assertEquals(migrated.settings, CatalogModelParametersCodec.decode(encoded))
        assertEquals(settings.requestOptions, migrated.settings.requestOptions)
        assertTrue(migrated.settings.batteryEnabled)
        assertTrue(migrated.settings.deviceStatusEnabled)
        assertFalse(encoded.contains(settings.apiKey))
        assertFalse(encoded.contains(settings.endpoint))
        assertEquals("customized", migrated.id)
    }

    @Test fun newRemoteModelsGetDetachedNormalDefaultsAndUniqueLocalIdentity() {
        val one = CatalogModelParameters.newModel(RemoteModel("one", "One"))
        val two = CatalogModelParameters.newModel(RemoteModel("two", "Two"))
        assertEquals("One", one.displayName)
        assertEquals(ProviderRequestOptions(), one.settings.requestOptions)
        assertFalse(one.settings.batteryEnabled)
        assertFalse(one.settings.deviceStatusEnabled)
        assertNotEquals(one.settings.localIdentity, two.settings.localIdentity)
    }

    @Test fun malformedRecordCannotSilentlyBecomeDefaults() {
        assertThrows(Exception::class.java) { CatalogModelParametersCodec.decode("{}") }
        val valid = CatalogModelParametersCodec.encode(CatalogModelParameters())
        assertThrows(Exception::class.java) {
            CatalogModelParametersCodec.decode(valid.replace("\"battery\":false", "\"battery\":\"false\""))
        }
    }
}
