package app.daiyu.android.ui.model

import app.daiyu.android.delegation.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationReasoningPreferenceTest {
    private class MemoryKeyValue : ConversationReasoningPreference.KeyValue {
        val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }
    }

    @Test fun missingAndUnknownValuesUseDefault() {
        val values = MemoryKeyValue()
        val preference = ConversationReasoningPreference(values)
        assertEquals(ReasoningEffort.DEFAULT, preference.get(null))
        assertEquals(ReasoningEffort.DEFAULT, preference.get("new-conversation"))
        preference.set("conversation", ReasoningEffort.HIGH)
        val key = values.values.keys.single()
        values.values[key] = "future-unsupported-effort"
        assertEquals(ReasoningEffort.DEFAULT, preference.get("conversation"))
    }

    @Test fun everyEffortSurvivesPreferenceRecreation() {
        val values = MemoryKeyValue()
        ReasoningEffort.entries.forEach { effort ->
            ConversationReasoningPreference(values).set("conversation", effort)
            assertEquals(effort.wireValue, values.values.values.single())
            assertEquals(effort, ConversationReasoningPreference(values).get("conversation"))
        }
    }

    @Test fun conversationsAndDraftHaveIndependentKeys() {
        val values = MemoryKeyValue()
        val preference = ConversationReasoningPreference(values)
        preference.set(null, ReasoningEffort.MINIMAL)
        preference.set("draft", ReasoningEffort.HIGH)
        preference.set("second", ReasoningEffort.OFF)
        preference.set("", ReasoningEffort.LOW)
        val restored = ConversationReasoningPreference(values)
        assertEquals(ReasoningEffort.MINIMAL, restored.get(null))
        assertEquals(ReasoningEffort.HIGH, restored.get("draft"))
        assertEquals(ReasoningEffort.OFF, restored.get("second"))
        assertEquals(ReasoningEffort.LOW, restored.get(""))
        assertEquals(ReasoningEffort.DEFAULT, restored.get("third"))
        assertEquals(4, values.values.size)
        assertEquals(true, values.values.keys.all { it.startsWith("reasoning:") })
    }

    @Test fun settingDefaultReplacesPreviousSelection() {
        val values = MemoryKeyValue()
        val preference = ConversationReasoningPreference(values)
        preference.set("conversation", ReasoningEffort.MAX)
        preference.set("conversation", ReasoningEffort.DEFAULT)
        assertEquals(ReasoningEffort.DEFAULT, ConversationReasoningPreference(values).get("conversation"))
        assertEquals("default", values.values.values.single())
    }
}
