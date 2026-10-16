package app.daiyu.android.voice

import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

/** Real StateFlow collectors, not a replacement callback abstraction or a delayed test dispatcher. */
class SpeechPlaybackReentrancyTest {
    private class FakeBackend : SpeechBackend {
        override val maxInputLength = 4000
        lateinit var onReady: (SpeechError?) -> Unit
        lateinit var onEvent: (String, SpeechEvent, Int?) -> Unit
        var readySynchronously = false
        var startSynchronously = false
        var throwAfterReady = false
        var throwAfterStart = false
        var throwOnStop = false
        var speakResult = true
        var configureHook: (() -> Unit)? = null
        var languageError: SpeechError? = null
        var initializations = 0
        var stops = 0
        var shutdowns = 0
        var speaking = false
        val spoken = mutableListOf<Pair<String, String>>()

        override fun initialize(onReady: (SpeechError?) -> Unit, onEvent: (String, SpeechEvent, Int?) -> Unit) {
            initializations++
            this.onReady = onReady
            this.onEvent = onEvent
            if (readySynchronously) onReady(null)
            if (throwAfterReady) error("Failure after synchronous ready")
        }

        override fun configureOfflineLanguage(locale: Locale): SpeechError? {
            configureHook?.invoke()
            return languageError
        }

        override fun speak(text: String, utteranceId: String): Boolean {
            spoken += text to utteranceId
            speaking = true
            if (startSynchronously) onEvent(utteranceId, SpeechEvent.START, null)
            if (throwAfterStart) error("Failure after synchronous start")
            return speakResult
        }

        override fun stop(): Boolean {
            stops++
            speaking = false
            if (throwOnStop) error("Stop failure")
            return true
        }

        override fun shutdown() {
            shutdowns++
            speaking = false
        }
    }

    private class Fixture(private val configure: (FakeBackend) -> Unit = {}) {
        val engines = mutableListOf<FakeBackend>()
        val player = SpeechPlaybackController(
            backendFactory = { FakeBackend().also { configure(it); engines += it } },
            dispatch = { it() },
        )
        val engine get() = engines.last()
        init { player.bindOwner("a") }
        fun play(id: String = "message", text: String = "selected") = player.play("a", id, text, Locale.US)
        fun prewarm() {
            player.initialize("a")
            engine.onReady(null)
        }
    }

    private enum class Cancellation { STOP, CLOSE, REBIND }

    private fun cancel(player: SpeechPlaybackController, action: Cancellation) {
        when (action) {
            Cancellation.STOP -> assertTrue(player.stop("a"))
            Cancellation.CLOSE -> player.close()
            Cancellation.REBIND -> player.bindOwner("b")
        }
    }

    private fun assertCancelled(player: SpeechPlaybackController, action: Cancellation) {
        val expected = when (action) {
            Cancellation.STOP -> SpeechPhase.STOPPED
            Cancellation.CLOSE -> SpeechPhase.CLOSED
            Cancellation.REBIND -> SpeechPhase.IDLE
        }
        assertEquals(expected, player.state.value.phase)
        assertEquals(if (action == Cancellation.REBIND) "b" else "a", player.state.value.owner)
        assertNull(player.state.value.error)
    }

