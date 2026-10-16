package app.daiyu.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import app.daiyu.android.voice.offline.OfflineSpeechPack

/** Consent/navigation only: no model download, system recognizer, or permission grant. */
class OfflineSpeechEntryActivityTest : WiredActivityTestCase() {
    fun testComposerExposesIndependentOfflineSettingsWithoutEnablingOrDownloading() {
        mainThread { OfflineSpeechPack.setEnabled(activity, false) }
        node("offline-speech-open").performClick()
        node("offline-speech-settings").assertIsDisplayed()
        assertFalse(OfflineSpeechPack.state.value.enabled)
        assertFalse(OfflineSpeechPack.state.value.downloading)
        assertEquals(0, fixture.opens.get())
    }

    fun testFirstDownloadTapOnlyRequestsConfirmationAndNeverEnablesMicrophone() {
        mainThread { OfflineSpeechPack.setEnabled(activity, false) }
        node("offline-speech-open").performClick()
        ui.waitUntil(timeoutMillis = 5000) { !OfflineSpeechPack.state.value.checking }
        // CI starts without weights. A provisioned device already has a verified pack and
        // deliberately has no download control; do not modify/delete its model in a test.
        if (!OfflineSpeechPack.state.value.ready) node("offline-speech-download").performClick()
        assertFalse(OfflineSpeechPack.state.value.enabled)
        assertFalse(OfflineSpeechPack.state.value.downloading)
    }
}
