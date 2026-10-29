package app.daiyu.android.assistants

import org.junit.Assert.*
import org.junit.Test

class AssistantMemoryReadPortTest {
    @Test fun frozenBindingHasBoundedCoreAndCanFindTailWithoutReadingAnotherAssistant() {
        val a = AssistantProfile("a", "A", "")
        val b = AssistantProfile("b", "B", "")
        val store = MemoryTestStore(a, b)
        val text = "前置😀".repeat(3000) + "TAIL-only-a"
        val assets = MemoryTestAssets("a" to text, "b" to "private-only-b")
        val context = store.capture("a", assets)
        assertTrue(context.resolvedMemory.toByteArray(Charsets.UTF_8).size <= AssistantMemoryLimits.CORE_BYTES)
        assertTrue(context.resolvedMemory.endsWith(AssistantMemoryLimits.TRUNCATION))
        assertFalse(context.resolvedMemory.contains("TAIL-only-a"))
        assertEquals("a-v1", context.memoryRevision)
        assertTrue(context.systemPrompt.contains(AssistantMemoryLimits.MODEL_POLICY))
        val port = context.memoryReadPort!!
        val hits = port.search("TAIL-only-a")
        assertEquals("a", hits.assistantId)
        assertTrue(hits.hits.single().content.contains("TAIL-only-a"))
        assertTrue(port.search("private-only-b").hits.isEmpty())
        assertEquals("TAIL-only-a", port.read(text.indexOf("TAIL-only-a")).content)
        assertEquals(setOf("a"), assets.reads.toSet())
        store.save(b.copy(name = "changed B"))
        assertEquals("a-v1", port.read().revision)
        assets.replace("a", "late replacement", "a-v2")
        assertThrows(AssistantMemoryRevisionConflict::class.java) { port.read() }
        assertThrows(AssistantMemoryRevisionConflict::class.java) { port.search("late") }
        assertFalse(context.resolvedMemory.contains("late replacement"))
    }

    @Test fun disabledMemoryHasZeroReadsZeroCoreZeroPortAndNoRevision() {
        val store = MemoryTestStore(AssistantProfile("off", "Off", "", memoryEnabled = false))
        val assets = MemoryTestAssets("off" to "never read")
        val captured = store.capture("off", assets)
        assertEquals("", captured.resolvedMemory)
        assertNull(captured.memoryRevision)
        assertNull(captured.memoryReadPort)
        assertEquals(AssistantPrompt.build("Off", "") + "\n\n" + AssistantRunContext.EXECUTION_POLICY, captured.systemPrompt)
        assertTrue(assets.reads.isEmpty())
    }

    @Test fun deletedOrDisabledLiveBindingCannotReadAndPreparationRejectsProfileRace() {
        val a = AssistantProfile("a", "A", "")
        val store = MemoryTestStore(a)
        val assets = MemoryTestAssets("a" to "a memory")
        val port = store.capture("a", assets).memoryReadPort!!
        store.save(a.copy(memoryEnabled = false))
        val reads = assets.reads.size
        assertThrows(AssistantMemoryUnavailable::class.java) { port.read() }
        assertEquals(reads, assets.reads.size)
        store.delete("a")
        assertThrows(AssistantMemoryUnavailable::class.java) { port.search("a") }
        store.save(a)
        assets.afterRead = { store.save(a.copy(prompt = "concurrent")) }
        assertThrows(StaleAssistantProfileException::class.java) { store.capture("a", assets) }
    }

    @Test fun utf8BudgetsOffsetsAndSearchAreBoundedAndLiteral() {
        val store = MemoryTestStore(AssistantProfile("a", "A", ""))
        val assets = MemoryTestAssets("a" to ("😀中[.*]".repeat(3000)))
        val port = store.capture("a", assets).memoryReadPort!!
        assertEquals("😀", port.read(maxBytes = 4).content)
        assertEquals(2, port.read(maxBytes = 4).nextOffset)
        assertThrows(IllegalArgumentException::class.java) { port.read(1) }
        assertThrows(IllegalArgumentException::class.java) { port.read(maxBytes = 1) }
        assertThrows(IllegalArgumentException::class.java) { port.read(maxBytes = AssistantMemoryLimits.READ_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { port.search(" ") }
        assertThrows(IllegalArgumentException::class.java) { port.search("x".repeat(257)) }
        val result = port.search("[.*]", maxHits = 3, maxBytes = 100)
        assertTrue(result.truncated)
        assertTrue(result.hits.size <= 3)
        assertTrue(result.hits.sumOf { it.content.toByteArray(Charsets.UTF_8).size } <= 100)
        assertTrue(result.hits.first().content.contains("[.*]"))
        val legacy = store.capture("a", "\"".repeat(100000))
        assertTrue(legacy.resolvedMemory.toByteArray(Charsets.UTF_8).size <= AssistantMemoryLimits.CORE_BYTES)
        assertNull(legacy.memoryReadPort)
    }
}
