package app.daiyu.android.voice.account

import org.junit.Assert.*
import org.junit.Test

class AccountSamplePickerTest {
    @Test fun startedDeliveryWaitsForResumeAndIsConsumedOnceWithoutReadingOrUploading() {
        val picker = AccountSamplePicker<String>()
        assertTrue(picker.begin("saved-A")); assertFalse(picker.begin("saved-A"))
        picker.deliver("content://local/audio")
        assertNull(picker.take("saved-A", resumed = false, pageCurrent = false))
        assertTrue(picker.pending)
        assertEquals("content://local/audio", picker.take("saved-A", resumed = true, pageCurrent = true))
        assertFalse(picker.pending)
        assertNull(picker.take("saved-A", resumed = true, pageCurrent = true))
    }
    @Test fun configurationChangeCannotRebindOldPickerResultToNewAccount() {
        val picker = AccountSamplePicker<String>()
        picker.begin("A")
        assertNull(picker.take("B", true, true))
        assertFalse(picker.begin("B"))
        picker.deliver("old-uri")
        assertNull(picker.take("B", true, true))
        assertTrue(picker.begin("B"))
        picker.deliver("new-uri")
        assertEquals("new-uri", picker.take("B", true, true))
    }
    @Test fun disposalAndCancellationClearDeliveredResultsButDoNotPermitOverlappingLaunches() {
        val picker = AccountSamplePicker<String>()
        picker.begin("A"); picker.invalidate()
        assertTrue(picker.pending); assertFalse(picker.begin("B"))
        picker.deliver("stale")
        assertNull(picker.take("A", true, true)); assertFalse(picker.pending)
        picker.begin("A"); picker.deliver(null)
        assertNull(picker.take("A", true, true)); assertFalse(picker.pending)
    }
}
