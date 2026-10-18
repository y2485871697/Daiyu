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

    @Test fun anthropicVersionRoundTripsThroughCatalog() {
        val options = ProviderRequestOptions(anthropicVersion = "2023-06-01")
        val source = ModelSettings("https://example.invalid", "model", "synthetic-key", false,
            protocol = app.daiyu.android.model.ProviderProtocol.MESSAGES, requestOptions = options)
        val migrated = CatalogModelParameters.fromCurrent(source)
        val decoded = CatalogModelParametersCodec.decode(CatalogModelParametersCodec.encode(migrated.settings))
        assertEquals(options, decoded.requestOptions)
        assertEquals(source, decoded.applyTo(source.copy(requestOptions = ProviderRequestOptions()), source.model))
        val file = File(temporary.root, "anthropic-version-catalog")
        val saved = FileSavedModelCatalogPersistence(file, CatalogModelParametersCodec).register(listOf(migrated))
        val reopened = FileSavedModelCatalogPersistence(file, CatalogModelParametersCodec).current()
        assertEquals(saved, reopened)
        assertEquals(options, reopened!!.models.single().settings.requestOptions)
    }

    @Test fun unavailablePolicyCannotPersistFallbackDefaultsDuringMigration() {
        val file = File(temporary.root, "catalog")
        val store = FileSavedModelCatalogPersistence(file, CatalogModelParametersCodec)
        val fallback = EditingSettings(ModelSettings("https://provider.invalid", "model", "", false),
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

    @Test fun selectionUsesOnlyCurrentConnectionAndSelectedModelPolicy() {
        val current = ModelSettings("https://current.invalid", "a", "current-key", true,
            responsesPath = "/v1/responses", deviceStatusEnabled = false,
            requestOptions = ProviderRequestOptions("{\"temperature\":0.9}"))
        val selected = CatalogModelParameters(batteryEnabled = false, deviceStatusEnabled = true,
            requestOptions = ProviderRequestOptions("{\"temperature\":0.1}"))
        val applied = selected.applyTo(current, "b")
        assertEquals(current.copy(model = "b", batteryEnabled = false, deviceStatusEnabled = true,
            requestOptions = selected.requestOptions), applied)
        assertEquals(current.apiKey, applied.apiKey)
        assertEquals(current.endpoint, applied.endpoint)
        assertEquals(current.protocol, applied.protocol)
        assertEquals(current.responsesPath, applied.responsesPath)
    }

    @Test fun editingPolicyPreservesLocalIdentityAndDisplayName() {
        val existing = CatalogModelParameters.newModel(RemoteModel("a", "My custom label"))
        val current = ModelSettings("https://current.invalid", "a", "current-key", true,
            deviceStatusEnabled = true, requestOptions = ProviderRequestOptions("{\"temperature\":0.1}"))
        val updated = CatalogModelParameters.updated(current, existing)
        assertEquals(existing.settings.localIdentity, updated.settings.localIdentity)
        assertEquals(existing.displayName, updated.displayName)
        assertEquals(current.requestOptions, updated.settings.requestOptions)
        assertTrue(updated.settings.batteryEnabled)
        assertTrue(updated.settings.deviceStatusEnabled)
    }

    @Test fun changedLocalIdentityCannotPassSelectionWithSameWireIdAndRevision() {
        val store = FileSavedModelCatalogPersistence(File(temporary.root, "selection"), CatalogModelParametersCodec)
        val model = CatalogModelParameters.newModel(RemoteModel("a", "A"))
        val ticket = store.register(listOf(model)).selectionFor("a")!!
        val forged = ticket.copy(record = model.copy(settings = CatalogModelParameters()))
        assertThrows(IllegalStateException::class.java) { store.consumeSelection(forged) {} }
        assertEquals(model, store.current()!!.models.single())
    }

    @Test fun malformedRecordCannotSilentlyBecomeDefaults() {
        assertThrows(Exception::class.java) { CatalogModelParametersCodec.decode("{}") }
        val valid = CatalogModelParametersCodec.encode(CatalogModelParameters())
        assertThrows(Exception::class.java) {
            CatalogModelParametersCodec.decode(valid.replace("\"battery\":false", "\"battery\":\"false\""))
        }
    }
}
