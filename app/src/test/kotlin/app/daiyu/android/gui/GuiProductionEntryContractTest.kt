package app.daiyu.android.gui

import app.daiyu.android.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuiProductionEntryContractTest {
    private fun rejected(raw: String) {
        try { GuiUserActionRequest.parse(raw); fail("invalid user request accepted") }
        catch (_: IllegalArgumentException) {}
        catch (_: org.json.JSONException) {}
    }
    @Test fun allProductionOperationsParseAndOnlyInputAcceptsFullText() {
        for (operation in GuiOperation.entries) {
            val raw = JSONObject().put("operation", operation.name.lowercase())
            if (operation == GuiOperation.INPUT) raw.put("text", "  full\n\"input\"  ")
            val request = GuiUserActionRequest.parse(raw.toString())
            assertEquals(operation, request.operation)
            assertEquals(if (operation == GuiOperation.INPUT) "  full\n\"input\"  " else null, request.text)
        }
        val complete = "x".repeat(4096)
        assertEquals(complete, GuiUserActionRequest.parse(JSONObject().put("operation", "input").put("text", complete).toString()).text)
        listOf("{}", "{\"operation\":1}", "{\"operation\":\"input\"}",
            "{\"operation\":\"input\",\"text\":null}", "{\"operation\":\"tap\",\"text\":\"extra\"}",
            "{\"operation\":\"tap\",\"x\":10,\"y\":20}", "{\"operation\":\"tap\",\"node_id\":4}",
            "{\"operation\":\"screenshot\"}", "{\"operation\":\"tap\",\"observation_id\":\"forged\"}").forEach(::rejected)
        rejected(JSONObject().put("operation", "input").put("text", complete + "x").toString())
    }

    @Test fun frozenModelProjectionBudgetsCompleteExactEvidenceWithoutChangingDurableUser() {
        val session = GuiRunSession(GuiLeaseGate.Lease(GuiOwner("session", "run")))
        val observation = GuiObservation("approved", "external.app", 7, 2, 100, emptyList(), false)
        val fullInput = "  exact\n\"quoted\"\\参数  " + "x".repeat(4000)
        val action = GuiAction(observation.observationId, GuiOperation.INPUT, 4, fullInput)
        val delivery = GuiConfirmedDelivery(session, observation, action, setOf(action.effect(session.identity)))
        val user = Message(MessageId("user"), ConversationId("conversation"), RunId("run"),
            Message.USER_ROLE, listOf(MessageBlock.Text("user", "  unchanged draft  ")), RunStatus.COMPLETED)
        val projected = GuiConfirmedModelPort.project(user, delivery)
        assertEquals(user.copy(blocks = projected.blocks), projected)
        assertEquals(1, user.blocks.size)
        assertEquals(2, projected.blocks.size)
        assertSame(user.blocks.single(), projected.blocks.first())
        assertEquals(MessageBlock.Text("gui_confirmed_action", delivery.modelContext()), projected.blocks.last())
        assertEquals(fullInput, JSONObject(delivery.actionArgumentsJson()).getString("text"))
        val originalCost = app.daiyu.android.domain.context.estimateTokens(listOf(user))
        val modelCost = app.daiyu.android.domain.context.estimateTokens(listOf(projected))
        assertEquals(originalCost + delivery.modelContext().toByteArray(Charsets.UTF_8).size, modelCost)
        val policy = app.daiyu.android.domain.context.CompressionPolicy(contextTokens = 1024, reserveTokens = 128)
        assertTrue(policy.fitsSendBudget(originalCost))
        assertFalse(policy.fitsSendBudget(modelCost))
        assertFalse(policy.copy(enabled = true).shouldCompress(originalCost))
        assertTrue(policy.copy(enabled = true).shouldCompress(modelCost))
    }

    @Test fun actualModelBoundaryUsesSameDeliveryAndDoesNotRewritePersistedUserOrOtherHistory() {
        val session = GuiRunSession(GuiLeaseGate.Lease(GuiOwner("session", "run")))
        val observation = GuiObservation("approved", "external.app", 7, 2, 100, emptyList(), false)
        val action = GuiAction(observation.observationId, GuiOperation.HOME)
        val delivery = GuiConfirmedDelivery(session, observation, action, setOf(action.effect(session.identity)))
        val user = Message(MessageId("user"), ConversationId("conversation"), RunId("run"),
            Message.USER_ROLE, listOf(MessageBlock.Text("body", "  unchanged original draft  ")), RunStatus.COMPLETED)
        val earlier = user.copy(id = MessageId("earlier"), runId = RunId("previous"))
        val seen = mutableListOf<List<Message>>()
        val model = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = error("catalog definitions must be preserved")
            override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                assertEquals(listOf("screen_action"), definitions.map { it.name })
                seen.add(history)
                return object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
                    override fun cancel() {}
                }
            }
        }
        val wrapped = GuiConfirmedModelPort(model, user.id, delivery)
        wrapped.open(listOf(earlier, user), listOf(ToolDefinition("screen_action", "exact", "{}", true)))
        assertSame(earlier, seen.single().first())
        assertEquals(1, user.blocks.size)
        assertSame(user.blocks.single(), seen.single().last().blocks.first())
        assertEquals(delivery.modelContext(), (seen.single().last().blocks.last() as MessageBlock.Text).text)
        assertSame(observation, delivery.observation)
        assertSame(action, delivery.action)
        assertEquals("approved", JSONObject(delivery.actionArgumentsJson()).getString("observation_id"))
    }
}
