package app.daiyu.android.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TerminalToolResultProjectionTest {
    private fun receipt(text: String = "源码 α\n") = JSONObject().put("stdout", text)
        .put("stdout_base64", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
        .put("next_offset", 17).put("has_more", true).put("execution", "unknown").put("exit_code", JSONObject.NULL).toString()
    private val protocols = listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES,
        ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)

    private fun request(protocol: ProviderProtocol, raw: String, name: String = "terminal_exec"): JSONObject {
        val rows = JSONArray()
        return when (protocol) {
            ProviderProtocol.RESPONSES -> JSONObject().put("input", rows
                .put(JSONObject().put("type", "function_call").put("call_id", "call-1").put("name", name).put("arguments", "{}"))
                .put(JSONObject().put("type", "function_call_output").put("call_id", "call-1").put("output", raw)))
            ProviderProtocol.MESSAGES -> JSONObject().put("messages", rows
                .put(JSONObject().put("role", "assistant").put("content", JSONArray().put(JSONObject()
                    .put("type", "tool_use").put("id", "call-1").put("name", name).put("input", JSONObject()))))
                .put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject()
                    .put("type", "tool_result").put("tool_use_id", "call-1").put("is_error", true).put("content", raw)))))
            ProviderProtocol.CHAT_COMPLETIONS -> JSONObject().put("messages", rows
                .put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", "call-1").put("type", "function").put("function", JSONObject().put("name", name).put("arguments", "{}")))))
                .put(JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", raw)))
            ProviderProtocol.GEMINI -> JSONObject().put("contents", rows
                .put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject()
                    .put("functionCall", JSONObject().put("id", "call-1").put("name", name).put("args", JSONObject())))))
                .put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject()
                    .put("functionResponse", JSONObject().put("id", "call-1").put("name", name)
                        .put("response", JSONObject().put("output", raw)))))))
            else -> error("fixture protocol")
        }
    }

    private fun output(body: JSONObject, protocol: ProviderProtocol): String = when (protocol) {
        ProviderProtocol.RESPONSES -> body.getJSONArray("input").getJSONObject(1).getString("output")
        ProviderProtocol.MESSAGES -> body.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("content")
        ProviderProtocol.CHAT_COMPLETIONS -> body.getJSONArray("messages").getJSONObject(1).getString("content")
        ProviderProtocol.GEMINI -> body.getJSONArray("contents").getJSONObject(1).getJSONArray("parts").getJSONObject(0)
            .getJSONObject("functionResponse").getJSONObject("response").getString("output")
        else -> error("fixture protocol")
    }

    @Test fun allProtocolsDropOnlyLosslessTerminalDuplicateWithoutMutatingCanonicalInput() {
        for (protocol in protocols) for (name in listOf("terminal_exec", "terminal_read", "terminal_job_start", "terminal_job_read", "terminal_pty_step", "terminal_pty_read")) {
            val raw = receipt()
            val original = request(protocol, raw, name).put("metadata", JSONObject().put("stdout_base64", "not a receipt"))
            val before = original.toString()
            val projected = TerminalToolResultProjection.request(original, protocol)
            assertNotSame(protocol.name, original, projected)
            assertEquals(before, original.toString())
            assertEquals(raw, output(original, protocol))
            val result = JSONObject(output(projected, protocol))
            assertFalse(result.has("stdout_base64"))
            assertEquals("源码 α\n", result.getString("stdout"))
            assertEquals(17, result.getInt("next_offset"))
            assertTrue(result.getBoolean("has_more"))
            assertEquals("unknown", result.getString("execution"))
            assertTrue(result.isNull("exit_code"))
            assertSame(projected, TerminalToolResultProjection.request(projected, protocol))
            assertEquals("not a receipt", projected.getJSONObject("metadata").getString("stdout_base64"))
        }
    }

    @Test fun unrelatedToolsAndNonReceiptValuesRemainByteForByteIntact() {
        val binary = JSONObject(receipt()).put("stdout_base64", "/w==").toString()
        val noText = JSONObject(receipt()).apply { remove("stdout") }.toString()
        for (raw in listOf("not json", "{broken", "[]", binary, noText,
            JSONObject(receipt()).put("stdout", JSONObject.NULL).toString(),
            JSONObject(receipt()).put("stdout_base64", false).toString(),
            "{\"stdout\":\"\",\"stdout\":\"other\",\"stdout_base64\":\"\"}")) {
            assertEquals(raw, TerminalToolResultProjection.output("terminal_exec", raw))
        }
        val raw = receipt()
        for (protocol in protocols) {
            val body = request(protocol, raw, "read_file")
            assertSame(body, TerminalToolResultProjection.request(body, protocol))
            assertEquals(raw, output(body, protocol))
        }
        assertEquals(raw, TerminalToolResultProjection.output(null, raw))
    }

    @Test fun responseCallIdsAreMatchedAndConflictingOrMissingCallsAreNotGuessed() {
        val raw = receipt()
        val body = request(ProviderProtocol.RESPONSES, raw)
        val rows = body.getJSONArray("input")
        rows.getJSONObject(1).put("call_id", "missing")
        assertSame(body, TerminalToolResultProjection.request(body, ProviderProtocol.RESPONSES))
        rows.getJSONObject(1).put("call_id", "call-1")
        body.put("input", JSONArray().put(rows.get(0))
            .put(JSONObject().put("type", "function_call").put("call_id", "call-1").put("name", "read_file"))
            .put(rows.get(1)))
        assertSame(body, TerminalToolResultProjection.request(body, ProviderProtocol.RESPONSES))
    }

    @Test fun messagesTextBlocksKeepImagesAndErrorStatus() {
        val body = request(ProviderProtocol.MESSAGES, receipt())
        val result = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0)
        val image = JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("data", "image-data"))
        result.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", receipt())).put(image))
        val before = body.toString()
        val projected = TerminalToolResultProjection.request(body, ProviderProtocol.MESSAGES)
        val clean = projected.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertTrue(clean.getBoolean("is_error"))
        assertFalse(JSONObject(clean.getJSONArray("content").getJSONObject(0).getString("text")).has("stdout_base64"))
        assertEquals(image.toString(), clean.getJSONArray("content").getJSONObject(1).toString())
        assertEquals(before, body.toString())
    }

    @Test fun geminiMismatchedFunctionNameAndUserTextAreNotRewritten() {
        val raw = receipt()
        val body = request(ProviderProtocol.GEMINI, raw)
        body.getJSONArray("contents").getJSONObject(1).getJSONArray("parts").getJSONObject(0)
            .getJSONObject("functionResponse").put("name", "read_file")
        assertSame(body, TerminalToolResultProjection.request(body, ProviderProtocol.GEMINI))
        val user = JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", raw)))
        assertSame(user, TerminalToolResultProjection.request(user, ProviderProtocol.CHAT_COMPLETIONS))
    }

    @Test fun largeSourceOutputKeepsReadableTextButRemovesTheRedundantPayload() {
        val raw = receipt("fun source() = \"内容\"\n".repeat(2000))
        val clean = TerminalToolResultProjection.output("terminal_exec", raw)
        assertEquals(JSONObject(raw).getString("stdout"), JSONObject(clean).getString("stdout"))
        assertTrue(clean.toByteArray().size * 2 < raw.toByteArray().size)
    }
}
