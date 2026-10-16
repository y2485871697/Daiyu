package app.daiyu.android.voice

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.test.InstrumentationTestCase

/** Fake recognizer only: never discovers a service, requests permission or opens a microphone. */
@Suppress("DEPRECATION")
class DraftDictationSessionTest : InstrumentationTestCase() {
    private class Platform : SystemDictationPlatform, SystemDictationRecognizer {
        var permission = true
        var creates = 0; var cancels = 0; var destroys = 0
        lateinit var listener: RecognitionListener
        override fun permitted() = permission
        override fun available() = true
        override fun create(): SystemDictationRecognizer { creates++; return this }
        override fun listen(listener: RecognitionListener, language: String) { this.listener = listener }
        override fun cancel() { cancels++ }
        override fun destroy() { destroys++ }
    }
    private class Host : DraftDictationHost {
        val partials = mutableListOf<Pair<DictationDraftTarget, String>>()
        val drafts = mutableListOf<Pair<DictationDraftTarget, ConfirmableTranscript>>()
        val states = mutableListOf<VoiceState>()
        val permissions = mutableListOf<DictationDraftTarget>()
        override fun permissionRequired(target: DictationDraftTarget) { permissions += target }
        override fun partial(target: DictationDraftTarget, text: String) { partials += target to text }
        override fun finalDraft(target: DictationDraftTarget, transcript: ConfirmableTranscript) {
            drafts += target to transcript
        }
        override fun state(target: DictationDraftTarget, state: VoiceState) { states += state }
    }
    private fun onMain(action: () -> Unit) { instrumentation.runOnMainSync { action() } }
    private fun idle() { instrumentation.waitForIdleSync() }
    private fun result(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    fun testUnsavedComposerHasIndependentTargetAndFinalRequiresExplicitConfirmation() {
        val platform = Platform(); val host = Host(); val gate = AudioOwnerGate()
        val target = DictationDraftTarget(null, 7, 3, "draft-session")
        val session = DraftDictationSession(AndroidDictation(platform, gate), target, host) { it == target }
        assertNull(session.target.conversationId)
        assertTrue(session.audioOwner.conversationId.startsWith("draft-"))
        onMain { session.start() }; idle()
        platform.listener.onPartialResults(result("partial")); idle()
        platform.listener.onResults(result("final\ntext")); idle()
        assertEquals(target, host.partials.single().first)
        assertEquals(target, host.drafts.single().first)
        val transcript = host.drafts.single().second
        assertEquals("final\ntext", transcript.preview())
        assertEquals("edited draft", transcript.confirm("edited draft")); assertNull(transcript.confirm())
        assertEquals(1, platform.destroys)
        onMain { session.close(); session.close() }
    }

    fun testEveryTargetDimensionFencesCallbacksAfterEditorChanges() {
        val target = DictationDraftTarget("conversation", 7, 3, "draft-session")
        val alternatives = listOf(target.copy(conversationId = null), target.copy(conversationId = "other"),
            target.copy(editorGeneration = 8), target.copy(draftRevision = 4), target.copy(sessionId = "next"))
        for (next in alternatives) {
            val platform = Platform(); val host = Host(); var current = target
            val session = DraftDictationSession(AndroidDictation(platform, AudioOwnerGate()), target, host) { it == current }
            onMain { session.start() }; idle()
            val before = host.states.toList()
            onMain { current = next }
            platform.listener.onPartialResults(result("late partial"))
            platform.listener.onResults(result("late final")); idle()
            assertTrue(host.partials.isEmpty()); assertTrue(host.drafts.isEmpty())
            assertEquals(before, host.states); assertEquals(1, platform.destroys)
            onMain { session.close() }
        }
    }

    fun testPermissionDenialDoesNotPreemptReadingAndRequiresNewExplicitAttempt() {
        val platform = Platform().apply { permission = false }; val host = Host(); val gate = AudioOwnerGate()
        val readingOwner = VoiceOwner("reading", "run", "session")
        var cancelled = false
        val reading = gate.acquire(readingOwner) { cancelled = true }
        val target = DictationDraftTarget(null, 0, 0)
        val session = DraftDictationSession(AndroidDictation(platform, gate), target, host) { true }
        onMain { session.start() }; idle()
        assertEquals(listOf(target), host.permissions)
        assertEquals("recognizer_permission_denied", host.states.single().detail)
        assertTrue(gate.current(reading)); assertFalse(cancelled); assertEquals(0, platform.creates)
        platform.permission = true; idle(); assertEquals(0, platform.creates)
        onMain { session.close() }; assertTrue(gate.current(reading))
        val retry = DraftDictationSession(AndroidDictation(platform, gate), target.copy(sessionId = "retry"), host) { true }
        onMain { retry.start() }; idle()
        assertTrue(cancelled); assertEquals(1, platform.creates)
        onMain { retry.close() }; assertEquals(1, platform.cancels); assertEquals(1, platform.destroys)
    }

    fun testCloseBeforeStartAndPreemptionNeverAllowLateDraftOrCancelNewOwner() {
        val platform = Platform(); val host = Host(); val gate = AudioOwnerGate()
        val session = DraftDictationSession(AndroidDictation(platform, gate), DictationDraftTarget(null, 0, 0), host) { true }
        onMain { session.start(); session.close() }; idle()
        assertEquals(0, platform.creates)
        val active = DraftDictationSession(AndroidDictation(platform, gate), DictationDraftTarget(null, 1, 0), host) { true }
        onMain { active.start() }; idle()
        val listener = platform.listener
        val other = VoiceOwner("conversation", "run", "other-session")
        val lease = gate.acquire(other) {}
        assertEquals(1, platform.cancels); assertEquals(1, platform.destroys)
        onMain { active.close() }; assertTrue(gate.current(lease))
        listener.onResults(result("late")); idle(); assertTrue(host.drafts.isEmpty())
        gate.cancel(other)
    }
}
