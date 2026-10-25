package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnthropicVersionOptionsTest {
    @Test fun validVersionIsRetainedForOtherEndpointsAndUsesStrictCalendarDate() {
        for (version in listOf("2023-06-01", "2024-02-29")) {
            val options = ProviderRequestOptions(anthropicVersion = version)
            options.validate(ProviderProtocol.MESSAGES)
            for (protocol in ProviderProtocol.entries.filter { it != ProviderProtocol.MESSAGES }) {
                options.validate(protocol)
                // An empty wire body is still invalid; retaining an inactive header preference does not synthesize history.
                rejected { options.apply(JSONObject(), protocol) }
            }
        }
        for (version in listOf("", " ", "2023-6-01", "2023-02-29", "2024-04-31", "0000-01-01",
            "2023-06-01\r\nx-injected: true", "2023-06-01\n", " 2023-06-01", "２０２３-０６-０１")) {
            rejected { ProviderRequestOptions(anthropicVersion = version).validate(ProviderProtocol.MESSAGES) }
        }
    }

    @Test fun codecRetainsLegacyThreeAndFourKeysAndOptionalVersion() {
        val legacy = """{"custom_body":"","speed_mode":null,"codex":false}"""
        assertEquals(ProviderRequestOptions(), ProviderRequestOptions.decode(legacy))
        val defaults = ProviderRequestOptions().encode()
        assertFalse(JSONObject(defaults).has("anthropic_version"))
        assertEquals(ProviderRequestOptions(), ProviderRequestOptions.decode(defaults))
        for (raw in listOf(legacy, defaults)) {
            val versioned = JSONObject(raw).put("anthropic_version", "2024-02-29").toString()
            val decoded = ProviderRequestOptions.decode(versioned)
            assertEquals("2024-02-29", decoded.anthropicVersion)
            assertEquals(decoded, ProviderRequestOptions.decode(decoded.encode()))
        }
        assertEquals(ProviderRequestOptions(), ProviderRequestOptions.decode(null))
    }

    @Test fun codecRejectsWrongTypesUnknownAndMissingKeys() {
        for (value in listOf<Any>(JSONObject.NULL, 20230601, true, JSONArray(), JSONObject())) {
            badEncoding(JSONObject(ProviderRequestOptions().encode()).put("anthropic_version", value))
        }
        badEncoding(JSONObject(ProviderRequestOptions().encode()).put("unexpected", "value"))
        badEncoding(JSONObject(ProviderRequestOptions(anthropicVersion = "2023-06-01").encode()).apply { remove("codex") })
    }

    @Test fun versionNeverBecomesARequestBodyFieldAndRetainsBodyMerge() {
        val base = JSONObject().put("model", "claude-fixture").put("messages", JSONArray())
            .put("stream", true).put("max_tokens", 4096)
        val versionOnly = ProviderRequestOptions(anthropicVersion = "2023-06-01").apply(base, ProviderProtocol.MESSAGES)
        assertEquals(base.toString(), versionOnly.toString())
        val merged = ProviderRequestOptions("""{"max_tokens":99}""", anthropicVersion = "2024-02-29")
            .apply(base, ProviderProtocol.MESSAGES)
        assertEquals(99, merged.getInt("max_tokens"))
        assertFalse(merged.has("anthropic_version"))
        assertFalse(merged.has("anthropic-version"))
        assertEquals(4096, base.getInt("max_tokens"))
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("Invalid Anthropic version must be rejected") } catch (_: IllegalArgumentException) {}
    }

    private fun badEncoding(value: JSONObject) {
        try { ProviderRequestOptions.decode(value.toString()); fail("Invalid codec input must be rejected") }
        catch (_: IllegalStateException) {}
    }
}
