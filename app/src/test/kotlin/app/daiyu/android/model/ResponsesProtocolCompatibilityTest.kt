package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesProtocolCompatibilityTest {
    private fun frame(value: JSONObject) = "data: $value\n\n"
    private fun event(type: String) = JSONObject().put("type", type)
    private fun call(arguments: String = "{}") = JSONObject().put("type", "function_call").put("call_id", "call").put("name", "lookup").put("arguments", arguments)
    private fun terminal(vararg items: JSONObject) = event("response.completed").put("response", JSONObject().put("status", "completed")
        .put("output", JSONArray(items.toList())))
    @Test fun optionalIndexesAndDoneWithoutTextPreserveDeltas() {
        val events = mutableListOf<ModelEvent>()
        val stream = frame(event("response.output_text.delta").put("item_id", "m").put("delta", "hello")) +
            frame(event("response.output_text.done").put("item_id", "m")) + frame(terminal())
        ResponsesStream.read(stream.reader().buffered(), { false }, events::add)
        assertEquals("hello", events.filterIsInstance<ModelEvent.TextFinal>().last().text)
    }
    @Test fun summaryIndexFallsBackToContentIndexAndDoneCanBeEmpty() {
        val events = mutableListOf<ModelEvent>()
        val stream = frame(event("response.reasoning_summary_text.delta").put("item_id", "r").put("content_index", 2).put("delta", "summary")) +
            frame(event("response.reasoning_summary_text.done").put("item_id", "r")) + frame(terminal())
        ResponsesStream.read(stream.reader().buffered(), { false }, events::add)
        assertEquals("summary", events.filterIsInstance<ModelEvent.ReasoningFinal>().last().text)
    }
    @Test fun terminalOnlyCallsAreConfirmedOnce() {
        val events = mutableListOf<ModelEvent>()
        val out = ResponsesStream.read(frame(terminal(call())).reader().buffered(), { false }, events::add)
        assertEquals(1, out.size); assertEquals(1, events.filterIsInstance<ModelEvent.ToolReady>().size)
    }
    @Test fun itemDoneWithoutTerminalAndServerOutputsCannotExecuteLocally() {
        val item = frame(event("response.output_item.done").put("item", call()))
        for (stream in listOf(item, item + frame(terminal()), frame(terminal(call(), JSONObject().put("type", "function_call_output").put("call_id", "call").put("output", "remote"))))) {
            val events = mutableListOf<ModelEvent>()
            assertThrows(ModelRequestException::class.java) { ResponsesStream.read(stream.reader().buffered(), { false }, events::add) }
            assertTrue(events.none { it is ModelEvent.ToolReady })
        }
    }
    @Test fun repeatedDoneIsIdempotentAndFinalArgumentsCompareAsJson() {
        val events = mutableListOf<ModelEvent>()
        val item = frame(event("response.output_item.done").put("item", call("{\"a\":1,\"b\":2}")))
        ResponsesStream.read((item + item + frame(terminal(call("{\"b\":2,\"a\":1}")))).reader().buffered(), { false }, events::add)
        assertEquals(1, events.filterIsInstance<ModelEvent.ToolReady>().size)
    }
    @Test fun rejectedTerminalBatchRollsBackBeforeReplay() {
        val state = ResponsesStream.State()
        val packet = terminal(call()).put("sequence_number", 1)
        packet.getJSONObject("response").put("id", "resp_fixture")
        assertThrows(ModelDeliveryRejectedException::class.java) {
            state.read(frame(packet).reader().buffered(), { false }, acknowledgedSink = { ModelDeliveryAck.REJECTED })
        }
        val batches = mutableListOf<ModelEventBatch>()
        state.read(frame(packet).reader().buffered(), { false }, acknowledgedSink = { batches.add(it); ModelDeliveryAck.ACCEPTED })
        assertEquals(1, batches.single().events.filterIsInstance<ModelEvent.ToolReady>().size)
        assertEquals(1L, state.acknowledgedSequence)
    }
    @Test fun invalidIndexesRemainProtocolFailures() {
        for (index in listOf<Any>(-1, "0", 0.5)) {
            val data = event("response.output_text.delta").put("item_id", "m").put("content_index", index).put("delta", "x")
            assertThrows(ModelRequestException::class.java) { ResponsesStream.read((frame(data) + frame(terminal())).reader().buffered(), { false }, {}) }
        }
    }
}
