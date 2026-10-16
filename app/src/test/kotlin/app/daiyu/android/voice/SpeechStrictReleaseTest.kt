package app.daiyu.android.voice

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real controller, including resources detached before a strict close is requested. */
class SpeechStrictReleaseTest {
    private class Backend : SpeechBackend {
        override val maxInputLength = 4000
        lateinit var ready: (SpeechError?) -> Unit
        lateinit var event: (String, SpeechEvent, Int?) -> Unit
        var stopResult = true
        var stopThrows = false
        var shutdownThrows = false
        var stopHook: (() -> Unit)? = null
        var shutdownHook: (() -> Unit)? = null
        val calls = mutableListOf<String>()
        override fun initialize(onReady: (SpeechError?) -> Unit, onEvent: (String, SpeechEvent, Int?) -> Unit) {
            ready = onReady
            event = onEvent
        }
        override fun configureOfflineLanguage(locale: Locale): SpeechError? = null
        override fun speak(text: String, utteranceId: String): Boolean = true
        override fun stop(): Boolean {
            calls += "stop"
            stopHook?.invoke()
            if (stopThrows) error("private stop exception")
            return stopResult
        }
        override fun shutdown() {
            calls += "shutdown"
            shutdownHook?.invoke()
            if (shutdownThrows) error("private shutdown exception")
        }
    }

    private fun controller(backend: Backend) = SpeechPlaybackController({ backend }, { it() }).also {
        it.bindOwner("owner")
        assertTrue(it.initialize("owner"))
    }

    private fun releaseFailure(action: () -> Unit) {
        try {
            action()
            fail("strict release must reject unconfirmed disposal")
        } catch (failure: IllegalStateException) {
            assertEquals("speech_release_failed", failure.message)
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
        }
    }

    @Test fun strictCloseAttemptsBothOperationsAndRetainsSanitizedFailure() {
        for (stop in listOf("success", "false", "throw")) for (shutdownThrows in listOf(false, true)) {
            val backend = Backend().also {
                it.stopResult = stop != "false"
                it.stopThrows = stop == "throw"
                it.shutdownThrows = shutdownThrows
            }
            val player = controller(backend) // Even an engine still initializing must be stopped/shut down.
            val fails = stop != "success" || shutdownThrows
            if (fails) releaseFailure { player.closeStrict() } else player.closeStrict()
            assertEquals(listOf("stop", "shutdown"), backend.calls)
            assertEquals(SpeechPhase.CLOSED, player.state.value.phase)
            assertFalse(player.initialize("owner"))
            assertFalse(player.play("owner", "late", "not submitted", Locale.US))
            player.close() // Ordinary lifecycle cleanup stays nonthrowing after a strict failure.
            if (fails) releaseFailure { player.closeStrict() } else player.closeStrict()
            assertEquals(listOf("stop", "shutdown"), backend.calls)
            backend.ready(null)
            for (event in SpeechEvent.entries) backend.event("late", event, -8)
            assertEquals(SpeechPhase.CLOSED, player.state.value.phase)
        }
    }

    @Test fun lenientCloseStillSwallowsEngineFailuresButCannotEraseThemForStrictCaller() {
        val backend = Backend().also { it.stopThrows = true; it.shutdownThrows = true }
        val player = controller(backend)
        player.close()
        player.close()
        assertEquals(listOf("stop", "shutdown"), backend.calls)
        releaseFailure { player.closeStrict() }
    }

    @Test fun strictCloseRemembersFailedDisposalDuringInitializationFailureOrOwnerReplacement() {
        for (origin in listOf("initialization", "owner", "stop")) {
            val backend = Backend().also { it.shutdownThrows = true }
            val player = controller(backend)
            when (origin) {
                "initialization" -> backend.ready(SpeechError.INITIALIZATION_FAILED)
                "owner" -> player.bindOwner("new-owner")
                "stop" -> assertTrue(player.stop("owner"))
            }
            assertEquals(listOf("stop", "shutdown"), backend.calls)
            // No backend remains attached here: merely checking the last close would falsely succeed.
            releaseFailure { player.closeStrict() }
            assertEquals(listOf("stop", "shutdown"), backend.calls)
        }
    }

    @Test fun reentrantStrictCloseCannotClaimSuccessWhileDisposalIsStillInFlight() {
        for (boundary in listOf("stop", "shutdown")) {
            val backend = Backend()
            val player = controller(backend)
            var reentries = 0
            val hook = {
                reentries++
                releaseFailure { player.closeStrict() }
                assertFalse(player.play("owner", "late", "not submitted", Locale.US))
                backend.ready(null)
                backend.event("late", SpeechEvent.START, null)
            }
            if (boundary == "stop") backend.stopHook = hook else backend.shutdownHook = hook
            player.closeStrict()
            assertEquals(1, reentries)
            assertEquals(listOf("stop", "shutdown"), backend.calls)
            assertEquals(SpeechPhase.CLOSED, player.state.value.phase)
            player.closeStrict() // After the successful outer disposal, repeated strict close is safe.
        }
    }

    @Test fun reentrantFactoryCloseCannotCertifyAnEngineThatHasNotYetBeenReturned() {
        val backend = Backend()
        lateinit var player: SpeechPlaybackController
        var reentries = 0
        player = SpeechPlaybackController({
            reentries++
            releaseFailure { player.closeStrict() }
            backend
        }, { it() })
        player.bindOwner("owner")
        player.initialize("owner")
        assertEquals(1, reentries)
        assertEquals(listOf("stop", "shutdown"), backend.calls)
        assertEquals(SpeechPhase.CLOSED, player.state.value.phase)
        player.closeStrict()
    }
}
