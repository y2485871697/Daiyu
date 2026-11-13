package app.daiyu.android.context

import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TokenTailEstimateTest {
    @Test fun batchedCallsUseOneArrayAndResultsRemainVerbatim() {
        val tools = listOf("one", "two").map { id ->
            MessageBlock.Tool(ToolInvocation(ToolCallId(id), "read", "{\"path\":\"/$id\"}"), "output-$id")
        }
        val row = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", tools, RunStatus.COMPLETED)
        val calls = JSONArray()
        tools.forEach { block -> calls.put(JSONObject().put("id", block.invocation.id.value).put("type", "function")
            .put("function", JSONObject().put("name", "read").put("arguments", block.invocation.arguments))) }
        val expected = 3 + TokenTailCompression.textTokens(calls.toString()) + tools.sumOf { block ->
            3 + TokenTailCompression.textTokens(block.invocation.id.value) + TokenTailCompression.textTokens(block.result!!)
        }
        assertEquals(expected, TokenTailCompression.messageTokens(row))
        assertEquals(expected, TokenTailCompression.messageTokens(row.copy(protocolItems = listOf(
            JSONObject().put("type", "reasoning").put("encrypted_content", "X".repeat(20000)).toString()))))
    }

    @Test fun completeToolDefinitionsIncludeTheirArrayAndSchemaFraming() {
        val tool = ToolDefinition("read", "description", """{"type":"object","properties":{}}""")
        val definitions = JSONArray().put(JSONObject().put("type", "function").put("function",
            JSONObject().put("name", tool.name).put("description", tool.description)
                .put("parameters", JSONObject(tool.parameters))))
        assertEquals(0L, TokenTailCompression.toolTokens(emptyList()))
        assertEquals(TokenTailCompression.textTokens(definitions.toString()), TokenTailCompression.toolTokens(listOf(tool)))
    }

    @Test fun textUsesOriginalCodePointAndRoundingRules() {
        assertEquals(0L, TokenTailCompression.textTokens(""))
        assertEquals(2L, TokenTailCompression.textTokens("中"))
        assertEquals(2L, TokenTailCompression.textTokens("\uD840\uDC00"))
        assertEquals(1L, TokenTailCompression.textTokens("😀😀😀😀"))
        assertEquals(2L, TokenTailCompression.textTokens("！"))
        assertEquals(85L, TokenTailCompression.imageTokens(1, 1))
        assertEquals(4096L, TokenTailCompression.imageTokens(4096, 4096))
    }
    @Test fun boundaryCountsOnlyReplayedVisibleReasoningAndNeverDuplicatesBody() {
        val text = "visible reasoning".repeat(100)
        val body = "answer"
        val row = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant",
            listOf(MessageBlock.Text("body", body)), RunStatus.COMPLETED)
        val baseline = TokenTailCompression.messageTokens(row)
        val native = JSONObject().put("role", "assistant").put("content", body).put("reasoning_content", text)
        val thinking = JSONObject().put("type", "thinking").put("thinking", text).put("signature", "X".repeat(20000))
        val messages = JSONObject().put("role", "assistant").put("content", JSONArray().put(thinking)
            .put(JSONObject().put("type", "text").put("text", body)))
        val responses = JSONObject().put("type", "reasoning").put("encrypted_content", "X".repeat(20000))
            .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", text)))
        for (raw in listOf(native, messages, responses)) {
            val replayed = row.copy(protocolItems = listOf(raw.toString()))
            assertEquals(baseline + TokenTailCompression.textTokens(text), TokenTailCompression.messageTokens(replayed))
            assertEquals(baseline, TokenTailCompression.messageTokens(replayed.copy(status = RunStatus.INTERRUPTED)))
        }
        val opaque = JSONObject().put("type", "reasoning").put("encrypted_content", "X".repeat(20000))
        assertEquals(baseline, TokenTailCompression.messageTokens(row.copy(protocolItems = listOf(opaque.toString()),
            reportedReasoning = listOf(ReportedReasoning("ui-only", text)))))
        val redacted = JSONObject().put("role", "assistant").put("content", JSONArray().put(
            JSONObject().put("type", "redacted_thinking").put("data", "X".repeat(20000))))
        assertEquals(baseline, TokenTailCompression.messageTokens(row.copy(protocolItems = listOf(redacted.toString()))))
    }

}
