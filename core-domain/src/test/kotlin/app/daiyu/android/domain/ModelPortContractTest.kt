package app.daiyu.android.domain

import org.junit.Assert.*
import org.junit.Test

class ModelPortContractTest {
    @Test fun nonemptyToolsNeverSilentlyFallBackToPlainOpen() {
        var plainCalls = 0
        val port = ModelPort { plainCalls++; object : ModelSession {
            override fun cancel() {}
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
        } }
        try { port.open(emptyList(), setOf(ReadOnlyTool.BATTERY)); fail() }
        catch (_: UnsupportedOperationException) {}
        try { port.open(emptyList(), listOf(ToolDefinition("terminal_exec", "command", "{}"))); fail() }
        catch (_: UnsupportedOperationException) {}
        assertEquals(0, plainCalls)
        port.open(emptyList(), emptySet<ReadOnlyTool>())
        port.open(emptyList(), emptyList<ToolDefinition>())
        assertEquals(2, plainCalls)
    }
}
