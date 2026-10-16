package app.daiyu.android.voice

import java.util.ArrayDeque
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SpeechPlaybackControllerTest {
    private class FakeBackend : SpeechBackend {
        override var maxInputLength = 4000
        lateinit var onReady: (SpeechError?) -> Unit
        lateinit var onEvent: (String, SpeechEvent, Int?) -> Unit
        val spoken = mutableListOf<Pair<String, String>>()
        var languageError: SpeechError? = null
        var speakResult = true
        var stopResult = true
        var stops = 0
        var shutdowns = 0
        var throwOnStop = false
        var throwOnConfigure = false
        var configuredLocale: Locale? = null
        override fun initialize(onReady: (SpeechError?) -> Unit, onEvent: (String, SpeechEvent, Int?) -> Unit) {
            this.onReady = onReady
            this.onEvent = onEvent
        }
        override fun configureOfflineLanguage(locale: Locale): SpeechError? {
            if (throwOnConfigure) error("Engine implementation failure")
            configuredLocale = locale
            return languageError
        }
        override fun speak(text: String, utteranceId: String): Boolean {
            spoken += text to utteranceId
            return speakResult
        }
        override fun stop(): Boolean {
            stops++
            if (throwOnStop) error("Engine implementation failure")
            return stopResult
        }
        override fun shutdown() { shutdowns++ }
        fun event(event: SpeechEvent, token: String = spoken.last().second, code: Int? = null) {
            onEvent(token, event, code)
        }
    }

    private class Fixture {
        val callbacks = ArrayDeque<() -> Unit>()
        val engines = mutableListOf<FakeBackend>()
        val player = SpeechPlaybackController(
            backendFactory = { FakeBackend().also { engines += it } },
            dispatch = { callbacks.addLast(it); Unit },
        )
        val engine get() = engines.last()
        val state get() = player.state.value
        init { player.bindOwner("session-a") }
        fun play(id: String = "message-1", text: String = "Selected text") =
            player.play("session-a", id, text, Locale.CHINA)
        fun drain() { while (callbacks.isNotEmpty()) callbacks.removeFirst().invoke() }
        fun ready(error: SpeechError? = null) { engine.onReady(error); drain() }
    }

    @Test fun bindingAndPrewarmingNeverSpeakAutomatically() {
        val f = Fixture()
        assertEquals(SpeechPhase.IDLE, f.state.phase)
        assertTrue(f.engines.isEmpty())
        assertTrue(f.player.initialize("session-a"))
        assertEquals(SpeechPhase.INITIALIZING, f.state.phase)
        f.ready()
        assertEquals(SpeechPhase.READY, f.state.phase)
        assertTrue(f.engine.spoken.isEmpty())
    }

    @Test fun asynchronousInitPlaysOnlyLatestExplicitRequestAndReportsProgress() {
        val f = Fixture()
        assertTrue(f.play("first", "Old"))
        assertTrue(f.play("second", "New"))
        assertTrue(f.engine.spoken.isEmpty())
        f.ready()
        assertEquals(listOf("New"), f.engine.spoken.map { it.first })
        assertEquals(Locale.CHINA, f.engine.configuredLocale)
        assertEquals("second", f.state.requestId)
        assertEquals(SpeechPhase.QUEUED, f.state.phase)
        f.engine.event(SpeechEvent.START)
        f.drain()
        assertEquals(SpeechPhase.SPEAKING, f.state.phase)
        f.engine.event(SpeechEvent.DONE)
        f.drain()
        assertEquals(SpeechPhase.COMPLETED, f.state.phase)
        f.engine.event(SpeechEvent.START)
        f.drain()
        assertEquals(SpeechPhase.COMPLETED, f.state.phase)
    }

    @Test fun stopDuringInitializationDropsTextAndFencesOldEngineCallback() {
        val f = Fixture()
        f.play()
        val old = f.engine
        assertTrue(f.player.stop("session-a"))
        assertEquals(1, old.shutdowns)
        old.onReady(null)
        f.drain()
        assertEquals(SpeechPhase.STOPPED, f.state.phase)
        assertTrue(old.spoken.isEmpty())
        f.play("again")
        f.ready()
        assertEquals(2, f.engines.size)
        assertEquals("again", f.state.requestId)
    }

    @Test fun switchingOwnerFencesInitAndRejectsOldOwnerActions() {
        val f = Fixture()
        f.play()
        val old = f.engine
        f.player.bindOwner("session-b")
        assertFalse(f.play())
        assertFalse(f.player.stop("session-a"))
        assertFalse(f.player.initialize("session-a"))
        old.onReady(null)
        f.drain()
        assertEquals("session-b", f.state.owner)
        assertEquals(SpeechPhase.IDLE, f.state.phase)
        assertTrue(old.spoken.isEmpty())
        assertEquals(1, old.shutdowns)
        assertTrue(f.player.play("session-b", "b", "New session", Locale.US))
        f.ready()
        assertEquals("b", f.state.requestId)
    }

    @Test fun oldUtteranceCallbacksCannotFinishReplacementWithSameMessageId() {
        val f = Fixture()
        f.play()
        f.ready()
        val oldToken = f.engine.spoken.last().second
        f.play() // Same message may be replayed; engine token must still be unique.
        val newToken = f.engine.spoken.last().second
        assertNotEquals(oldToken, newToken)
        f.engine.event(SpeechEvent.DONE, oldToken)
        f.engine.event(SpeechEvent.ERROR, oldToken, -8)
        f.drain()
        assertEquals(SpeechPhase.QUEUED, f.state.phase)
        f.engine.event(SpeechEvent.START, newToken)
        f.drain()
        assertEquals(SpeechPhase.SPEAKING, f.state.phase)
    }

    @Test fun queuedCallbacksAreFencedOnOwnerSwitchEvenWhenOwnerNameReturns() {
        val f = Fixture()
        f.play()
        f.ready()
        val old = f.engine
        old.event(SpeechEvent.START)
        f.player.bindOwner("session-b")
        f.player.bindOwner("session-a")
        f.play()
        f.ready()
        old.event(SpeechEvent.DONE)
        f.drain()
        assertEquals(SpeechPhase.QUEUED, f.state.phase)
        assertEquals(1, old.shutdowns)
    }

    @Test fun stopFencesPendingProgressAndDoesNotAutoResume() {
        val f = Fixture()
        f.play()
        f.ready()
        f.engine.event(SpeechEvent.START)
        assertTrue(f.player.stop("session-a"))
        f.engine.event(SpeechEvent.DONE)
        f.drain()
        assertEquals(SpeechPhase.STOPPED, f.state.phase)
        assertEquals(1, f.engine.spoken.size)
    }

    @Test fun missingLanguageOrOfflineVoiceNeverCallsSpeakAndCanRecover() {
        for (error in listOf(SpeechError.LANGUAGE_UNSUPPORTED, SpeechError.LANGUAGE_DATA_MISSING,
            SpeechError.OFFLINE_VOICE_UNAVAILABLE)) {
            val f = Fixture()
            f.play()
            f.engine.languageError = error
            f.ready()
            assertEquals(SpeechPhase.UNAVAILABLE, f.state.phase)
            assertEquals(error, f.state.error)
            assertTrue(f.engine.spoken.isEmpty())
            f.engine.languageError = null
            f.play("retry")
            assertEquals(SpeechPhase.QUEUED, f.state.phase)
        }
    }

    @Test fun initializationFailureReleasesEngineAndExplicitRetryCreatesNewOne() {
        for (error in listOf(SpeechError.ENGINE_UNAVAILABLE, SpeechError.INITIALIZATION_FAILED)) {
            val f = Fixture()
            f.play()
            val old = f.engine
            f.ready(error)
            assertEquals(SpeechPhase.UNAVAILABLE, f.state.phase)
            assertEquals(error, f.state.error)
            assertEquals(1, old.shutdowns)
            f.play("retry")
            old.onReady(null)
            f.ready()
            assertTrue(old.spoken.isEmpty())
            assertEquals(2, f.engines.size)
            assertEquals("retry", f.state.requestId)
        }
    }

    @Test fun duplicateInitializationCannotResetActivePlayback() {
        val f = Fixture()
        f.play()
        f.ready()
        f.engine.event(SpeechEvent.START)
        f.drain()
        f.ready(SpeechError.INITIALIZATION_FAILED)
        assertEquals(SpeechPhase.SPEAKING, f.state.phase)
        assertEquals(0, f.engine.shutdowns)
    }

    @Test fun emptyAndOverLimitTextAreRejectedWithoutTruncationOrSpeech() {
        val f = Fixture()
        assertFalse(f.play(text = " \n\t"))
        assertEquals(SpeechError.EMPTY_TEXT, f.state.error)
        assertTrue(f.engines.isEmpty())
        f.play(text = "12345")
        f.engine.maxInputLength = 4
        f.ready()
        assertEquals(SpeechError.TEXT_TOO_LONG, f.state.error)
        assertTrue(f.engine.spoken.isEmpty())
    }

    @Test fun synchronousAndAsynchronousPlaybackFailureAreVisibleAndFenced() {
        val f = Fixture()
        f.play()
        f.engine.speakResult = false
        f.ready()
        assertEquals(SpeechError.PLAYBACK_FAILED, f.state.error)
        f.engine.speakResult = true
        f.play("retry")
        f.engine.event(SpeechEvent.ERROR, code = -5)
        f.drain()
        assertEquals(SpeechPhase.ERROR, f.state.phase)
        assertEquals(-5, requireNotNull(f.state.platformErrorCode))
        f.engine.event(SpeechEvent.DONE)
        f.drain()
        assertEquals(SpeechPhase.ERROR, f.state.phase)
    }

    @Test fun stopFailureReleasesEngineAndNeverSubmitsReplacement() {
        val f = Fixture()
        f.play()
        f.ready()
        val old = f.engine
        old.stopResult = false
        assertFalse(f.play("replacement"))
        assertEquals(SpeechError.STOP_FAILED, f.state.error)
        assertEquals(1, old.spoken.size)
        assertEquals(1, old.shutdowns)
        f.play("retry")
        f.ready()
        assertEquals(2, f.engines.size)
    }

    @Test fun throwingEngineDoesNotLeakRawExceptionOrSkipShutdown() {
        val f = Fixture()
        f.play()
        f.engine.throwOnConfigure = true
        f.ready()
        assertEquals(SpeechError.OFFLINE_VOICE_UNAVAILABLE, f.state.error)
        f.engine.throwOnStop = true
        assertFalse(f.player.stop("session-a"))
        assertEquals(SpeechError.STOP_FAILED, f.state.error)
        assertEquals(1, f.engine.shutdowns)
    }

    @Test fun closeDuringInitIsTerminalAndIdempotent() {
        val f = Fixture()
        f.play()
        val old = f.engine
        f.player.close()
        f.player.close()
        old.onReady(null)
        f.drain()
        f.player.bindOwner("session-b")
        assertFalse(f.play())
        assertFalse(f.player.initialize("session-a"))
        assertFalse(f.player.stop("session-a"))
        assertEquals(SpeechPhase.CLOSED, f.state.phase)
        assertEquals(1, old.shutdowns)
        assertTrue(old.spoken.isEmpty())
    }

    @Test fun closeDuringSpeechAndForeignStopCannotAffectNewOwner() {
        val f = Fixture()
        f.play()
        f.ready()
        val old = f.engine
        f.player.bindOwner("session-b")
        f.player.play("session-b", "current", "New text", Locale.US)
        f.ready()
        val current = f.engine
        val stopCount = current.stops
        assertFalse(f.player.stop("session-a"))
        assertEquals(stopCount, current.stops)
        current.event(SpeechEvent.START)
        f.drain()
        assertEquals(SpeechPhase.SPEAKING, f.state.phase)
        f.player.close()
        old.event(SpeechEvent.ERROR, code = -1)
        current.event(SpeechEvent.DONE)
        f.drain()
        assertEquals(SpeechPhase.CLOSED, f.state.phase)
        assertEquals(1, old.shutdowns)
        assertEquals(1, current.shutdowns)
        assertTrue(current.stops > stopCount)
    }

    @Test fun factoryFailureIsReportedWithoutThrowingAndCanRetry() {
        var attempts = 0
        val engine = FakeBackend()
        val queue = ArrayDeque<() -> Unit>()
        val player = SpeechPlaybackController(
            backendFactory = { if (attempts++ == 0) error("Unavailable") else engine },
            dispatch = { queue.addLast(it); Unit },
        )
        player.bindOwner("owner")
        player.play("owner", "first", "text", Locale.US)
        assertEquals(SpeechError.ENGINE_UNAVAILABLE, player.state.value.error)
        player.play("owner", "retry", "text", Locale.US)
        engine.onReady(null)
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
        assertEquals(SpeechPhase.QUEUED, player.state.value.phase)
    }
}
