package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class ModelFailureProtocolNeutralTest {
    @Test fun sharedErrorsDoNotPrescribeAnyProviderWireProtocol() {
        for (failure in listOf(ModelFailure.ENDPOINT, ModelFailure.REQUEST, ModelFailure.PROTOCOL)) {
            for (name in listOf("Responses", "Messages", "Chat Completions", "Gemini"))
                assertFalse("$failure must be protocol-neutral", failure.userMessage.contains(name))
            assertTrue(failure.userMessage.isNotBlank())
        }
        assertTrue(ModelFailure.PROTOCOL.userMessage.contains("协议"))
        assertTrue(ModelFailure.ENDPOINT.userMessage.contains("请求路径"))
    }
}
