package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.*
import app.daiyu.android.model.MessagesModel
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test

class ReconnectCapabilityTest {
    @Test fun productionTransportsAdvertiseRequestRetryWithoutPostingBeforeFailure() {
        var connections = 0
        for (protocol in listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)) {
            val settings = ModelSettings("https://example.invalid/v1", "fixture", "unused", false,
                protocol = protocol)
            val model: ModelPort = when (protocol) {
                ProviderProtocol.RESPONSES -> ResponsesModel(settings) { connections++; error("must not connect") }
                ProviderProtocol.MESSAGES -> MessagesModel(settings) { connections++; error("must not connect") }
                else -> error("fixture")
            }
            assertEquals(ReconnectCapability.REQUEST_RETRY, ReconnectCapabilities.forProtocol(protocol))
            val session = model.open(emptyList()) // Logical session only; it does not connect.
            assertTrue(session is RequestRetryModelSession)
            assertEquals(ReconnectCapability.SESSION_UNAVAILABLE, ReconnectCapabilities.forSession(session))
            session.cancel()
        }
        assertEquals(0, connections)
        assertEquals(ReconnectCapability.UNKNOWN_TRANSPORT,
            ReconnectCapabilities.forProtocol(ProviderProtocol.UNKNOWN))
    }

    @Test fun onlyExactSessionContractCanReportCapabilityAndFalseIsNotPromoted() {
        var available = true
        var invoked = 0
        val session = object : ResumableModelSession {
            override val canResume: Boolean get() = available
            override fun resume(sink: (ModelEvent) -> Unit) { invoked++; error("probe must not resume") }
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { invoked++; error("probe must not next") }
            override fun cancel() { invoked++; error("probe must not cancel") }
        }
        assertEquals(ReconnectCapability.TRANSPORT_RESUME, ReconnectCapabilities.forSession(session))
        available = false
        assertEquals(ReconnectCapability.SESSION_UNAVAILABLE, ReconnectCapabilities.forSession(session))
        assertEquals(0, invoked)
    }

    @Test fun providerCapabilityExceptionIsUnavailableWithFixedDiagnostic() {
        val secret = "provider-private-diagnostic"
        val session = object : ResumableModelSession {
            override val canResume: Boolean get() = error(secret)
            override fun resume(sink: (ModelEvent) -> Unit) = error("unused")
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = error("unused")
            override fun cancel() = Unit
        }
        val capability = ReconnectCapabilities.forSession(session)
        assertEquals(ReconnectCapability.SESSION_UNAVAILABLE, capability)
        assertFalse(ReconnectUnavailableException(capability).message!!.contains(secret))
    }
}
