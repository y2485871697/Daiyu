package app.daiyu.android.voice

import android.os.Handler
import android.os.Looper
import android.test.InstrumentationTestCase
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Real Android main dispatch/release, real player/controller, no TTS engine, audio, or network. */
@Suppress("DEPRECATION")
class ProductionSpeechReleaseSafetyTest : InstrumentationTestCase() {
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }

    private inner class Backend(private val failure: String) : SpeechBackend {
        override val maxInputLength = 4000
        val calls = mutableListOf<String>()
        var reenter: () -> Unit = {}
        override fun initialize(onReady: (SpeechError?) -> Unit, onEvent: (String, SpeechEvent, Int?) -> Unit) {
            requireMain(); onReady(null)
        }
        override fun configureOfflineLanguage(locale: Locale): SpeechError? { requireMain(); return null }
        override fun speak(text: String, utteranceId: String): Boolean { requireMain(); return true }
        override fun stop(): Boolean {
            requireMain(); calls += "stop"; reenter()
            if (failure == "stopThrow") error("private engine detail")
            return failure != "stopFalse"
        }
        override fun shutdown() {
            requireMain(); calls += "shutdown"
            if (failure == "shutdownThrow") error("private engine detail")
        }
    }

    fun testStrictReleaseReachesGateOnMainAndWorkerWithoutReentrantDeadlock() {
        val main = Handler(Looper.getMainLooper())
        for (fromMain in listOf(false, true)) for (failure in listOf("stopFalse", "stopThrow", "shutdownThrow")) {
            val owner = VoiceOwner("conversation", "run", "$fromMain-$failure")
            val backend = Backend(failure)
            val controller = SpeechPlaybackController({ backend }, { callback -> main.post { callback() }; Unit })
            val adapter = LocalProductionSpeechPlayer(AndroidSpeechPlayer(controller, ::requireMain))
            val output = ProductionSpeechOutput(owner, { adapter },
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                { action -> check(main.post { action() }) },
                { action -> releaseOnMain(main, action) },
                ::voiceEvent)
            val gate = AudioOwnerGate()
            val states = mutableListOf<VoiceState>()
            val session = ProductionVoiceSession(owner, gate, output, states::add)
            session.begin(); session.delta(owner, "一。")
            instrumentation.waitForIdleSync()
            assertEquals(SpeechPhase.QUEUED, controller.state.value.phase)
            // Exercise chain reentry on main while the releasing caller may be a worker thread.
            backend.reenter = { session.close() }
            val done = CountDownLatch(1)
            val caught = AtomicReference<Throwable?>()
            val release = {
                try { session.stop(owner) } catch (error: Throwable) { caught.set(error) }
                finally { done.countDown() }
            }
            if (fromMain) assertTrue(main.post { release() })
            else Thread({ release() }, "speech-release-fixture").apply { isDaemon = true; start() }
            assertTrue("release must finish without waiting on a reentrant chain lock", done.await(5, TimeUnit.SECONDS))
            assertTrue(caught.get() is IllegalStateException)
            assertEquals(listOf("stop", "shutdown"), backend.calls)
            assertEquals(VoicePhase.FAILED, states.last().phase)
            assertEquals("audio_release_failed", states.last().detail)
            assertFalse(states.last().firstSentenceStarted)
            assertEquals("none", states.last().playbackEvidence)
            assertEquals(SpeechPhase.CLOSED, controller.state.value.phase)
            try {
                gate.acquire(owner.copy(sessionId = "next")) {}
                fail("failed real release must poison gate")
            } catch (_: IllegalStateException) { }
        }
    }
}
