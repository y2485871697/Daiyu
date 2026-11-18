package app.daiyu.android.context

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test

class HarnessRequestPolicyTest {
    private fun snapshot(protocol: ProviderProtocol, body: String = "") = ModelSettings(
        "https://example.invalid/v1", "model", "offline-fixture-only", false, protocol = protocol,
        requestOptions = app.daiyu.android.model.ProviderRequestOptions(customBody = body)).requestSnapshot()

    @Test fun outputReservationUsesTheRoutedCapThenTheAdapterDefault() {
        assertEquals(64000L, HarnessRequestPolicy.outputReservation(snapshot(ProviderProtocol.MESSAGES)))
        assertEquals(0L, HarnessRequestPolicy.outputReservation(snapshot(ProviderProtocol.RESPONSES)))
        assertEquals(0L, HarnessRequestPolicy.outputReservation(snapshot(ProviderProtocol.CHAT_COMPLETIONS)))
        assertEquals(123L, HarnessRequestPolicy.outputReservation(snapshot(ProviderProtocol.RESPONSES, """{"max_output_tokens":123}""")))
        assertEquals(234L, HarnessRequestPolicy.outputReservation(snapshot(ProviderProtocol.MESSAGES, """{"max_tokens":234}""")))
        assertEquals(345L, HarnessRequestPolicy.outputReservation(snapshot(ProviderProtocol.CHAT_COMPLETIONS,
            """{"max_tokens":2,"max_completion_tokens":345}""")))
        assertEquals(456L, HarnessRequestPolicy.outputReservation(snapshot(ProviderProtocol.GEMINI,
            """{"generationConfig":{"maxOutputTokens":456}}""")))
    }

    @Test fun invalidOutputCapsAreNotSilentlyChangedIntoAReservation() {
        for (value in listOf("-1", "1.5", "\"12\"", "null")) {
            assertThrows(IllegalArgumentException::class.java) {
                HarnessRequestPolicy.outputReservation(snapshot(ProviderProtocol.RESPONSES, "{\"max_output_tokens\":$value}"))
            }
        }
    }
}
