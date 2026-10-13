package io.github.y2485871697.daiyu.domain

import org.junit.Assert.*
import org.junit.Test

class StableIdsTest {
    @Test fun rejectsBlankIdentities() {
        for (value in listOf("", " ", "\n")) {
            assertThrows(IllegalArgumentException::class.java) { ConversationId(value) }
            assertThrows(IllegalArgumentException::class.java) { MessageId(value) }
            assertThrows(IllegalArgumentException::class.java) { RunId(value) }
            assertThrows(IllegalArgumentException::class.java) { ToolCallId(value) }
        }
    }
    @Test fun identitiesHaveIndependentTypes() {
        assertNotEquals(ConversationId("same") as Any, MessageId("same") as Any)
        assertNotEquals(RunId("same") as Any, ToolCallId("same") as Any)
    }
    @Test fun preservesLogicalIdentity() {
        assertEquals(MessageId("message-1"), MessageId("message-1"))
        assertNotEquals(ToolCallId("call-1"), ToolCallId("call-2"))
    }
}
