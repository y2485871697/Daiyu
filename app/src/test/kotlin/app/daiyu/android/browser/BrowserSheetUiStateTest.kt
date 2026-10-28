package app.daiyu.android.browser

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class BrowserSheetUiStateTest {
    @Test fun firstTabProjectionObservesEditsAfterSameValueSynchronization() {
        val state = BrowserSheetUiState()
        var invalidated = false
        val scope = Any()
        val observer = androidx.compose.runtime.snapshots.SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(scope, { _: Any -> invalidated = true }) {
                assertEquals("", state.displayedAddress(0, "").text)
            }
            state.synchronize(0, "")
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            assertFalse(invalidated)
            val edit = TextFieldValue("unfinished address, do not navigate", TextRange(2, 9))
            state.editAddress(edit)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            assertEquals(edit, state.address)
            assertTrue("first-tab projection must observe address edits", invalidated)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test fun detachedPoolAndSameTabPollingKeepUnsavedAddressAndSelection() {
        val state = BrowserSheetUiState()
        state.synchronize(0, "https://example.com/")
        val edit = TextFieldValue("not submitted yet", TextRange(8, 2), TextRange(1, 9))
        state.editAddress(edit)
        repeat(3) {
            state.synchronize(null, "")
            assertEquals(edit, state.displayedAddress(null, ""))
            state.synchronize(0, "https://example.com/")
            assertEquals(edit, state.address)
        }
    }
    @Test fun changedPageCannotEraseDirtyInputButTabSwitchResetsToItsOwnAddress() {
        val state = BrowserSheetUiState()
        state.synchronize(0, "https://example.com/")
        state.editAddress(TextFieldValue("unfinished"))
        state.synchronize(0, "https://example.com/redirect")
        assertEquals("unfinished", state.address.text)
        state.synchronize(1, "https://example.org/")
        assertEquals("https://example.org/", state.address.text)
    }
    @Test fun committedAddressAndCleanNavigationFollowTheNewUrl() {
        val state = BrowserSheetUiState()
        state.synchronize(0, "https://example.com/")
        state.editAddress(TextFieldValue("https://example.org"))
        state.submittedAddress("https://example.org")
        state.synchronize(0, "https://example.com/")
        assertEquals("https://example.org", state.address.text)
        state.synchronize(0, "https://example.org/")
        assertEquals("https://example.org/", state.address.text)
    }
    @Test fun firstDisplayedFrameUsesDraftOrNewTabBeforeSideEffectSynchronizes() {
        val state = BrowserSheetUiState()
        state.synchronize(0, "https://example.com/")
        val edit = TextFieldValue("unfinished", TextRange(6, 2), TextRange(1, 7))
        state.editAddress(edit)
        assertEquals(edit, state.displayedAddress(0, ""))
        assertEquals(edit, state.displayedAddress(0, "https://example.com/redirect"))
        assertEquals("https://example.org/", state.displayedAddress(1, "https://example.org/").text)
        assertEquals(edit, state.address)
        state.synchronize(1, "https://example.org/")
        assertEquals(state.address, state.displayedAddress(1, "https://example.org/"))
    }
    @Test fun panelsAndHistoryQueryArePresentationOnlyAndNewPageStartsClean() {
        val state = BrowserSheetUiState()
        state.showHistory = true; state.showSettings = true; state.showDownloads = true; state.historyQuery = "unfinished query"
        state.synchronize(null, "")
        assertTrue(state.showHistory); assertTrue(state.showSettings); assertTrue(state.showDownloads)
        assertEquals("unfinished query", state.historyQuery)
        val replacement = BrowserSheetUiState()
        assertFalse(replacement.showHistory); assertFalse(replacement.showSettings); assertFalse(replacement.showDownloads)
        assertEquals("", replacement.historyQuery)
    }
}
