package app.daiyu.android

import android.Manifest
import android.content.pm.PackageManager
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import android.os.Bundle
import androidx.compose.ui.test.*
import app.daiyu.android.voice.*
import app.daiyu.android.voice.offline.*
import java.util.concurrent.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Real MainActivity/offline host/session/audio gate/ActivityResultRegistry; fake device/player only.
 * Permission results enter Activity.onRequestPermissionsResult, not the host callback directly.
 * No OS permission dialog, JNI model, microphone, network or speaker is used.
 */
@Suppress("DEPRECATION")
class DictationActivityTest : WiredActivityTestCase() {
    private class Platform {
        @Volatile var permission = true
        @Volatile var failDestroy = false
        @Volatile var creates = 0
        @Volatile var cancels = 0
        @Volatile var destroys = 0
        val listeners = CopyOnWriteArrayList<Attempt>()
    }
    private inner class Attempt(private val gate: AudioOwnerGate, private val owner: VoiceOwner,
        private val allowed: () -> Boolean, events: OfflineSpeechSession.Events) : OfflineSpeechAttempt {
        private val commands = ConcurrentLinkedQueue<Pair<String, CountDownLatch>>()
        private val wake = Semaphore(0)
        private val started = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val stoppedDelivered = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        private val finalReady = CountDownLatch(1)
        private val listening = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>()
        private val listeningPublished = CountDownLatch(1)
        @Volatile private var text = ""
        @Volatile private var endpoint = false
        private val device = object : OfflineSpeechDevice {
            override fun load() = object : OfflineSpeechDevice.Decoder {
                override fun accept(samples: FloatArray) = Unit
                override fun ready() = false
                override fun decode() = Unit
                override fun text() = text
                override fun endpoint() = endpoint
                override fun finish() { check(finalReady.await(5, TimeUnit.SECONDS)) { "final not supplied" } }
                override fun close() = Unit
            }
            override fun microphone(): OfflineSpeechDevice.Microphone {
                platform.creates++
                return object : OfflineSpeechDevice.Microphone {
                    override fun start() { started.countDown() }
                    override fun read(samples: ShortArray): Int {
                        val command = commands.poll() ?: return 0
                        text = command.first
                        command.second.countDown()
                        return 1
                    }
                    override fun release() {
                        platform.cancels++; platform.destroys++
                        released.countDown(); wake.release()
                        check(!platform.failDestroy) { "synthetic release failure" }
                    }
                }
            }
            override fun now() = android.os.SystemClock.elapsedRealtime()
            override fun idle() { wake.acquire() }
        }
        private var firstPost = true
        private val session = OfflineSpeechSession(gate, owner, allowed, device,
            object : OfflineSpeechSession.Events by events {
                override fun captureStopped() {
                    events.captureStopped()
                    stoppedDelivered.countDown()
                }
            },
            execute = Executor { task ->
                Thread({ try { task.run() } finally { finished.countDown() } }, "test-offline-device").start()
            },
            post = { callback ->
                if (firstPost) { firstPost = false; listening.set(callback); listeningPublished.countDown() }
                else android.os.Handler(android.os.Looper.getMainLooper()).post { callback() }
            })
        override fun start() { session.start() }
        override fun close() {
            try { session.close() } finally { finalReady.countDown(); wake.release() }
        }
        fun awaitStarted() {
            assertTrue("offline device opened", started.await(5, TimeUnit.SECONDS))
            assertTrue("offline listening callback published", listeningPublished.await(5, TimeUnit.SECONDS))
        }
        fun onReadyForSpeech(ignored: Bundle) {
            awaitStarted()
            mainThread { listening.get()?.invoke() }
        }
        fun onEndOfSpeech() {
            endpoint = true
            commands += "" to CountDownLatch(1)
            wake.release()
            assertTrue("capture stop delivered", stoppedDelivered.await(5, TimeUnit.SECONDS))
            assertEquals("capture stopped before final", 0L, released.count)
        }
        fun onPartialResults(value: String) {
            if (finished.count == 0L || host.state.value.target == null) return
            val consumed = CountDownLatch(1)
            commands += value to consumed; wake.release()
            assertTrue("partial consumed", consumed.await(5, TimeUnit.SECONDS))
            awaitControl("owned partial published or retired") {
                host.state.value.preview == value || host.state.value.target == null
            }
        }
        fun onResults(value: String) {
            text = value; endpoint = true; finalReady.countDown()
            commands += value to CountDownLatch(1); wake.release()
            assertTrue("offline attempt terminated", finished.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
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
    private lateinit var host: OfflineComposerHost
    private lateinit var speech: VoiceHost
    private val players = mutableListOf<Player>()
    private var permissionRequests = 0
    private var uiVoicePreferences: Pair<Boolean?, String?>? = null

    private fun configureUiVoicePreferences() {
        val context = instrumentation.targetContext
        val settings = context.getSharedPreferences("voice-settings", 0)
        val entry = context.getSharedPreferences("chat-voice-entry", 0)
        uiVoicePreferences = (if (settings.contains("input")) settings.getBoolean("input", false) else null) to
            entry.getString("mode", null)
        check(settings.edit().putBoolean("input", true).commit())
        check(entry.edit().putString("mode", ChatMenuAction.VOICE_INPUT.name).commit())
    }

    override fun cleanupAfterLegacy() {
        try {
            super.cleanupAfterLegacy()
        } finally {
            uiVoicePreferences?.let { (input, mode) ->
                val context = instrumentation.targetContext
                val settings = context.getSharedPreferences("voice-settings", 0).edit()
                if (input == null) settings.remove("input") else settings.putBoolean("input", input)
                check(settings.commit())
                val entry = context.getSharedPreferences("chat-voice-entry", 0).edit()
                if (mode == null) entry.remove("mode") else entry.putString("mode", mode)
                check(entry.commit())
                uiVoicePreferences = null
            }
        }
    }

    private fun field(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun call(name: String, vararg args: Any?): Any? = MainActivity::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }
        .apply { isAccessible = true }.invoke(activity, *args)

    override fun createFixture(): RunControlFixture {
        configureUiVoicePreferences()
        return super.createFixture().also { fixture ->
            runBlocking {
                fixture.controller.voiceRuntime.save(VoiceConfiguration(engine = "SYSTEM", dictation = "SYSTEM"))
            }
        }
    }
    override fun setUp() {
        super.setUp()
        awaitControl("SYSTEM dictation profile ready") {
            fixture.controller.voiceRuntime.configuration.value?.dictation == "SYSTEM"
        }
        platform = Platform()
        mainThread {
            val gate = AudioOwnerGate()
            speech = call("getSpeechHost") as VoiceHost
            field(speech, "gate", gate)
            val playerFactory: () -> VoiceHostPlayer = { Player().also { players += it } }
            field(speech, "factory", playerFactory)
            val ready: () -> Boolean = { true }
            field(activity, "offlineSpeechReady", ready)
            val factory: (VoiceOwner, () -> Boolean, OfflineSpeechSession.Events) -> OfflineSpeechAttempt =
                { owner, allowed, events -> Attempt(gate, owner, allowed, events).also { platform.listeners += it } }
            field(activity, "offlineSpeechSessionFactory", factory)
            host = call("getOfflineHost") as OfflineComposerHost
            val permission: () -> Boolean = { platform.permission }
            field(host, "permission", permission)
            val launcher: () -> Unit = {
                field(activity, "offlineMicrophonePermissionOwner", host)
                permissionRequests++
            }
            field(host, "requestPermission", launcher)
        }
    }

    private fun result(text: String) = text

    private fun voice(expectNewAttempt: Boolean = true) {
        val previousCount = platform.listeners.size
        ui.onNode(hasContentDescription("语音输入") or hasContentDescription("停止语音输入"))
            .assertIsDisplayed().assertIsEnabled().performClick()
        ui.waitForIdle()
        if (expectNewAttempt) {
            awaitControl("one new owned offline attempt after voice click") {
                platform.listeners.size == previousCount + 1 && host.state.value.target != null
            }
            platform.listeners[previousCount].awaitStarted()
        }
    }
    private fun permissionResult(granted: Boolean) {
        mainThread {
            // Resolve the offline dictation launcher's actual
            // randomized request code from the real registry; exercise RequestPermission parsing.
            val registry = activity.activityResultRegistry
            val map = registry.javaClass.superclass?.declaredFields.orEmpty().asSequence()
                .plus(registry.javaClass.declaredFields.asSequence())
                .mapNotNull { f ->
                    f.isAccessible = true
                    (f.get(registry) as? Map<*, *>)?.takeIf { it["activity_rq#4"] is Int }
                }.first()
            val code = map["activity_rq#4"] as Int
            // Substitute only the platform permission request, marking that same registered
            // launcher as in-flight just as ActivityResultRegistry.launch normally does.
            val launchedField = registry.javaClass.superclass!!.declaredFields.single {
                it.name.contains("launchedKeys", ignoreCase = true)
            }.apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val launched = launchedField.get(registry) as MutableCollection<String>
            launched.add("activity_rq#4")
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
        val firstTarget = checkNotNull(host.state.value.target)
        first.onReadyForSpeech(Bundle())
        awaitControl("same owned recognizer listening") {
            host.state.value.let { it.target == firstTarget && it.listening }
        }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithContentDescription("停止语音输入")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        ui.onNodeWithContentDescription("停止语音输入").assertExists()
        assertTrue(host.state.value.listening)
        first.onEndOfSpeech()
        awaitControl("same recognizer waiting for final, not capturing") {
            host.state.value.let { it.target == firstTarget && !it.listening }
        }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithContentDescription("语音输入")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
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
        setSelection(0, "original".length)
        platform.permission = false
        voice(expectNewAttempt = false)
        assertEquals(1, permissionRequests)
        assertEquals(0, platform.creates)
        permissionResult(false)
        assertTrue(host.state.value.status.contains("被拒绝"))
        assertDraft("original")
        voice(expectNewAttempt = false)
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

    fun testSystemDictationReplacesOnlyCapturedForwardSelection() {
        setDraft("leftRIGHTtail")
        setSelection(4, 9)
        voice()
        platform.listeners.last().onResults(result("middle"))
        instrumentation.waitForIdleSync()
        assertDraft("leftmiddletail")
        assertSelection(10, 10)
        assertEquals(0, fixture.opens.get())
    }

    fun testSystemDictationInsertsAtCursorAndPreservesSuffix() {
        setDraft("lefttail")
        setSelection(4, 4)
        voice()
        platform.listeners.last().onResults(result("middle"))
        instrumentation.waitForIdleSync()
        assertDraft("leftmiddletail")
        assertSelection(10, 10)
        assertEquals(0, fixture.opens.get())
    }

    fun testSystemDictationRejectsMovedSelectionEvenAfterItReturns() {
        setDraft("leftRIGHTtail")
        setSelection(9, 4)
        voice()
        val old = platform.listeners.last()
        setSelection(0, 0)
        setSelection(9, 4)
        old.onResults(result("stale"))
        instrumentation.waitForIdleSync()
        assertDraft("leftRIGHTtail")
        assertSelection(9, 4)
        assertEquals(0, fixture.opens.get())
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
        voice(expectNewAttempt = false) // Explicit stop.
        awaitControl("explicit stop retires old dictation target") { host.state.value.target == null }
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
        voice(expectNewAttempt = false) // Retire the failed old attempt.
        voice(expectNewAttempt = false) // A genuinely new attempt must hit the same poisoned gate.
        assertEquals(1, platform.creates)
        assertTrue(players.isEmpty())
        assertEquals(0, fixture.opens.get())
    }
}
