package app.daiyu.android.model

import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderRequestOptionsValidationTest {
    private fun rejected(protocol: ProviderProtocol, body: String, mode: GptSpeedMode? = null) {
        try {
            ProviderRequestOptions(body, mode).validate(protocol)
            fail("invalid option must be rejected during configuration validation")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun explicitTierTypesAreNotCoercedOrRepairedBySpeedSelection() {
        val invalid = listOf<Any>(0, true, JSONObject(), JSONArray(), "", "   ")
        for (value in invalid) {
            val body = JSONObject().put("service_tier", value).toString()
            rejected(ProviderProtocol.RESPONSES, body)
            rejected(ProviderProtocol.RESPONSES, body, GptSpeedMode.FAST)
            rejected(ProviderProtocol.MESSAGES, body)
        }
    }

    @Test fun messagesCapacityPolicyDoesNotUseGptGatewayTiers() {
        for (tier in listOf("default", "priority", "fast", "ultrafast", "custom-gateway-tier")) {
            rejected(ProviderProtocol.MESSAGES, JSONObject().put("service_tier", tier).toString())
        }
        rejected(ProviderProtocol.MESSAGES, """{"service_tier":null}""")
        for (tier in listOf("auto", "standard_only")) {
            val options = ProviderRequestOptions(JSONObject().put("service_tier", tier).toString())
            options.validate(ProviderProtocol.MESSAGES)
            val base = JSONObject().put("model", "claude-fixture").put("messages", JSONArray())
                .put("stream", true).put("max_tokens", 4096)
            assertEquals(tier, options.apply(base, ProviderProtocol.MESSAGES).get("service_tier"))
            assertFalse(base.has("service_tier"))
        }
    }

    @Test fun responsesRetainsGatewayTiersAndNullableOrAbsentPolicy() {
        val base = JSONObject().put("model", "gpt-5").put("input", JSONArray()).put("stream", true)
        assertFalse(ProviderRequestOptions().apply(base, ProviderProtocol.RESPONSES).has("service_tier"))
        for (tier in listOf<Any>("default", "auto", "priority", "fast", "ultrafast", "custom-gateway-tier", JSONObject.NULL)) {
            val options = ProviderRequestOptions(JSONObject().put("service_tier", tier).toString())
            options.validate(ProviderProtocol.RESPONSES)
            assertEquals(tier, options.apply(base, ProviderProtocol.RESPONSES).get("service_tier"))
        }
        assertFalse(base.has("service_tier"))
    }

    @Test fun modelAndMessagesOutputLimitFailAtSettingsValidationNotOnlySend() {
        val responses = ModelSettings("https://example.invalid/v1/responses", "gpt-5", "synthetic-key", false)
        for (body in listOf("""{"model":1}""", """{"model":null}""", """{"model":" "}""",
            JSONObject().put("model", "x".repeat(201)).toString())) {
            try {
                responses.copy(requestOptions = ProviderRequestOptions(body)).validate()
                fail("invalid final model must not be saved")
            } catch (_: IllegalArgumentException) {}
        }
        for (body in listOf("""{"max_tokens":"99"}""", """{"max_tokens":99.5}""",
            """{"max_tokens":true}""", """{"max_tokens":null}""", """{"max_tokens":0}""",
            """{"max_tokens":-1}""", """{"max_tokens":2147483648}""")) {
            rejected(ProviderProtocol.MESSAGES, body)
        }
        ProviderRequestOptions("""{"max_tokens":99}""").validate(ProviderProtocol.MESSAGES)
    }

    @Test fun recursiveMergeAndOpaqueGatewayValueTypesStayUnchanged() {
        val base = JSONObject().put("model", "gpt-5").put("input", JSONArray()).put("stream", true)
            .put("extension", JSONObject().put("retained", true).put("items", JSONArray().put(1)))
        val options = ProviderRequestOptions("""{"extension":{"items":["2"],"number":"123","flag":"true","nullable":null}}""")
        val extension = options.apply(base, ProviderProtocol.RESPONSES).getJSONObject("extension")
        assertEquals(true, extension.get("retained"))
        assertEquals(1, extension.getJSONArray("items").length())
        assertEquals("2", extension.getJSONArray("items").get(0))
        assertEquals("123", extension.get("number"))
        assertEquals("true", extension.get("flag"))
        assertSame(JSONObject.NULL, extension.get("nullable"))
        assertEquals(1, base.getJSONObject("extension").getJSONArray("items").get(0))
    }
}
