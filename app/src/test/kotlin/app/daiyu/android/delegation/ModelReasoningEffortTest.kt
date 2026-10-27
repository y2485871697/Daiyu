package app.daiyu.android.delegation

import org.junit.Assert.*
import org.junit.Test

class ModelReasoningEffortTest {
    private val supported = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH, ReasoningEffort.MAX)

    @Test fun mandatoryModelsOnlyExposeDocumentedEfforts() {
        for (model in listOf("gpt-6-astra", "gpt-6.1-sol", "openai/gpt-6.1-sol", " Vendor/GPT-6-ASTRA "))
            assertEquals(model, supported, ReasoningEffort.textChoices(model))
    }

    @Test fun unsupportedStoredChoicesArePreservedRatherThanReplaced() {
        for (model in listOf("gpt-6-astra", "gpt-6.1-sol"))
            for (stored in listOf(null, ReasoningEffort.OFF, ReasoningEffort.DEFAULT, ReasoningEffort.MINIMAL))
                assertEquals(if (stored == ReasoningEffort.MINIMAL) stored else ReasoningEffort.OFF, ReasoningEffort.textPreference(stored, model = model))
    }

    @Test fun supportedChoicesAndInheritedHighRemainUnchanged() {
        for (effort in supported) assertEquals(effort, ReasoningEffort.textPreference(effort, model = "gpt-6.1-sol"))
        assertEquals(ReasoningEffort.HIGH, ReasoningEffort.textPreference(null, ReasoningEffort.HIGH, "gpt-6.1-sol"))
    }

    @Test fun otherModelsKeepTheExplicitOffDefaultAndAllLegacyChoices() {
        for (model in listOf("", "gpt-5", "gpt-6", "foo-gpt-6.1-sol", "gpt-6.1-sol-custom", "claude-fixture")) {
            assertEquals(7, ReasoningEffort.textChoices(model).size)
            assertEquals(ReasoningEffort.OFF, ReasoningEffort.textPreference(null, model = model))
            assertEquals(ReasoningEffort.OFF, ReasoningEffort.textPreference(ReasoningEffort.DEFAULT, model = model))
            assertEquals(ReasoningEffort.MINIMAL, ReasoningEffort.textPreference(ReasoningEffort.MINIMAL, model = model))
        }
    }
}
