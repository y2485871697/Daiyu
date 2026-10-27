package app.daiyu.android.voice.account

import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import org.junit.Assert.*
import org.junit.Test

class PersonalVoicePreviewTest {
    private val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", "key", "seed-tts-2.0", "account-label")
    private fun voice(id: String = "S_one") = PersonalVoices.Voice(id, id, DoubaoPersonalVoice.accountFingerprint("key"),
        status = 2, models = setOf(5), accepted = true, demo = "https://example.test/demo?private-token")
    private class Entry(val request: AccountPreviewRequest, val listener: VoiceAccountPreviewPort.Listener) : VoiceAccountPreviewPort.Lease {
        var stopped = 0
        override fun stop() { stopped++; listener.stopped() }
    }
    private fun owner(entries: MutableList<Entry>) = PersonalVoicePreview(VoiceAccountPreviewPort { request, listener ->
        Entry(request, listener).also { entries += it }
    })
    @Test fun constructionDoesNotStartAndLoadingOrPlayingAreStoppable() {
        val entries = mutableListOf<Entry>(); val preview = owner(entries)
        assertTrue(entries.isEmpty())
        preview.toggle(config, voice())
        assertTrue(preview.state.value.loading)
        assertEquals("S_one", entries.single().request.voice.id)
        assertFalse(entries.single().request.toString().contains("private-token"))
        entries.single().listener.started()
        assertFalse(preview.state.value.loading)
        preview.toggle(config, voice())
        assertEquals(1, entries.single().stopped)
        assertFalse(preview.state.value.active)
        entries.single().listener.started()
        assertFalse(preview.state.value.active)
    }
    @Test fun staleCallbacksCannotChangeNewSelection() {
        val entries = mutableListOf<Entry>(); val preview = owner(entries)
        preview.toggle(config, voice("S_old")); preview.toggle(config, voice("S_new"))
        entries[0].listener.started(); entries[0].listener.failed(); entries[0].listener.stopped()
        assertTrue(preview.state.value.matches(voice().account, "S_new"))
        assertTrue(preview.state.value.loading); assertNull(preview.state.value.error)
        entries[1].listener.started(); assertFalse(preview.state.value.loading)
        entries[1].listener.stopped(); assertFalse(preview.state.value.active)
        assertEquals(1, entries[1].stopped)
    }
    @Test fun synchronousStopDuringPortAdmissionReleasesReturnedLease() {
        var released = 0
        val preview = PersonalVoicePreview(VoiceAccountPreviewPort { _, listener ->
            listener.stopped()
            VoiceAccountPreviewPort.Lease { released++ }
        })
        preview.toggle(config, voice())
        assertFalse(preview.state.value.active); assertEquals(1, released)
    }
    @Test fun failuresAndExplicitLeavingFenceAllCallbacks() {
        val entries = mutableListOf<Entry>(); val preview = owner(entries)
        preview.toggle(config, voice())
        entries[0].listener.failed()
        assertFalse(preview.state.value.active); assertNotNull(preview.state.value.error)
        preview.toggle(config, voice()); preview.stop()
        entries[1].listener.started(); entries[1].listener.failed()
        assertFalse(preview.state.value.active); assertNull(preview.state.value.error)
        assertEquals(1, entries[1].stopped)
    }
    @Test fun leavingAfterAnAlreadyFailedPreviewClearsErrorAndFencesItsLateCallback() {
        val entries = mutableListOf<Entry>(); val preview = owner(entries)
        preview.toggle(config, voice())
        entries.single().listener.failed()
        assertNotNull(preview.state.value.error)
        assertFalse(preview.state.value.active)
        preview.stop()
        assertNull(preview.state.value.error)
        entries.single().listener.failed(); entries.single().listener.started()
        assertNull(preview.state.value.error)
        assertFalse(preview.state.value.active)
    }
    @Test fun unknownWrongAccountAndUnsafeUrlsNeverReachPort() {
        val entries = mutableListOf<Entry>(); val preview = owner(entries)
        listOf(voice().copy(uncertain = true), voice().copy(account = "wrong"),
            voice().copy(demo = "http://example.test/demo"), voice().copy(demo = "https://user:secret@example.test/demo")).forEach {
            assertThrows(IllegalArgumentException::class.java) { preview.toggle(config, it) }
        }
        assertTrue(entries.isEmpty())
    }
    @Test fun demoCanBeHeardBeforeFormalAcceptanceWithoutChangingTheVoice() {
        val entries = mutableListOf<Entry>(); val preview = owner(entries)
        val pending = voice().copy(accepted = false)
        preview.toggle(config, pending)
        assertEquals(1, entries.size)
        assertTrue(entries.single().request.demoOnly)
        assertEquals(pending.demo, entries.single().request.demoUrl)
        assertFalse(entries.single().request.voice.accepted)
        assertFalse(pending.accepted)
        preview.stop()
        assertEquals(1, entries.single().stopped)
    }

    @Test fun retainedDrainPolicyIsBoundedButActualDrainBelongsToParentPort() {
        assertEquals(826L, PersonalVoicePreview.drainDelayMs(3407, 2731))
        assertEquals(150L, PersonalVoicePreview.drainDelayMs(-1, -1))
        assertEquals(150L, PersonalVoicePreview.drainDelayMs(1000, 2000))
        assertEquals(2150L, PersonalVoicePreview.drainDelayMs(Int.MAX_VALUE, 0))
    }
}
