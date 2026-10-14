package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class ResponsesEndpointTest {
    @Test fun baseAndFullAddressesResolveWithoutProbing() {
        for (address in listOf("https://example.invalid", "https://example.invalid/"))
            assertEquals("https://example.invalid/v1/responses", responsesEndpoint(address))
        for (address in listOf("https://example.invalid/v1", "https://example.invalid/v1/"))
            assertEquals("https://example.invalid/v1/responses", responsesEndpoint(address))
        for (address in listOf("https://example.invalid/v1/responses", "https://example.invalid/custom/responses", "https://example.invalid/custom"))
            assertEquals(address, responsesEndpoint(address))
        assertEquals("https://example.invalid/api/responses", responsesEndpoint("https://example.invalid/api/", "/responses"))
        assertEquals("https://example.invalid/responses", responsesEndpoint("https://example.invalid", "/responses"))
    }
    @Test fun unsafeAddressesAndWrongProtocolAreRejected() {
        for (address in listOf("http://example.invalid", "https://key@example.invalid", "https://example.invalid?key=secret",
            "https://example.invalid/#key", "https://example.invalid/v1/chat/completions")) {
            try { responsesEndpoint(address);fail(address) } catch (_: IllegalArgumentException) { }
        }
        for (path in listOf("responses", "//other.invalid/responses", "/responses?key=secret", "/responses#key", "https://other.invalid/responses")) {
            try { responsesEndpoint("https://example.invalid", path);fail(path) } catch (_: IllegalArgumentException) { }
        }
    }
}
