package app.daiyu.android.model

import app.daiyu.android.domain.ReadOnlyTool
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test

class ProviderSnapshotContractTest {
    private fun settings() = ModelSettings("https://example.invalid/gateway", "fixture-model", "synthetic-key", true, "/responses")

    @Test fun snapshotRetainsFieldsAndImmutableToolSet() {
        val original = settings()
        val snapshot = original.requestSnapshot()
        val later = original.copy(model="later-model", apiKey="replacement", batteryEnabled=false, deviceStatusEnabled=true)
        assertEquals(original, snapshot.asModelSettings())
        assertNotEquals(later, snapshot.asModelSettings())
        assertEquals(setOf(ReadOnlyTool.BATTERY), snapshot.tools)
        try {
            (snapshot.tools as MutableSet<ReadOnlyTool>).clear()
            fail("tools must remain immutable")
        } catch (_: UnsupportedOperationException) {}
        assertFalse(snapshot.toString().contains(original.apiKey))
    }

    @Test fun settingsAndSnapshotConversionRedactCredentialInDiagnostics() {
        val original = settings()
        assertFalse(original.toString().contains(original.apiKey))
        assertFalse(original.requestSnapshot().asModelSettings().toString().contains(original.apiKey))
        assertFalse(original.copy(model="later-model").toString().contains(original.apiKey))
        assertEquals("synthetic-key", original.apiKey)
    }

    @Test fun unknownProtocolsCannotFallBackToResponses() {
        for (wire in listOf("messages", "anthropic-messages", "unknown", "")) {
            try {
                ModelFactory.create(settings(), wire)
                fail("unsupported protocol must fail closed")
            } catch (_: UnsupportedProviderException) {}
        }
        try {
            ModelFactory.create(ProviderSnapshot.capture(settings(), ProviderProtocol.UNKNOWN))
            fail("typed unknown must fail closed")
        } catch (_: UnsupportedProviderException) {}
    }

    @Test fun missingCredentialsAndMalformedEndpointsFailBeforeConstruction() {
        try {
            ModelFactory.create(settings().copy(apiKey=""))
            fail("credential must be explicit")
        } catch (_: MissingProviderCredentialException) {}
        for (endpoint in listOf("http://example.invalid", "https://user:secret@example.invalid", "https://example.invalid/?key=secret")) {
            try {
                ModelFactory.create(settings().copy(endpoint=endpoint))
                fail("unsafe endpoint must be rejected")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun supportedFactoryConstructionHasNoTransportSideEffects() {
        assertTrue(ModelFactory.create(settings()) is ResponsesModel)
        assertTrue(ModelFactory.create(settings(), " RESPONSES ") is ResponsesModel)
        assertTrue(ModelFactory.create(settings().requestSnapshot()) is ResponsesModel)
    }
}
