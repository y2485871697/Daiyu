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
}
