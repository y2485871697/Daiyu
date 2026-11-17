package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.model.TerminalToolResultProjection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TokenTailBudgetTest {
    private fun message(id: String, text: String, role: String = "assistant", opaque: Boolean = false) =
        Message(MessageId(id), ConversationId("c"), RunId("r"), role,
            listOf(MessageBlock.Text(id, text)), RunStatus.COMPLETED,
            protocolItems = if (opaque) listOf(JSONObject().put("type", "reasoning")
                .put("encrypted_content", "opaque-$id").toString()) else emptyList())

    @Test fun oldOpaqueHistoryCannotKeepAnOversizedUserToLatestTail() {
        val history = listOf(message("old", "old", opaque = true), message("user", "continue", "user"),
            message("large", "source".repeat(10000)), message("latest", "latest complete", opaque = true))
        val before = history.toList()
        val cut = TokenTailCompression.selectStart(history, 1000)
        assertEquals(3, cut)
        assertTrue(TokenTailCompression.isSafeCut(history, cut))
        assertEquals(before, history)
        assertEquals(history.last().protocolItems, history.drop(cut).single().protocolItems)
    }

    @Test fun smallLatestUserContextIsStillKeptWithItsOpaqueSegment() {
        val history = listOf(message("old", "source".repeat(10000), opaque = true),
            message("user", "continue", "user"), message("latest", "answer", opaque = true))
        assertEquals(1, TokenTailCompression.selectStart(history, 1000))
    }

    @Test fun unfinishedToolAndItsLastCompleteProtocolSegmentAreNeverSplit() {
        val pending = message("pending", "").copy(blocks = listOf(
            MessageBlock.Tool(ToolInvocation(ToolCallId("waiting"), "terminal_exec", "{}"), null)))
        val history = listOf(message("old", "old", opaque = true), message("user", "continue", "user"),
            message("complete", "source".repeat(10000), opaque = true), pending)
        val cut = TokenTailCompression.selectStart(history, 1000)
        assertEquals(2, cut)
        assertTrue(TokenTailCompression.isSafeCut(history, cut))
        assertFalse(TokenTailCompression.isSafeCut(history, history.size))
        assertNull((history.last().blocks.single() as MessageBlock.Tool).result)
    }

    @Test fun overflowDoesNotReintroduceTheLargeEarlierUserTail() {
        val history = listOf(message("old", "old", opaque = true), message("user", "continue", "user"),
            message("large", "source".repeat(10000)), message("latest", "latest complete", opaque = true))
        assertEquals(3, TokenTailCompression.selectStart(history, 1000, overflow = true))
    }

    @Test fun opaqueUnitPendingToolsAndLaterSupplementRemainCompleteEvenOverBudget() {
        val completeTool = MessageBlock.Tool(ToolInvocation(ToolCallId("done"), "read", "{}"), "result")
        val responses = listOf(
            JSONObject().put("type", "reasoning").put("encrypted_content", "opaque-signature").toString(),
            JSONObject().put("type", "function_call").put("call_id", "done").put("name", "read").put("arguments", "{}").toString(),
            JSONObject().put("type", "function_call_output").put("call_id", "done").put("output", "result").toString())
        val messages = listOf(
            JSONObject().put("role", "assistant").put("content", org.json.JSONArray()
                .put(JSONObject().put("type", "thinking").put("thinking", "reason").put("signature", "signed-reason"))
                .put(JSONObject().put("type", "tool_use").put("id", "done").put("name", "read").put("input", JSONObject()))).toString(),
            JSONObject().put("role", "user").put("content", org.json.JSONArray().put(JSONObject()
                .put("type", "tool_result").put("tool_use_id", "done").put("content", "result"))).toString())
        for (protocol in listOf(responses, messages)) {
            val complete = message("complete", "").copy(blocks = listOf(completeTool), protocolItems = protocol)
            val pending = message("pending", "").copy(blocks = listOf(
                MessageBlock.Tool(ToolInvocation(ToolCallId("waiting"), "terminal_exec", "{}"), null)))
            val supplement = message("supplement", "keep the entire supplement".repeat(3000), "user")
            val expected = listOf(complete, pending, supplement)
            for (prefix in listOf(emptyList(), listOf(message("old", "old", opaque = true), message("user", "continue", "user")))) {
                val history = prefix + expected
                for (overflow in listOf(false, true)) {
                    val cut = TokenTailCompression.selectStart(history, 1000, overflow = overflow)
                    assertEquals(prefix.size, cut)
                    assertEquals(expected, history.drop(cut))
                    assertEquals(protocol, history[cut].protocolItems)
                }
            }
        }
    }

    @Test fun aRequiredOversizedLatestOpaqueSegmentIsNeverSilentlyTruncated() {
        val latest = message("latest", "source".repeat(10000), opaque = true)
        val history = listOf(message("old", "old", opaque = true), message("user", "continue", "user"), latest)
        assertEquals(listOf(latest), history.drop(TokenTailCompression.selectStart(history, 1000)))
    }

    @Test fun terminalEstimatorUsesTheSameLosslessWireProjectionWithoutChangingReceipt() {
        val text = "源码\n".repeat(2000)
        val raw = JSONObject().put("stdout", text).put("stdout_base64",
            Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))).toString()
        val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "terminal_exec", "{}"), raw)
        val original = message("terminal", "").copy(blocks = listOf(tool))
        val projected = original.copy(blocks = listOf(tool.copy(result = TerminalToolResultProjection.output("terminal_exec", raw))))
        assertEquals(TokenTailCompression.messageTokens(projected), TokenTailCompression.messageTokens(original))
        assertEquals(raw, (original.blocks.single() as MessageBlock.Tool).result)
    }
}
