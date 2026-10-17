package app.daiyu.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.compose.ui.test.*
import app.daiyu.android.voice.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Real MainActivity/actions/editor/ActivityResultRegistry, fake recognizer/player only.
 * Permission results enter Activity.onRequestPermissionsResult, not the host callback directly.
 * No OS permission dialog, recognition service, microphone, network or speaker is used.
 */
@Suppress("DEPRECATION")
class DictationActivityTest : WiredActivityTestCase() {
    private class Platform : SystemDictationPlatform {
        var permission = true
        var failDestroy = false
        var creates = 0
        var cancels = 0
        var destroys = 0
        val listeners = mutableListOf<RecognitionListener>()
        override fun permitted() = permission
        override fun available() = true
        override fun create(): SystemDictationRecognizer {
            creates++
            return object : SystemDictationRecognizer {
                override fun listen(listener: RecognitionListener, language: String) { listeners += listener }
                override fun cancel() { cancels++ }
                override fun destroy() { destroys++; check(!failDestroy) { "synthetic release failure" } }
            }
        }
    }
    private class Player : VoiceHostPlayer {
        override val state = MutableStateFlow(SpeechPlaybackState())
        var closes = 0
        override fun bindOwner(owner: String) { state.value = SpeechPlaybackState(owner = owner) }
        override fun play(owner: String, requestId: String, text: String): Boolean {
            state.value = SpeechPlaybackState(owner, requestId, SpeechPhase.QUEUED)
            return true
        }
        override fun stop(owner: String) = error("must use strict release")
        override fun close() = error("best effort is not ownership release")
        override fun closeStrict() { closes++ }
    }
    private lateinit var platform: Platform
    private lateinit var host: ComposerDictationHost
    private lateinit var speech: VoiceHost
    private val players = mutableListOf<Player>()
    private var permissionRequests = 0

    private fun field(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun call(name: String, vararg args: Any?): Any? = MainActivity::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }
        .apply { isAccessible = true }.invoke(activity, *args)

    override fun createFixture() = super.createFixture().also { fixture ->
        runBlocking {
            fixture.controller.voiceRuntime.save(VoiceConfiguration(engine = "SYSTEM", dictation = "SYSTEM"))
        }
    }
    override fun setUp() {
        super.setUp()
        awaitControl("SYSTEM dictation profile ready") {
            fixture.controller.voiceRuntime.configuration.value?.dictation == "SYSTEM"
        }
        mainThread {
            assertFalse("System dictation fixture requires offline dictation disabled",
                app.daiyu.android.voice.offline.OfflineSpeechPack.state.value.enabled)
        }
        platform = Platform()
        mainThread {
            host = call("getDictationHost") as ComposerDictationHost
            speech = call("getSpeechHost") as VoiceHost
            // Isolate intentionally poisoned gates per test, never reset the production singleton.
            val gate = AudioOwnerGate()
            field(speech, "gate", gate)
            val playerFactory: () -> VoiceHostPlayer = { Player().also { players += it } }
            field(speech, "factory", playerFactory)
            val factory: (DictationDraftTarget, DraftDictationHost, (DictationDraftTarget) -> Boolean) -> DraftDictationSession =
                { target, callbacks, current -> DraftDictationSession(AndroidDictation(platform, gate), target, callbacks, current) }
            field(host, "factory", factory)
            val launcher: (DictationDraftTarget) -> Unit = { permissionRequests++ }
            field(host, "requestPermission", launcher)
        }
    }

