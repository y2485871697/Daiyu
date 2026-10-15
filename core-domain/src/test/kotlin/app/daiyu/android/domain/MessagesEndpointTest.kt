package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class MessagesEndpointTest {
    @Test fun baseAndExplicitPathsResolveWithoutProbing() {
        for (address in listOf("https://example.invalid", "https://example.invalid/", "https://example.invalid/v1", "https://example.invalid/v1/"))
            assertEquals("https://example.invalid/v1/messages", messagesEndpoint(address))
        assertEquals("https://example.invalid/gateway/messages", messagesEndpoint("https://example.invalid/gateway/messages/"))
        assertEquals("HTTPS://example.invalid/v1/messages", messagesEndpoint("HTTPS://example.invalid"))
    }
    @Test fun wrongProtocolsUnsafeAndMalformedEndpointsAreRejectedWithoutLeaking() {
        for (address in listOf("http://example.invalid", "https://secret@example.invalid", "https://example.invalid?key=secret", "https://example.invalid/#secret", "https://example.invalid/v1/responses/", "https://example.invalid/v1/chat/completions", "https://example.invalid/v1/%72esponses", "https://example.invalid:65536", "https://example.invalid/%synthetic-secret")) {
            try { messagesEndpoint(address); fail("invalid endpoint accepted") }
            catch (failure: IllegalArgumentException) { assertFalse(failure.message.orEmpty().contains("synthetic-secret")) }
        }
    }
}
