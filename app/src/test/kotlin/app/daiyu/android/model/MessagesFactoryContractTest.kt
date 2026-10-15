package app.daiyu.android.model

import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test

class MessagesFactoryContractTest {
    private fun settings() = ModelSettings("https://example.invalid", "fixture-model", "synthetic-key", false, protocol=ProviderProtocol.MESSAGES)
    @Test fun selectedProtocolIsFrozenAndDefaultFactoryConsumesIt() {
        val source=settings();val snapshot=source.requestSnapshot()
        assertEquals(ProviderProtocol.MESSAGES,snapshot.protocol)
        assertEquals(source,snapshot.asModelSettings())
        assertEquals("https://example.invalid/v1/messages",snapshot.asModelSettings().requestEndpoint())
        assertTrue(ModelFactory.create(source) is MessagesModel)
        assertTrue(ModelFactory.create(snapshot) is MessagesModel)
        assertTrue(ModelFactory.create(source.copy(protocol=ProviderProtocol.RESPONSES)," MESSAGES ") is MessagesModel)
        assertTrue(ModelFactory.create(source.copy(protocol=ProviderProtocol.RESPONSES)) is ResponsesModel)
    }
    @Test fun invalidProtocolEndpointAndResponsesPathFailBeforePortConstruction() {
        for (source in listOf(settings().copy(responsesPath="/responses"),settings().copy(endpoint="https://example.invalid/v1/responses"),settings().copy(protocol=ProviderProtocol.UNKNOWN))) {
            try { ModelFactory.create(source);fail("invalid protocol configuration accepted") }
            catch (_:IllegalArgumentException) {}
        }
    }
    @Test fun keyAndToolSetStayBoundToCapturedProtocol() {
        val source=settings().copy(batteryEnabled=true)
        val snapshot=source.requestSnapshot();val changed=source.copy(protocol=ProviderProtocol.RESPONSES,apiKey="replacement",batteryEnabled=false)
        assertNotEquals(changed,snapshot.asModelSettings())
        assertEquals(source,snapshot.asModelSettings())
        assertFalse(snapshot.toString().contains("synthetic-key"))
    }
}
