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
        val expected = 4L + tools.sumOf { block ->
            app.daiyu.android.domain.context.HarnessTokenEstimate.toolCall(block.invocation.name, block.invocation.arguments) +
                8L + TokenTailCompression.textTokens(block.result!!)
        }
        assertEquals(expected, TokenTailCompression.messageTokens(row))
        assertEquals(expected, TokenTailCompression.messageTokens(row.copy(protocolItems = listOf(
            JSONObject().put("type", "reasoning").put("encrypted_content", "X".repeat(20000)).toString()))))
    }

    @Test fun completeToolDefinitionsIncludeTheirArrayAndSchemaFraming() {
        val tool = ToolDefinition("read", "description", """{"type":"object","properties":{}}""")
        val definitions = JSONArray().put(JSONObject().put("name", tool.name).put("description", tool.description)
            .put("parameters", JSONObject(tool.parameters)))
        assertEquals(0L, TokenTailCompression.toolTokens(emptyList()))
        assertEquals(4L + TokenTailCompression.textTokens(definitions.toString()), TokenTailCompression.toolTokens(listOf(tool)))
    }

    @Test fun textUsesHarnessUtf16DensityAndCeiling() {
        assertEquals(0L, TokenTailCompression.textTokens(""))
        assertEquals(1L, TokenTailCompression.textTokens("中"))
        assertEquals(1L, TokenTailCompression.textTokens("\uD840\uDC00"))
        assertEquals(2L, TokenTailCompression.textTokens("😀😀😀😀"))
        assertEquals(1L, TokenTailCompression.textTokens("！"))
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
            assertEquals(baseline + 4L + TokenTailCompression.textTokens(text), TokenTailCompression.messageTokens(replayed))
            assertEquals(baseline, TokenTailCompression.messageTokens(replayed.copy(status = RunStatus.INTERRUPTED)))
        }
        val opaque = JSONObject().put("type", "reasoning").put("encrypted_content", "X".repeat(20000))
        assertEquals(baseline, TokenTailCompression.messageTokens(row.copy(protocolItems = listOf(opaque.toString()),
            reportedReasoning = listOf(ReportedReasoning("ui-only", text)))))
        val redacted = JSONObject().put("role", "assistant").put("content", JSONArray().put(
            JSONObject().put("type", "redacted_thinking").put("data", "X".repeat(20000))))
        assertEquals(baseline, TokenTailCompression.messageTokens(row.copy(protocolItems = listOf(redacted.toString()))))
    }

    @Test fun sendPreparationUsesTheFrozenPolicyForHistoricalImages() {
        val owner = MessageImageOwner("c", "u")
        val image = MessageImageReference(owner, "00000000-0000-0000-0000-000000000001", "image/jpeg",
            385198, "a".repeat(64), 1216, 2640)
        val row = Message(MessageId("u"), ConversationId("c"), RunId("r"), "user",
            listOf(MessageBlock.Image(image)), RunStatus.COMPLETED)
        val policy = app.daiyu.android.domain.context.CompressionPolicy(
            contextTokens = 272000, reserveTokens = 4096, tokenTail = true)
        val local = estimateSendContextTokens(policy, listOf(row))
        assertEquals(4L + TokenTailCompression.imageReferenceTokens(image), local)
        assertTrue(policy.fitsSendBudget(local))
        val legacy = policy.copy(tokenTail = false)
        assertEquals(385214L, estimateSendContextTokens(legacy, listOf(row)))
        assertFalse(legacy.fitsSendBudget(estimateSendContextTokens(legacy, listOf(row))))
    }

    @Test fun canonicalHarnessGoldensKeepToolsAndResultsOnTheirOwnMessageBoundaries() {
        val row = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant",
            listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("c1"), "f", "{}"), "ok")), RunStatus.COMPLETED)
        assertEquals(19L, TokenTailCompression.messageTokens(row))
        val tools = listOf(ToolDefinition("f", "", """{"type":"object","properties":{}}"""))
        assertEquals(24L, TokenTailCompression.toolTokens(tools))
        val system = row.copy(id = MessageId("s"), role = "system", blocks = listOf(MessageBlock.Text("s", "abcd")))
        assertEquals(48L, TokenTailCompression.historyTokens(listOf(system, row)) + TokenTailCompression.toolTokens(tools))
        val image = MessageImageReference(MessageImageOwner("c", "u"), "00000000-0000-0000-0000-000000000001",
            "image/png", 1, "0".repeat(64), 1, 1)
        assertEquals(46L, TokenTailCompression.imageReferenceTokens(image))
        assertEquals(50L, TokenTailCompression.messageTokens(row.copy(role = "user", blocks = listOf(MessageBlock.Image(image)))))
        val reasoning = row.copy(blocks = emptyList(), protocolItems = listOf("""{"type":"reasoning","reasoning_text":"x"}"""))
        assertEquals(9L, TokenTailCompression.messageTokens(reasoning))
    }

}