    private fun result(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }
    private fun voice() { mainThread { retainedControls(activity).onVoice() } }
    private fun permissionResult(granted: Boolean) {
        mainThread {
            // Resolve the SYSTEM dictation launcher's actual
            // randomized request code from the real registry; exercise RequestPermission parsing.
            val registry = activity.activityResultRegistry
            val map = registry.javaClass.superclass?.declaredFields.orEmpty().asSequence()
                .plus(registry.javaClass.declaredFields.asSequence())
                .mapNotNull { f ->
                    f.isAccessible = true
                    (f.get(registry) as? Map<*, *>)?.takeIf { it["activity_rq#3"] is Int }
                }.first()
            val code = map["activity_rq#3"] as Int
            // Substitute only the platform permission request, marking that same registered
            // launcher as in-flight just as ActivityResultRegistry.launch normally does.
            val launchedField = registry.javaClass.superclass!!.declaredFields.single {
                it.name.contains("launchedKeys", ignoreCase = true)
            }.apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val launched = launchedField.get(registry) as MutableCollection<String>
            launched.add("activity_rq#3")
            activity.onRequestPermissionsResult(code, arrayOf(Manifest.permission.RECORD_AUDIO),
                intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED))
        }
    }

    fun testRealMainProjectsOnlyOwnedRecognizerListeningAndFencesLateReady() {
        setDraft("base")
        voice()
        val first = platform.listeners.last()
        ui.onNodeWithContentDescription("语音输入").assertExists() // A click/PREPARING is not listening.
        assertFalse(host.state.value.listening)
        first.onReadyForSpeech(Bundle())
        instrumentation.waitForIdleSync()
        ui.onNodeWithContentDescription("停止语音输入").assertExists()
        assertTrue(host.state.value.listening)
        first.onEndOfSpeech()
        instrumentation.waitForIdleSync()
        ui.onNodeWithContentDescription("语音输入").assertExists() // Waiting for final is not capture.
        assertFalse(host.state.value.listening)
        mainThread { activity.select(null) } // Same nullable conversation, a different editor owner.
        first.onReadyForSpeech(Bundle())
        instrumentation.waitForIdleSync()
        ui.onNodeWithContentDescription("语音输入").assertExists()
        assertFalse(host.state.value.listening)
        voice()
        val hidden = platform.listeners.last()
        ui.onNodeWithContentDescription("打开侧边栏").performClick()
        hidden.onReadyForSpeech(Bundle())
        instrumentation.waitForIdleSync()
        backToChat(activity)
        ui.onNodeWithContentDescription("语音输入").assertExists()
        assertFalse(host.state.value.listening)
        voice()
        val background = platform.listeners.last()
        mainThread { call("onPause") }
        background.onReadyForSpeech(Bundle())
        instrumentation.waitForIdleSync()
        mainThread { call("onResume") }
        ui.onNodeWithContentDescription("语音输入").assertExists()
        assertFalse(host.state.value.listening)
        assertEquals(0, fixture.opens.get())
    }

    fun testMainRunningIsNotRecognitionListening() {
        val session = fixture.session(holdSeconds = 120)
        setDraft("request"); node("chat-send").performClick(); awaitTextSendResult(activity)
        ui.onNodeWithContentDescription("语音输入").assertExists()
        ui.onNodeWithContentDescription("停止语音输入").assertDoesNotExist()
        assertFalse(host.state.value.listening)
        session.release.countDown()
        awaitControl("run completed") { fixture.controller.active == null }
    }

    fun testActivityPermissionDenialGrantAndExplicitRetryNeverSend() {
        setDraft("original")
        platform.permission = false
        voice()
        assertEquals(1, permissionRequests)
        assertEquals(0, platform.creates)
        permissionResult(false)
        assertTrue(host.state.value.status.contains("被拒绝"))
        assertDraft("original")
        voice()
        assertEquals(2, permissionRequests)
        permissionResult(true)
        assertTrue(host.state.value.status.contains("再次点击"))
        assertEquals(0, platform.creates) // grant alone is not a microphone admission.
        platform.permission = true
        voice()
        assertEquals(1, platform.creates)
        platform.listeners.last().onPartialResults(result("not final"))
        instrumentation.waitForIdleSync()
        assertDraft("original")
        assertEquals("not final", host.state.value.preview)
        platform.listeners.last().onResults(result("editable result"))
        instrumentation.waitForIdleSync()
        assertDraft("editable result")
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.active)
        assertEquals(1, platform.destroys)
    }

    fun testActivityEditReentryAndBackgroundReleaseAndDiscardOldTargets() {
        setDraft("base")
        voice()
        val edited = platform.listeners.last()
        setDraft("user edit")
        edited.onResults(result("must not overwrite edit"))
        instrumentation.waitForIdleSync()
        assertDraft("user edit")
        voice()
        val reentered = platform.listeners.last()
        mainThread { activity.select(null) } // Same nullable conversation, new editor generation.
        reentered.onPartialResults(result("late preview"))
        reentered.onResults(result("must not overwrite new editor"))
        instrumentation.waitForIdleSync()
        assertDraft("user edit")
        assertEquals("", host.state.value.preview)
        voice()
        val background = platform.listeners.last()
        mainThread { call("onPause") }
        background.onResults(result("must not write while backgrounded"))
        instrumentation.waitForIdleSync()
        mainThread { call("onResume") }
        assertDraft("user edit")
        assertEquals(3, platform.cancels)
        assertEquals(3, platform.destroys)
        assertEquals(3, platform.creates) // Resume never reopens the microphone.
        assertEquals(0, fixture.opens.get())
    }

    fun testActivityNewSessionAndConversationDoNotAcceptOldFinalOrPromotePartial() {
        setDraft("base")
        voice()
        val old = platform.listeners.last()
        voice() // Explicit stop.
        voice() // Same editor/revision, different dictation sessionId.
        old.onResults(result("old session"))
        instrumentation.waitForIdleSync()
        assertDraft("base")
        val current = platform.listeners.last()
        current.onPartialResults(result("preview only"))
        current.onResults(result(""))
        instrumentation.waitForIdleSync()
        assertDraft("base")
        voice()
        val oldConversation = platform.listeners.last()
        val other = fixture.database.create()
        fixture.database.draft(other.id, "other draft")
        mainThread { activity.select(other) }
        oldConversation.onResults(result("old conversation"))
        instrumentation.waitForIdleSync()
        assertDraft("other draft")
        assertEquals(0, fixture.opens.get())
    }

    fun testActivityDestructionReleasesAndRecreationNeverReopensMicrophone() {
        setDraft("base")
        voice()
        val old = platform.listeners.last()
        val gateField = MainActivity::class.java.getDeclaredField("audioGate").apply { isAccessible = true }
        val processGate = gateField.get(activity)
        activity = recreateActivity(activity)
        assertSame(processGate, gateField.get(activity))
        old.onResults(result("old activity"))
        instrumentation.waitForIdleSync()
        assertDraft("base")
        assertEquals(1, platform.cancels)
        assertEquals(1, platform.destroys)
        assertEquals(1, platform.creates)
    }

    fun testActivityReadAloudPreemptsDictationAndLateResultsCannotWrite() {
        setDraft("base")
        voice()
        val late = platform.listeners.last()
        mainThread { assertTrue(speech.speak(speech.owner, "answer", "visible answer")) }
        assertEquals(1, platform.cancels)
        assertEquals(1, platform.destroys)
        assertEquals(1, players.size)
        late.onResults(result("late"))
        instrumentation.waitForIdleSync()
        assertDraft("base")
        voice() // Symmetric takeover must strictly close the old TTS player first.
        assertEquals(1, players.single().closes)
        assertEquals(2, platform.creates)
    }

    fun testActivityStrictRecognizerReleaseFailureNeverAdmitsReadingOrNewMicrophone() {
        voice()
        platform.failDestroy = true
        mainThread { assertFalse(speech.speak(speech.owner, "answer", "must not play")) }
        assertTrue(players.isEmpty())
        assertEquals(1, platform.cancels)
        assertEquals(1, platform.destroys)
        voice() // Retire the failed old attempt.
        voice() // A genuinely new attempt must hit the same poisoned gate.
        assertEquals(1, platform.creates)
        assertTrue(players.isEmpty())
        assertEquals(0, fixture.opens.get())
    }
}
