package app.daiyu.android.model

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.model.sessiongateway.SessionGateway
import app.daiyu.android.model.sessiongateway.SessionGatewayContext
import app.daiyu.android.model.sessiongateway.SessionGatewayRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class SessionGatewayPolicyTest {
    private fun context(id: String = "real-conversation") = SessionGatewayContext(ConversationId(id))

    @Test fun frozenDefaultsAndFiveFieldCodecRoundTrip() {
        val rule = SessionGatewayRule()
        assertEquals("^gpt-.*$", rule.modelPattern)
        assertEquals("/v1/responses", rule.pathPattern)
        assertEquals("继承全局默认", rule.retention)
        assertEquals("gjson", rule.keySource)
        assertEquals("prompt_cache_key", rule.keyField)
        val edited = SessionGatewayRule("relay/.*", "custom", "unverified-retention", "unverified-source", "literal.key")
        assertEquals(edited, SessionGatewayRule.decode(SessionGatewayRule.encode(edited)))
        assertEquals(rule, SessionGatewayRule.decode(""))
        assertEquals(rule, SessionGatewayRule.decode("not-json"))
        assertEquals(rule, SessionGatewayRule.decode("""{"future":true}"""))
        assertNull(context().project)
    }

    @Test fun modelIsFullMatchAndPathSeesCompleteUrlAndBadRegexNeverMatches() {
        val rule = SessionGatewayRule(modelPattern = "gpt-5", pathPattern = "gateway\\.invalid.*responses\\?route=yes")
        assertTrue(rule.matches("gpt-5", "https://gateway.invalid/v1/responses?route=yes"))
        assertFalse(rule.matches("relay/gpt-5", "https://gateway.invalid/v1/responses?route=yes"))
        assertFalse(rule.copy(modelPattern = "[").matches("gpt-5", "https://gateway.invalid/v1/responses?route=yes"))
        assertFalse(rule.copy(pathPattern = "[").matches("gpt-5", "https://gateway.invalid/v1/responses?route=yes"))
    }

    @Test fun multilineRulesMatchAnyNonemptyValidLineWithoutChangingSingleRuleSemantics() {
        val rule = SessionGatewayRule(modelPattern = "[\n^gpt-.*$\n^relay-.*$", pathPattern = "/v1/responses\n/relay/responses")
        assertTrue(rule.matches("relay-one", "https://gateway.invalid/relay/responses"))
        assertTrue(rule.matches("gpt-one", "https://gateway.invalid/v1/responses"))
        assertFalse(rule.matches("other", "https://gateway.invalid/v1/responses"))
        assertFalse(rule.copy(pathPattern = "\n").matches("gpt-one", "https://gateway.invalid/v1/responses"))
    }

    @Test fun keysAreLiteralAndRuntimeFieldsAreRejectedWithoutRepairingIntoProtocolOverrides() {
        for (key in listOf("model", "input", "messages", "tools", "stream", "store", "instructions", "system",
            "max_tokens", "max_output_tokens", "previous_response_id", "conversation", "tool_choice", "include", "metadata")) {
            assertThrows(IllegalArgumentException::class.java) { SessionGatewayRule(keyField = key).validate() }
        }
        for (key in listOf("", "x y", "x\n", "x".repeat(81), "array[0]")) {
            assertEquals("prompt_cache_key", SessionGatewayRule(keyField = key).safeKeyField())
        }
        val request = JSONObject().put("model", "gpt-5")
        SessionGateway.applyResponses(request, "https://gateway.invalid/v1/responses",
            SessionGatewayRule(keyField = "literal.key"), context())
        assertEquals("real-conversation", request.getString("literal.key"))
        assertFalse(request.has("literal"))
    }

    @Test fun oldThreeKeyRequestOptionsDecodeAndNewCodecPreservesOpaquePolicy() {
        val old = """{"custom_body":"{}","speed_mode":null,"codex":false}"""
        assertEquals(ProviderRequestOptions(customBody = "{}"), ProviderRequestOptions.decode(old))
        val rule = SessionGatewayRule(retention = "unknown TTL", keySource = "unknown-source")
        val options = ProviderRequestOptions("{}", GptSpeedMode.FAST, false, rule)
        assertEquals(options, ProviderRequestOptions.decode(options.encode()))
        val migrated = JSONObject(options.encode())
        migrated.getJSONObject("session_gateway").put("retention", JSONObject.NULL)
        assertThrows(IllegalStateException::class.java) { ProviderRequestOptions.decode(migrated.toString()) }
        assertThrows(IllegalStateException::class.java) {
            ProviderRequestOptions.decode(JSONObject(old).put("unknown", true).toString())
        }
        options.validate(ProviderProtocol.RESPONSES) // unknown semantics remain editable/persistable
        val settings = app.daiyu.android.storage.ModelSettings("https://gateway.invalid/v1/responses", "gpt-5", "synthetic-key", false,
            requestOptions = options)
        val snapshot = ProviderSnapshot.capture(settings)
        assertEquals(rule, snapshot.requestOptions.sessionGateway)
        assertEquals(options, snapshot.asModelSettings().requestOptions)
        assertNotEquals(snapshot, ProviderSnapshot.capture(settings.copy(requestOptions = options.copy(sessionGateway = SessionGatewayRule()))))
        assertNotNull(rule.unsupportedSemantics())
        val request = JSONObject().put("model", "gpt-5").put("store", false)
        assertThrows(IllegalArgumentException::class.java) {
            SessionGateway.applyResponses(request, "https://gateway.invalid/v1/responses", rule, context())
        }
        assertFalse(request.has("prompt_cache_key")); assertFalse(request.has("project"))
        assertFalse(request.has("ttl")); assertEquals(false, request.get("store"))
    }

    @Test fun noContextDoesNotInventIdentityAndCodexBypassIsExact() {
        val body = JSONObject().put("model", "not-gpt")
        SessionGateway.applyResponses(body, "https://gateway.invalid/v1/responses", SessionGatewayRule(), null)
        assertEquals(1, body.length()); assertTrue(SessionGateway.headers("https://opencode.ai", null).isEmpty())
        for (url in listOf("https://chatgpt.com.evil/backend-api/codex/responses",
            "https://chatgpt.com/backend-api/codex-evil/responses", "https://gateway.invalid/v1/responses")) {
            val request = JSONObject().put("model", "not-gpt")
            SessionGateway.applyResponses(request, url, SessionGatewayRule(), context())
            assertFalse(request.has("prompt_cache_key"))
        }
        SessionGateway.applyResponses(body, "https://chatgpt.com/backend-api/codex/responses", SessionGatewayRule(), context())
        assertEquals("real-conversation", body.getString("prompt_cache_key"))
    }

    @Test fun runtimeHeadersKeepConversationAndOpenCodeOnlyUsesExactHost() {
        val first = SessionGateway.headers("https://opencode.ai/v1/responses", context())
        assertEquals("real-conversation", first["session-id"])
        assertEquals("real-conversation", first["thread-id"])
        assertEquals(UUID.nameUUIDFromBytes("real-conversation".toByteArray(Charsets.UTF_8)).toString(), first["x-opencode-session"])
        assertEquals(first, SessionGateway.headers("https://opencode.ai/v1/responses", context()))
        assertNotEquals(first, SessionGateway.headers("https://opencode.ai/v1/responses", context("another")))
        for (url in listOf("https://sub.opencode.ai/v1", "https://opencode.ai.evil/v1", "https://example.invalid/?opencode.ai")) {
            assertFalse(SessionGateway.headers(url, context()).containsKey("x-opencode-session"))
        }
        assertThrows(IllegalArgumentException::class.java) { SessionGateway.headers("https://gateway.invalid", context("bad\r\nid")) }
    }

    @Test fun anthropicProtocolConstantsAndCallerOwnedMetadataRemainFrozen() {
        val request = JSONObject().put("messages", JSONArray().put("unchanged"))
        SessionGateway.applyAnthropic(request, context())
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("eta-anthropic-session-v1\u0000real-conversation".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("eta-session-$expected", request.getJSONObject("metadata").getString("user_id"))
        val original = request.toString()
        SessionGateway.applyAnthropic(request, context())
        assertEquals(original, request.toString())
        assertEquals("unchanged", request.getJSONArray("messages").getString(0))
        for (raw in listOf("null", "[]", "\"caller\"", "{\"user_id\":null}", "{\"user_id\":\"\"}", "{\"user_id\":\"explicit\"}")) {
            val explicit = JSONObject("{\"metadata\":$raw}")
            val before = explicit.toString()
            SessionGateway.applyAnthropic(explicit, context())
            assertEquals(before, explicit.toString())
        }
        val extra = JSONObject("""{"metadata":{"custom":"retained"}}""")
        SessionGateway.applyAnthropic(extra, context())
        assertEquals("retained", extra.getJSONObject("metadata").getString("custom"))
        val other = JSONObject(); SessionGateway.applyAnthropic(other, context("another"))
        assertNotEquals(request.getJSONObject("metadata").getString("user_id"), other.getJSONObject("metadata").getString("user_id"))
        val absent = JSONObject(); SessionGateway.applyAnthropic(absent, null)
        assertFalse(absent.has("metadata"))
    }
}
