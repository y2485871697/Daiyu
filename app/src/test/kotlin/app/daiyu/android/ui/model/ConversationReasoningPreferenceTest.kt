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

    @Test fun runtimeSnapshotRejectsUnknownAndReadFailuresInsteadOfSilentlyUsingDefault() {
        val values = MemoryKeyValue()
        val preference = ConversationReasoningPreference(values)
        preference.set("conversation", ReasoningEffort.LOW)
        values.values[values.values.keys.single()] = "unsupported-future-value"
        try { preference.snapshot("conversation"); throw AssertionError("unknown runtime preference accepted") }
        catch (_: IllegalStateException) {}
        val unreadable = ConversationReasoningPreference(object : ConversationReasoningPreference.KeyValue {
            override fun get(key: String): String? = throw IllegalStateException("unreadable")
            override fun set(key: String, value: String) {}
        })
        assertEquals(ReasoningEffort.DEFAULT, unreadable.get("conversation")) // Display only.
        try { unreadable.snapshot("conversation"); throw AssertionError("unreadable runtime preference accepted") }
        catch (_: IllegalStateException) {}
    }

    @Test fun acceptedStandaloneChoiceIsDetachedAndDoesNotOverwriteConversationEdits() {
        val values = MemoryKeyValue()
        val preference = ConversationReasoningPreference(values)
        preference.set(null, ReasoningEffort.LOW)
        val frozen = preference.snapshot(null)
        preference.set(null, ReasoningEffort.HIGH)
        preference.retainAccepted("first", frozen)
        assertEquals(ReasoningEffort.LOW, preference.snapshot("first"))
        preference.set("first", ReasoningEffort.OFF)
        preference.retainAccepted("first", frozen)
        assertEquals(ReasoningEffort.OFF, preference.snapshot("first"))
        assertEquals(ReasoningEffort.HIGH, preference.snapshot(null))
        assertEquals(ReasoningEffort.DEFAULT, preference.snapshot("other"))
    }

    @Test fun failedSaveFencesRuntimeUntilAnExplicitConfirmedSave() {
        var stored: String? = null
        var fails = true
        val preference = ConversationReasoningPreference(object : ConversationReasoningPreference.KeyValue {
            override fun get(key: String) = stored
            override fun set(key: String, value: String) { stored = value; check(!fails) }
        })
        try { preference.set(null, ReasoningEffort.LOW); throw AssertionError("save accepted") } catch (_: IllegalStateException) {}
        try { preference.snapshot(null); throw AssertionError("unconfirmed value accepted") } catch (_: IllegalStateException) {}
        fails = false
        preference.set(null, ReasoningEffort.HIGH)
        assertEquals(ReasoningEffort.HIGH, preference.snapshot(null))
    }

    @Test fun settingDefaultReplacesPreviousSelection() {
        val values = MemoryKeyValue()
        val preference = ConversationReasoningPreference(values)
        preference.set("conversation", ReasoningEffort.MAX)
        preference.set("conversation", ReasoningEffort.DEFAULT)
        assertEquals(ReasoningEffort.DEFAULT, ConversationReasoningPreference(values).get("conversation"))
        assertEquals("default", values.values.values.single())
    }
    @Test fun draftDefaultNeverChangesExistingConversationWithOrWithoutExplicitChoice() {
        val values = MemoryKeyValue()
        val preference = ConversationReasoningPreference(values)
        preference.set("old", ReasoningEffort.HIGH)
        preference.set(null, ReasoningEffort.LOW)
        val restored = ConversationReasoningPreference(values)
        assertEquals(ReasoningEffort.LOW, restored.snapshot(null))
        assertEquals(ReasoningEffort.HIGH, restored.snapshot("old"))
        assertEquals(ReasoningEffort.DEFAULT, restored.snapshot("old-without-choice"))
    }

    @Test fun disablingStartingDefaultPreservesAcceptedChoiceAndModelDefaultPolicy() {
        val preference = ConversationReasoningPreference(MemoryKeyValue())
        preference.set(null, ReasoningEffort.LOW)
        preference.retainAccepted("accepted", preference.snapshot(null))
        preference.set(null, ReasoningEffort.DEFAULT)
        assertEquals(ReasoningEffort.DEFAULT, preference.snapshot(null))
        assertEquals(ReasoningEffort.LOW, preference.snapshot("accepted"))
    }

    @Test fun failedStartingDefaultSaveFencesDraftWithoutDamagingOldConversation() {
        val values = mutableMapOf<String, String>()
        var fails = false
        val preference = ConversationReasoningPreference(object : ConversationReasoningPreference.KeyValue {
            override fun get(key: String) = values[key]
            override fun set(key: String, value: String) { values[key] = value; check(!fails) }
        })
        preference.set("old", ReasoningEffort.LOW)
        fails = true
        try { preference.set(null, ReasoningEffort.HIGH); throw AssertionError("save accepted") }
        catch (_: IllegalStateException) {}
        assertEquals(ReasoningEffort.LOW, preference.snapshot("old"))
        try { preference.snapshot(null); throw AssertionError("unconfirmed starting default accepted") }
        catch (_: IllegalStateException) {}
        fails = false
        preference.set(null, ReasoningEffort.MINIMAL)
        assertEquals(ReasoningEffort.MINIMAL, preference.snapshot(null))
    }

}
