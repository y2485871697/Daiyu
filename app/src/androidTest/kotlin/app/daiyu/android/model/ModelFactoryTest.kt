package app.daiyu.android.model

import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.storage.CredentialUnavailableException
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.testContext
import java.security.KeyStore
import java.util.Collections
import java.util.UUID

@Suppress("DEPRECATION")
class ModelFactoryTest : InstrumentationTestCase() {
    private fun settings(
        endpoint: String = "https://example.invalid/gateway",
        key: String = "synthetic-key"
    ) = ModelSettings(
        endpoint = endpoint,
        model = "fixture-model",
        apiKey = key,
        batteryEnabled = false,
        responsesPath = "/responses"
    )

    fun testFactoryCreatesImplementedResponsesPortWithoutStartingNetwork() {
        val port = ModelFactory.create(settings())
        assertTrue(port is ResponsesModel)
    }

    fun testFactoryRejectsUnknownProtocolBeforeCreatingPort() {
        try {
            ModelFactory.create(settings(), "anthropic-messages")
            fail("unknown protocol must fail closed")
        } catch (expected: UnsupportedProviderException) {
            assertTrue(expected.message!!.contains("anthropic-messages"))
        }
    }

    fun testFactoryRejectsMissingCredentialExplicitly() {
        try {
            ModelFactory.create(settings(key = ""))
            fail("missing credential must fail closed")
        } catch (expected: MissingProviderCredentialException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    fun testFactoryRejectsMalformedEndpointBeforePortConstruction() {
        try {
            ModelFactory.create(settings(endpoint = "http://example.invalid/gateway"))
            fail("invalid endpoint must fail closed")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    fun testExplicitKeySaveClearsIsolatedRebindGateBeforeLoadedSnapshotFactoryUse() {
        val prefix = "factory-gate-test-${UUID.randomUUID()}"
        val context = testContext(instrumentation.targetContext, "$prefix-")
        val preferences = context.getSharedPreferences("model-settings", Context.MODE_PRIVATE)
        val store = ModelSettingsStore(context, prefix)
        val gate = "credential-rebind-required"
        try {
            store.save(settings())
            assertTrue(preferences.edit().putBoolean(gate, true).commit())
            val reopened = ModelSettingsStore(context, prefix)
            try {
                ModelFactory.create(reopened.load().requestSnapshot())
                fail("a rebind gate must prevent loading credentials for the factory")
            } catch (expected: CredentialUnavailableException) {
                assertTrue(expected.message!!.isNotBlank())
            }

            val editing = reopened.loadForEditing()
            assertTrue(editing.credentialUnavailable)
            assertEquals("", editing.settings.apiKey)
            try {
                reopened.save(editing.settings)
                fail("saving without an explicit key must not clear the rebind gate")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
            assertTrue(preferences.getBoolean(gate, false))

            val replacement = editing.settings.copy(apiKey = "explicit-replacement-key")
            reopened.save(replacement)
            assertFalse(preferences.contains(gate))
            val loaded = ModelSettingsStore(context, prefix).load()
            assertEquals(replacement, loaded)
            assertTrue(ModelFactory.create(loaded.requestSnapshot()) is ResponsesModel)
        } finally {
            // Both preferences and key aliases belong exclusively to this test invocation.
            try {
                assertTrue(preferences.edit().clear().commit())
            } finally {
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                Collections.list(keys.aliases()).filter { it.startsWith("$prefix.") }
                    .forEach { keys.deleteEntry(it) }
            }
        }
    }
}
