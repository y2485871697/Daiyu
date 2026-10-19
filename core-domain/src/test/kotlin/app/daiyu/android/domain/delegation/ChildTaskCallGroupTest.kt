package app.daiyu.android.domain.delegation

import org.junit.Assert.*
import org.junit.Test

class ChildTaskCallGroupTest {
    @Test fun stableAcrossProcessesAndIndependentOfLease() {
        val a = childTaskCallGroup("c", "r", "m", "start_child_tasks", "call")
        assertEquals(a, childTaskCallGroup("c", "r", "m", "start_child_tasks", "call"))
        assertTrue(a.matches(Regex("pcall-v1-[0-9a-f]{64}")))
    }
    @Test fun everyOwnerAndCallComponentSeparatesTheGroup() {
        val base = listOf("c", "r", "m", "tool", "call")
        fun key(v: List<String>) = childTaskCallGroup(v[0], v[1], v[2], v[3], v[4])
        base.indices.forEach { index -> assertNotEquals(key(base), key(base.mapIndexed { i, v -> if (i == index) "$v-new" else v })) }
    }
    @Test fun lengthDelimitedUnicodeAndDelimitersCannotAlias() {
        assertNotEquals(childTaskCallGroup("会:话", "r", "m", "t", "1:2"), childTaskCallGroup("会", ":话r", "m", "t", "1:2"))
        assertNotEquals(childTaskCallGroup("a", "bc", "m", "t", "call"), childTaskCallGroup("ab", "c", "m", "t", "call"))
    }
}