    private fun observe(player: SpeechPlaybackController, observer: (SpeechPlaybackState) -> Unit): Job =
        CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            player.state.collect { observer(it) }
        }

    @Test fun initializingObserverCancelsBeforeFactoryForPlayAndPrewarm() {
        for (action in Cancellation.entries) for (prewarm in listOf(false, true)) {
            val f = Fixture()
            var observed = 0
            val collector = observe(f.player) {
                if (it.phase == SpeechPhase.INITIALIZING) {
                    observed++
                    cancel(f.player, action)
                }
            }
            try {
                if (prewarm) f.player.initialize("a") else f.play()
                assertEquals(1, observed)
                assertTrue("No backend may be created after cancellation", f.engines.isEmpty())
                assertCancelled(f.player, action)
            } finally { collector.cancel() }
            f.player.close()
        }
    }

    @Test fun initializingObserverCancelsReplacementAndReleasesExistingBackend() {
        for (action in Cancellation.entries) {
            val f = Fixture()
            f.play("old")
            val old = f.engine
            var observed = 0
            val collector = observe(f.player) {
                if (it.phase == SpeechPhase.INITIALIZING && it.requestId == "replacement") {
                    observed++
                    cancel(f.player, action)
                }
            }
            try {
                f.play("replacement")
                old.onReady(null)
                assertEquals(1, observed)
                assertEquals(1, f.engines.size)
                assertEquals(1, old.shutdowns)
                assertTrue(old.spoken.isEmpty())
                assertCancelled(f.player, action)
            } finally { collector.cancel() }
            f.player.close()
        }
    }

    @Test fun queuedObserverCancelsBeforeSpeakWithReadyAsyncAndSynchronousEngines() {
        for (action in Cancellation.entries) for (mode in listOf("ready", "async", "sync")) {
            val f = Fixture { it.readySynchronously = mode == "sync" }
            if (mode == "ready") f.prewarm()
            var observed = 0
            val collector = observe(f.player) {
                if (it.phase == SpeechPhase.QUEUED) {
                    observed++
                    cancel(f.player, action)
                }
            }
            try {
                f.play()
                if (mode == "async") f.engine.onReady(null)
                assertEquals("$action/$mode must observe QUEUED inline", 1, observed)
                assertTrue("$action/$mode must not submit speech", f.engine.spoken.isEmpty())
                assertTrue(f.engine.stops > 0)
                assertEquals(if (action == Cancellation.STOP) 0 else 1, f.engine.shutdowns)
                assertCancelled(f.player, action)
                f.engine.onReady(null) // A duplicate ready cannot resurrect cancelled text.
                assertTrue(f.engine.spoken.isEmpty())
                assertCancelled(f.player, action)
            } finally { collector.cancel() }
            f.player.close()
            assertEquals(1, f.engine.shutdowns)
        }
    }

    @Test fun speakingObserverCancellationSurvivesSynchronousSpeakReturnAndThrow() {
        for (action in Cancellation.entries) for (result in listOf("success", "failure", "throw")) {
            val f = Fixture {
                it.startSynchronously = true
                it.speakResult = result == "success"
                it.throwAfterStart = result == "throw"
            }
            f.prewarm()
            var observed = 0
            val collector = observe(f.player) {
                if (it.phase == SpeechPhase.SPEAKING) {
                    observed++
                    cancel(f.player, action)
                }
            }
            try {
                f.play()
                assertEquals("$action/$result must observe SPEAKING inline", 1, observed)
                assertEquals(1, f.engine.spoken.size)
                assertFalse("Cancellation must stop already submitted audio", f.engine.speaking)
                assertCancelled(f.player, action)
                val token = f.engine.spoken.single().second
                f.engine.onEvent(token, SpeechEvent.DONE, null)
                f.engine.onEvent(token, SpeechEvent.ERROR, -5)
                assertCancelled(f.player, action)
            } finally { collector.cancel() }
            f.player.close()
            assertEquals(1, f.engine.shutdowns)
        }
    }

    @Test fun closeDuringFactoryDisposesUnattachedEngineEvenWhenStopThrows() {
        for (action in Cancellation.entries) for (throwOnStop in listOf(false, true)) {
            val engine = FakeBackend().also { it.throwOnStop = throwOnStop }
            lateinit var player: SpeechPlaybackController
            player = SpeechPlaybackController(
                backendFactory = { cancel(player, action); engine },
                dispatch = { it() },
            )
            player.bindOwner("a")
            player.play("a", "message", "selected", Locale.US)
            assertCancelled(player, action)
            assertEquals(0, engine.initializations)
            assertEquals(1, engine.stops)
            assertEquals(1, engine.shutdowns)
            assertTrue(engine.spoken.isEmpty())
            player.close()
            assertEquals(1, engine.shutdowns)
        }
    }

    @Test fun factoryThrowAfterCloseDoesNotReplaceClosedWithUnavailable() {
        lateinit var player: SpeechPlaybackController
        player = SpeechPlaybackController(
            backendFactory = { player.close(); error("Factory failure") },
            dispatch = { it() },
        )
        player.bindOwner("a")
        player.play("a", "message", "selected", Locale.US)
        assertCancelled(player, Cancellation.CLOSE)
    }

    @Test fun initializeThrowAfterObserverCancellationDoesNotOverwriteNewStateOrDoubleRelease() {
        for (action in Cancellation.entries) for (phase in listOf(SpeechPhase.QUEUED, SpeechPhase.SPEAKING)) {
            val f = Fixture {
                it.readySynchronously = true
                it.startSynchronously = true
                it.throwAfterReady = true
            }
            var observed = 0
            val collector = observe(f.player) {
                if (it.phase == phase) {
                    observed++
                    cancel(f.player, action)
                }
            }
            try {
                f.play()
                assertEquals(1, observed)
                assertCancelled(f.player, action)
                assertEquals(if (phase == SpeechPhase.QUEUED) 0 else 1, f.engine.spoken.size)
                assertFalse(f.engine.speaking)
                assertEquals(if (action == Cancellation.STOP) 0 else 1, f.engine.shutdowns)
            } finally { collector.cancel() }
            f.player.close()
            assertEquals(1, f.engine.shutdowns)
        }
    }

    @Test fun configureCancellationCannotPublishStaleErrorOrQueueSpeech() {
        for (action in Cancellation.entries) {
            val f = Fixture()
            f.prewarm()
            f.engine.configureHook = { cancel(f.player, action) }
            f.engine.languageError = SpeechError.OFFLINE_VOICE_UNAVAILABLE
            f.play()
            assertCancelled(f.player, action)
            assertTrue(f.engine.spoken.isEmpty())
            f.player.close()
            assertEquals(1, f.engine.shutdowns)
        }
    }

    @Test fun queuedObserverReplacingSameMessageOnlySpeaksNewRequest() {
        val f = Fixture()
        f.prewarm()
        var replaced = false
        val collector = observe(f.player) {
            if (it.phase == SpeechPhase.QUEUED && !replaced) {
                replaced = true
                f.play(text = "replacement") // Same owner, engine and message ID, different command.
            }
        }
        try {
            f.play(text = "superseded")
            assertTrue(replaced)
            assertEquals(listOf("replacement"), f.engine.spoken.map { it.first })
            assertEquals(SpeechPhase.QUEUED, f.player.state.value.phase)
        } finally { collector.cancel(); f.player.close() }
    }

    @Test fun initializingObserverOwnerRoundTripDoesNotResumeOldCreation() {
        val f = Fixture()
        var switched = false
        val collector = observe(f.player) {
            if (it.phase == SpeechPhase.INITIALIZING && !switched) {
                switched = true
                f.player.bindOwner("b")
                f.player.bindOwner("a")
            }
        }
        try {
            f.play(text = "superseded")
            assertTrue(switched)
            assertTrue(f.engines.isEmpty())
            assertEquals(SpeechPhase.IDLE, f.player.state.value.phase)
            assertEquals("a", f.player.state.value.owner)
            f.play(text = "new explicit request")
            f.engine.onReady(null)
            assertEquals(listOf("new explicit request"), f.engine.spoken.map { it.first })
        } finally { collector.cancel(); f.player.close() }
    }

    @Test fun factoryFailureObserverCanRetryWithoutLosingNewPendingRequest() {
        val engine = FakeBackend()
        var attempts = 0
        val player = SpeechPlaybackController(
            backendFactory = { if (attempts++ == 0) error("Unavailable") else engine },
            dispatch = { it() },
        )
        player.bindOwner("a")
        var retried = false
        val collector = observe(player) {
            if (it.phase == SpeechPhase.UNAVAILABLE && !retried) {
                retried = true
                player.play("a", "retry", "replacement", Locale.US)
            }
        }
        try {
            player.play("a", "old", "superseded", Locale.US)
            assertTrue(retried)
            engine.onReady(null)
            assertEquals(listOf("replacement"), engine.spoken.map { it.first })
            assertEquals("retry", player.state.value.requestId)
            assertEquals(SpeechPhase.QUEUED, player.state.value.phase)
        } finally { collector.cancel(); player.close() }
    }
}
