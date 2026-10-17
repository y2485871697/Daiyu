package app.daiyu.android

import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import org.junit.Assert.*
import org.junit.Test

class AccountPreviewVoiceAdmissionTest {
    private fun voice(id: String = "S_one", account: String = "account", status: Int = 2,
        models: Set<Int> = setOf(4, 5), accepted: Boolean = true) =
        DoubaoPersonalVoice(id, account, status, models, accepted)

    @Test fun independentMatchingSnapshotsAreAdmitted() {
        val requested = voice()
        val selected = voice(models = linkedSetOf(5, 4))
        assertNotSame(selected, requested)
        assertTrue(accountPreviewVoiceMatches(selected, requested))
    }

    @Test fun missingOrChangedFrozenFieldsAreRejected() {
        val requested = voice()
        assertFalse(accountPreviewVoiceMatches(null, requested))
        listOf(voice(id = "S_other"), voice(account = "other"), voice(status = 4),
            voice(models = setOf(5)), voice(accepted = false)).forEach { changed ->
            assertFalse(accountPreviewVoiceMatches(changed, requested))
        }
    }
}
