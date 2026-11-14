package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacySearchDisplayTest {
    private val plain = "## Heading\n\n**bold** [site](https://example.org)\n\n```sh\necho '\\*'\n```"
    private fun item() = JSONObject().put("type", "message").put("id", "m").put("role", "assistant").put("content",
        JSONArray().put(JSONObject().put("type", "output_text").put("text", plain).put("annotations", JSONArray().put(
            JSONObject().put("type", "url_citation").put("start_index", 0).put("end_index", 2)
                .put("url", "https://source.test").put("title", "Source")))))
    @Test fun exactLegacyWitnessRestoresOnlyItsMatchingAssistantBlock() {
        val item = item(); val raw = item.toString()
        val old = MessageBlock.Text("m", ResponsesSearchProtocol.legacyDisplayText(item, plain))
        val untouched = MessageBlock.Text("other", "\u2060\\*literal\\*")
        val blocks = listOf(old, untouched)
        val projected = LegacySearchDisplay.project("assistant", blocks, listOf(raw))
        assertEquals(ResponsesSearchProtocol.displayText(item, plain), (projected.first() as MessageBlock.Text).text)
        assertSame(untouched, projected.last())
        assertEquals(raw, item.toString())
        assertEquals(ResponsesSearchProtocol.legacyDisplayText(item, plain), old.text)
        for (protocol in listOf(emptyList(), listOf("broken"), listOf(raw, raw)))
            assertEquals(blocks, LegacySearchDisplay.project("assistant", blocks, protocol))
        assertSame(blocks, LegacySearchDisplay.project("user", blocks, listOf(raw)))
        assertEquals(listOf(old.copy(text = old.text + "changed")),
            LegacySearchDisplay.project("assistant", listOf(old.copy(text = old.text + "changed")), listOf(raw)))
    }
    @Test fun workerPreparationUsesIdentityAndLeavesOwnerHistoryUnchanged() {
        val item = item(); val raw = item.toString()
        val text = MessageBlock.Text("m", ResponsesSearchProtocol.legacyDisplayText(item, plain))
        val owner = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", listOf(text), RunStatus.COMPLETED,
            protocolItems = listOf(raw), historySegments = listOf(MessageHistorySegment(MessageId("a"), "assistant", listOf(text), listOf(raw))))
        val original = owner.copy()
        LegacySearchDisplay.prepare(owner)
        assertEquals(original, owner)
        assertTrue((LegacySearchDisplay.display(text) as MessageBlock.Text).text.startsWith(plain))
        val unrelated = text.copy()
        assertSame(unrelated, LegacySearchDisplay.display(unrelated))
    }
    @Test fun emptyLegacyCitationStillRequiresTheExactWitness() {
        val item = item()
        val part = item.getJSONArray("content").getJSONObject(0).put("text", "")
        part.getJSONArray("annotations").getJSONObject(0).put("end_index", 0)
        val old = MessageBlock.Text("m", ResponsesSearchProtocol.legacyDisplayText(item, ""))
        assertFalse(old.text.contains("\u2060"))
        val projected = LegacySearchDisplay.project("assistant", listOf(old), listOf(item.toString()))
        assertEquals(ResponsesSearchProtocol.displayText(item, ""), (projected.single() as MessageBlock.Text).text)
    }
    @Test fun joinedDisplayNeverFlowsBackIntoAuthoritativeCallbacks() {
        val item = item()
        val old = MessageBlock.Text("m", ResponsesSearchProtocol.legacyDisplayText(item, plain))
        val tail = MessageBlock.Text("tail", "tail")
        val owner = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", listOf(old), RunStatus.COMPLETED,
            historySegments = listOf(
                MessageHistorySegment(MessageId("a"), "assistant", listOf(old), listOf(item.toString())),
                MessageHistorySegment(MessageId("a"), "assistant", listOf(tail))))
        LegacySearchDisplay.prepare(owner)
        val callbacks = mutableListOf<Message>()
        val turns = app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapper.map(listOf(owner),
            timestamp = { callbacks += it; "" })
        assertEquals(1, turns.size)
        assertEquals(listOf(old, tail), callbacks.single().blocks)
        val turn = turns.single() as app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi.Assistant
        assertTrue(turn.finalText.contains(plain))
    }

}
