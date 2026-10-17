package app.daiyu.android.voice.control

import org.junit.Assert.*
import org.junit.Test

class VoiceEntryPolicyTest {
    private val entry = VoiceEntrySnapshot("chat", "foreground", VoiceEntryMode.entries.toSet(), true, true)

    @Test fun selectionNeverGuessesUniversalFromUnknownOrDisabledHistory() {
        assertNull(VoiceEntryMode.fromWireValue("future-mode"))
        assertNull(VoiceEntryPolicy.directMode(entry, "future-mode"))
        assertTrue(VoiceEntryPolicy.canChoose(entry))
        assertEquals(VoiceEntryMode.UNIVERSAL, VoiceEntryPolicy.directMode(entry, "universal"))
        val dictationOnly = entry.copy(enabledModes = setOf(VoiceEntryMode.DICTATION))
        assertEquals(VoiceEntryMode.DICTATION, VoiceEntryPolicy.directMode(dictationOnly, "universal"))
        assertFalse(VoiceEntryPolicy.canChoose(dictationOnly))
        assertEquals(VoiceControlStopReason.DISABLED, VoiceEntryPolicy.startProblem(dictationOnly))
        assertTrue(VoiceEntryPolicy.modes(entry.copy(enabledModes = emptySet())).isEmpty())
        assertNull(VoiceEntryPolicy.directMode(entry.copy(enabledModes = emptySet())))
    }

    @Test fun permissionIsSeparateFromAvailabilityAndGrantDoesNotChangeModes() {
        assertNull(VoiceEntryPolicy.startProblem(entry.copy(microphoneGranted = false)))
        assertEquals(VoiceEntryPolicy.modes(entry), VoiceEntryPolicy.modes(entry.copy(microphoneGranted = true)))
        assertEquals(VoiceControlStopReason.REVOKED, VoiceEntryPolicy.startProblem(entry.copy(lifecycleKey = "")))
        assertEquals(VoiceControlStopReason.CONFIGURATION_CHANGED, VoiceEntryPolicy.startProblem(entry.copy(profileReady = false)))
        assertEquals(VoiceControlStopReason.QUESTION_WAITING, VoiceEntryPolicy.startProblem(entry.copy(questionWaiting = true)))
    }

    @Test fun permitLimitsAreFiniteAndCannotBeDisabledByZeroOrUnlimitedValues() {
        val invalid = listOf<() -> Unit>(
            { VoiceControlLimits(maxTurns = 0) }, { VoiceControlLimits(maxTurns = 21) },
            { VoiceControlLimits(durationMillis = 0) }, { VoiceControlLimits(durationMillis = Long.MAX_VALUE) },
            { VoiceControlLimits(receiptMillis = 0) }, { VoiceControlLimits(drainMillis = 240_001) })
        invalid.forEach { construct ->
            try { construct(); fail("unbounded limits accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(3, VoiceControlLimits().maxTurns)
        assertEquals(180_000L, VoiceControlLimits().durationMillis)
    }
}
