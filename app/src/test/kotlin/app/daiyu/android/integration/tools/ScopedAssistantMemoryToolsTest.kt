package app.daiyu.android.integration.tools

import app.daiyu.android.assistants.*
import app.daiyu.android.domain.tools.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScopedAssistantMemoryToolsTest {
    @Test fun onlyReadToolsForFrozenAssistantAndDisabledExposesNothing() {
        val store = MemoryTestStore(AssistantProfile("a", "A", ""), AssistantProfile("b", "B", "", memoryEnabled = false))
        val assets = MemoryTestAssets("a" to "content A", "b" to "private B")
        val context = store.capture("a", assets)
        val tools = ScopedAssistantMemoryTools.create(context) { true }
        assertEquals(setOf("assistant_memory_read", "assistant_memory_search"), tools.map { it.definition.name }.toSet())
        assertTrue(tools.all { it.definition.effect == ToolEffect.READ_ONLY })
        val read = tools.single { it.definition.name == "assistant_memory_read" }
        val response = read.prepare(read.definition.schema.parse("{}")).execute() as ExecutionReceipt.Confirmed
        assertFalse(response.isError)
        val body = JSONObject(response.payload)
        assertEquals("a", body.getString("assistant_id"))
        assertEquals("a-v1", body.getString("revision"))
        assertEquals("content A", body.getString("content"))
        assertThrows(IllegalArgumentException::class.java) { read.definition.schema.parse("{\"assistant_id\":\"b\"}") }
        assertThrows(IllegalArgumentException::class.java) { read.definition.schema.parse("{\"revision\":\"b-v1\"}") }
        val disabled = store.capture("b", assets)
        assertTrue(ScopedAssistantMemoryTools.create(disabled) { true }.isEmpty())
        assertEquals(setOf("a"), assets.reads.toSet())
    }

    @Test fun closedPreparedWorkDoesNotReadAndOldRevisionReturnsSafeConflict() {
        val store = MemoryTestStore(AssistantProfile("a", "A", ""))
        val assets = MemoryTestAssets("a" to "old")
        var active = true
        val tools = ScopedAssistantMemoryTools.create(store.capture("a", assets)) { active }
        val read = tools.single { it.definition.name == "assistant_memory_read" }
        val args = read.definition.schema.parse("{}")
        repeat(10) { read.prepare(args) }
        assertEquals(1, assets.reads.size)
        val prepared = read.prepare(args)
        active = false
        assertTrue(prepared.execute() is ExecutionReceipt.NotExecuted)
        assertEquals(1, assets.reads.size)
        active = true
        assets.replace("a", "must not leak this replacement", "new")
        val conflict = prepared.execute() as ExecutionReceipt.Confirmed
        assertTrue(conflict.isError)
        assertEquals("assistant_memory_revision_conflict", JSONObject(conflict.payload).getString("error"))
        assertFalse(conflict.payload.contains("must not leak"))
    }

    @Test fun escapedPayloadIsIndependentlyBoundedAndSearchReadsTail() {
        val store = MemoryTestStore(AssistantProfile("a", "A", ""))
        val assets = MemoryTestAssets("a" to ("\u0001".repeat(100000) + "unique-tail"))
        val tools = ScopedAssistantMemoryTools.create(store.capture("a", assets)) { true }
        val read = tools.single { it.definition.name == "assistant_memory_read" }
        val response = read.prepare(read.definition.schema.parse("{}")).execute() as ExecutionReceipt.Confirmed
        assertFalse(response.isError)
        assertTrue(response.payload.toByteArray(Charsets.UTF_8).size < 64 * 1024)
        assertEquals(AssistantMemoryLimits.READ_BYTES, JSONObject(response.payload).getString("content").length)
        val search = tools.single { it.definition.name == "assistant_memory_search" }
        val found = search.prepare(search.definition.schema.parse("{\"query\":\"unique-tail\"}")).execute() as ExecutionReceipt.Confirmed
        assertFalse(found.isError)
        assertTrue(JSONObject(found.payload).getJSONArray("hits").getJSONObject(0).getString("content").contains("unique-tail"))
    }
}
