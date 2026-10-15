package app.daiyu.android.model

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.ReadOnlyTool
import app.daiyu.android.storage.ModelSettings

@Suppress("DEPRECATION")
class ProviderSnapshotTest : InstrumentationTestCase() {
    private fun settings(
        key: String = "synthetic-key",
        battery: Boolean = true,
        deviceStatus: Boolean = false
    ) = ModelSettings(
        endpoint = "https://example.invalid/gateway",
        model = "fixture-model",
        apiKey = key,
        batteryEnabled = battery,
        responsesPath = "/responses",
        deviceStatusEnabled = deviceStatus
    )

    fun testCaptureCopiesRequestFieldsAndDoesNotExposeCredentialInText() {
        val source = settings()
        val snapshot = source.requestSnapshot()

        assertEquals(ProviderProtocol.RESPONSES, snapshot.protocol)
        assertEquals(source.endpoint, snapshot.endpoint)
        assertEquals(source.model, snapshot.model)
        assertEquals(source.apiKey, snapshot.apiKey)
        assertEquals(source.enabledTools(), snapshot.tools)
        try {
            @Suppress("UNCHECKED_CAST")
            val mutableTools = snapshot.tools as MutableSet<ReadOnlyTool>
            mutableTools.clear()
            fail("snapshot tools must be immutable")
        } catch (expected: UnsupportedOperationException) {
            // expected: an in-flight request cannot have its tool set rewritten
        }
        assertTrue(!snapshot.toString().contains(source.apiKey))
        assertEquals(source, snapshot.asModelSettings())
    }

    fun testUnknownProtocolRemainsExplicitForFactory() {
        val snapshot = ProviderSnapshot.capture(settings(), ProviderProtocol.UNKNOWN)
        try {
            ModelFactory.create(snapshot)
            fail("unknown protocol must fail closed")
        } catch (expected: UnsupportedProviderException) {
            assertTrue(expected.message!!.contains("unknown"))
        }
    }

    fun testMissingCredentialFailsBeforePortConstruction() {
        try {
            ProviderSnapshot.capture(settings(key = ""))
            fail("missing credential must be rejected")
        } catch (expected: MissingProviderCredentialException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }
}
