package app.daiyu.android.voice

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.test.InstrumentationTestCase

/** Dictation-only fixtures extracted from retained 1a165ee5 AndroidSpeechSafetyTest. No live mic. */
@Suppress("DEPRECATION")
class AndroidDictationSafetyTest : InstrumentationTestCase() {
    private val owner = VoiceOwner("fixture-conversation", "fixture-run", "fixture-session")
    private val other = VoiceOwner("other-conversation", "other-run", "other-session")
    private fun idle() { instrumentation.waitForIdleSync() }
    private fun onMain(action: () -> Unit) { instrumentation.runOnMainSync { action() } }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }

    private inner class RecognizerFixture : SystemDictationPlatform, SystemDictationRecognizer {
        var permission = true; var available = true; var throwSecurity = false; var throwCreate = false
        var throwCancel = false; var throwDestroy = false
        var listener: RecognitionListener? = null
        var creates = 0; var cancels = 0; var destroys = 0
        override fun permitted() = permission
        override fun available(): Boolean { requireMain(); return available }
        override fun create(): SystemDictationRecognizer {
            requireMain(); creates++
            if (throwCreate) throw IllegalStateException("fixture create")
            return this
        }
        override fun listen(listener: RecognitionListener, language: String) {
            requireMain(); this.listener = listener
            if (throwSecurity) throw SecurityException("fixture permission revoked")
        }
        override fun cancel() { requireMain(); cancels++; if (throwCancel) throw IllegalStateException("fixture cancel") }
        override fun destroy() { requireMain(); destroys++; if (throwDestroy) throw IllegalStateException("fixture destroy") }
    }
    private class Host : DictationHost {
        var permissions = 0
        val states = mutableListOf<VoiceState>()
        val partials = mutableListOf<String>()
        val drafts = mutableListOf<ConfirmableTranscript>()
        override fun permissionRequired() { permissions++ }
        override fun partial(owner: VoiceOwner, text: String) { partials.add(text) }
        override fun finalDraft(owner: VoiceOwner, transcript: ConfirmableTranscript) { drafts.add(transcript) }
        override fun state(state: VoiceState) { states.add(state) }
    }
    private fun result(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    fun testRevokedDictationAdmissionHasNoPermissionOrOwnershipSideEffects() {
        val fixture = RecognizerFixture().apply { permission = false }
        val gate = AudioOwnerGate(); var preempted = false
        val previous = gate.acquire(other) { preempted = true }
        val host = Host()
        AndroidDictation(fixture, gate).start(owner, host, "zh-CN", { false })
        idle()
        assertTrue(gate.current(previous)); assertFalse(preempted)
        assertEquals(0, fixture.creates); assertEquals(0, host.permissions)
        assertTrue(host.states.isEmpty()); gate.release(previous)
    }

    fun testDictationPermissionDeniedNeverAcquiresOrCreatesAndRetryIsExplicit() {
        val fixture = RecognizerFixture().apply { permission = false }; val gate = AudioOwnerGate()
        var previousCancelled = false
        val lease = gate.acquire(other) { previousCancelled = true }
        val dictation = AndroidDictation(fixture, gate); val host = Host()
        dictation.start(owner, host); idle()
        assertEquals(1, host.permissions); assertEquals(0, fixture.creates)
        assertEquals("recognizer_permission_denied", host.states.single().detail)
        assertTrue(gate.current(lease)); assertFalse(previousCancelled)
        fixture.permission = true
        assertEquals(0, fixture.creates)
        dictation.start(owner, host); idle(); assertEquals(1, fixture.creates)
        assertTrue(previousCancelled); assertTrue(dictation.stop(owner))
    }

    fun testDictationUnavailableCreationAndRevokedPermissionReleaseLease() {
        for (mode in listOf("unavailable", "create", "revoked", "callback")) {
            val fixture = RecognizerFixture().apply {
                available = mode != "unavailable"; throwCreate = mode == "create"; throwSecurity = mode == "revoked"
            }
            val gate = AudioOwnerGate(); val host = Host(); val dictation = AndroidDictation(fixture, gate)
            dictation.start(owner, host); idle()
            if (mode == "callback") { fixture.listener!!.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); idle() }
            assertEquals(VoicePhase.FAILED, host.states.last().phase)
            if (mode == "revoked" || mode == "callback") {
                assertEquals("recognizer_permission_denied", host.states.last().detail); assertEquals(1, host.permissions)
            }
            assertFalse(dictation.stop(owner))
            val next = gate.acquire(other) {}; assertTrue(gate.current(next)); gate.release(next)
            assertEquals(if (mode == "revoked" || mode == "callback") 1 else 0, fixture.destroys)
        }
    }

    fun testDictationEmptyFinalDoesNotPromotePartialAndLateCallbacksAreIgnored() {
        val fixture = RecognizerFixture(); val host = Host(); val dictation = AndroidDictation(fixture, AudioOwnerGate())
        dictation.start(owner, host); idle()
        val listener = fixture.listener!!
        listener.onPartialResults(result("unconfirmed partial")); idle()
        listener.onResults(result("")); idle()
        assertEquals(listOf("unconfirmed partial"), host.partials)
        assertTrue(host.drafts.isEmpty()); assertEquals("empty_recognition", host.states.last().detail)
        val states = host.states.toList()
        listener.onResults(result("late")); listener.onError(SpeechRecognizer.ERROR_NETWORK); idle()
        assertEquals(states, host.states); assertEquals(1, fixture.destroys)
    }

    fun testDictationFinalIsConfirmableOnlyOnceAndStopDropsLateCallbacks() {
        val fixture = RecognizerFixture(); val host = Host(); val dictation = AndroidDictation(fixture, AudioOwnerGate())
        dictation.start(owner, host); idle()
        fixture.listener!!.onResults(result("final snapshot")); idle()
        val draft = host.drafts.single()
        assertEquals("edited", draft.confirm("edited")); assertNull(draft.confirm())
        assertEquals(VoicePhase.COMPLETE, host.states.last().phase)
        dictation.start(owner, host); idle(); val late = fixture.listener!!
        assertTrue(dictation.stop(owner)); val count = host.states.size
        late.onReadyForSpeech(null); late.onPartialResults(result("late")); late.onResults(result("late")); idle()
        assertEquals(count, host.states.size); assertEquals(1, host.drafts.size)
        assertEquals(1, fixture.cancels); assertEquals(2, fixture.destroys)
    }

    fun testDictationStopBeforeMainStartNeverCreatesRecognizer() {
        val fixture = RecognizerFixture(); val dictation = AndroidDictation(fixture, AudioOwnerGate()); val host = Host()
        onMain { dictation.start(owner, host); assertTrue(dictation.stop(owner)) }
        idle(); assertEquals(0, fixture.creates)
        assertEquals(VoicePhase.CANCELLED, host.states.single().phase)
    }

    fun testDictationCancelAlwaysDestroysAndFailedTeardownPoisonsGate() {
        val fixture = RecognizerFixture().apply { throwCancel = true }
        val gate = AudioOwnerGate(); val dictation = AndroidDictation(fixture, gate)
        dictation.start(owner, Host()); idle()
        try { dictation.stop(owner); fail("expected failed teardown") } catch (_: IllegalStateException) { }
        assertEquals(1, fixture.destroys)
        try { gate.acquire(other) {}; fail("expected poisoned gate") } catch (_: IllegalStateException) { }
    }

    fun testDictationNaturalDestroyFailureDoesNotPublishDraftOrReuseGate() {
        val fixture = RecognizerFixture().apply { throwDestroy = true }
        val gate = AudioOwnerGate(); val dictation = AndroidDictation(fixture, gate); val host = Host()
        dictation.start(owner, host); idle(); fixture.listener!!.onResults(result("draft")); idle()
        assertTrue(host.drafts.isEmpty()); assertEquals("recognizer_release_failed", host.states.last().detail)
        try { gate.acquire(other) {}; fail("expected poisoned gate") } catch (_: IllegalStateException) { }
    }

    fun testReleaseOnMainIsSynchronousAndRejectsNonMainHandler() {
        var released = false
        releaseOnMain(Handler(Looper.getMainLooper())) { requireMain(); released = true }
        assertTrue(released)
        onMain { releaseOnMain(Handler(Looper.getMainLooper())) { requireMain() } }
        try {
            releaseOnMain(Handler(Looper.getMainLooper())) { throw IllegalArgumentException("fixture release") }
            fail("expected release error")
        } catch (expected: IllegalStateException) { assertEquals("audio_release_failed", expected.message) }
        val thread = HandlerThread("fixture-not-main").apply { start() }
        try {
            try { releaseOnMain(Handler(thread.looper)) {}; fail("expected main-only check") }
            catch (expected: IllegalArgumentException) { assertEquals("audio_release_requires_main_handler", expected.message) }
        } finally { thread.quitSafely(); thread.join(1_000) }
    }
}
