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

/** Complements the existing reentrancy cases at READY and backend disposal boundaries. */
class SpeechPlaybackBoundaryTest {
    private class FakeBackend(
        private val readySynchronously: Boolean,
        private val throwAfterReady: Boolean,
    ) : SpeechBackend {
        override val maxInputLength = 4000
        lateinit var onReady: (SpeechError?) -> Unit
        lateinit var onEvent: (String, SpeechEvent, Int?) -> Unit
        var stopHook: (() -> Boolean)? = null
        var shutdownHook: (() -> Unit)? = null
        var initializations = 0
        var configurations = 0
        var stops = 0
        var shutdowns = 0
        val spoken = mutableListOf<Pair<String, String>>()

        override fun initialize(onReady: (SpeechError?) -> Unit, onEvent: (String, SpeechEvent, Int?) -> Unit) {
            initializations++
            this.onReady = onReady
            this.onEvent = onEvent
            if (readySynchronously) onReady(null)
            if (throwAfterReady) error("Failure after synchronous ready")
        }

        override fun configureOfflineLanguage(locale: Locale): SpeechError? {
            configurations++
            return null
        }

        override fun speak(text: String, utteranceId: String): Boolean {
            spoken += text to utteranceId
            return true
        }

        override fun stop(): Boolean {
            stops++
            return stopHook?.invoke() ?: true
        }

        override fun shutdown() {
            shutdowns++
            shutdownHook?.invoke()
        }
    }

    private class Fixture {
        var readySynchronously = true
        var throwAfterReady = false
        val engines = mutableListOf<FakeBackend>()
        val player = SpeechPlaybackController(
            backendFactory = {
                FakeBackend(readySynchronously, throwAfterReady).also { engines += it }
            },
            dispatch = { it() },
        )
        val engine get() = engines.last()
        init { player.bindOwner("a") }
        fun play(owner: String = "a", id: String = "message", text: String = "original") =
            player.play(owner, id, text, Locale.US)
    }

    private enum class ReadyAction { STOP, CLOSE, BIND_OWNER, PLAY }
    private enum class DisposalOrigin { BIND_OWNER, STOP_INITIALIZING, INITIALIZATION_FAILURE }

    // Match the existing test's real StateFlow observer: no fake subscription API or scheduler drain.
    private fun observe(
        player: SpeechPlaybackController,
        failures: MutableList<Throwable>,
        observer: (SpeechPlaybackState) -> Unit,
    ): Job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
        player.state.collect { state ->
            try { observer(state) } catch (failure: Throwable) { failures += failure }
        }
    }

    @Test fun readyPublicationAllowsSynchronousStopCloseOwnerSwitchAndPlay() {
        for (action in ReadyAction.entries) for (mode in listOf("async", "sync", "syncThrow")) {
            val case = "$action/$mode"
            val f = Fixture().also {
                it.readySynchronously = mode != "async"
                it.throwAfterReady = mode == "syncThrow"
            }
            var deliveringReady = false
            var observations = 0
            val observerFailures = mutableListOf<Throwable>()
            val collector = observe(f.player, observerFailures) { state ->
                if (state.phase == SpeechPhase.READY) {
                    assertTrue("$case must reenter before READY delivery returns", deliveringReady)
                    observations++
                    assertEquals(SpeechPlaybackState("a", phase = SpeechPhase.READY), state)
                    when (action) {
                        ReadyAction.STOP -> assertTrue(f.player.stop("a"))
                        ReadyAction.CLOSE -> f.player.close()
                        ReadyAction.BIND_OWNER -> f.player.bindOwner("b")
                        ReadyAction.PLAY -> assertTrue(f.play(text = "explicit replacement"))
                    }
                }
            }
            try {
                deliveringReady = true
                assertTrue(f.player.initialize("a"))
                if (mode == "async") f.engine.onReady(null)
                deliveringReady = false

                val expected = when (action) {
                    ReadyAction.STOP -> SpeechPlaybackState("a", phase = SpeechPhase.STOPPED)
                    ReadyAction.CLOSE -> SpeechPlaybackState("a", phase = SpeechPhase.CLOSED)
                    ReadyAction.BIND_OWNER -> SpeechPlaybackState("b", phase = SpeechPhase.IDLE)
                    ReadyAction.PLAY -> SpeechPlaybackState("a", "message", SpeechPhase.QUEUED)
                }
                assertEquals(case, 1, observations)
                assertEquals(case, expected, f.player.state.value)
                assertEquals(case, 1, f.engines.size)
                assertEquals(case, 1, f.engine.initializations)
                assertEquals(case, 1, f.engine.stops)
                assertEquals(case, if (action in listOf(ReadyAction.CLOSE, ReadyAction.BIND_OWNER)) 1 else 0,
                    f.engine.shutdowns)
                assertEquals(case, if (action == ReadyAction.PLAY) 1 else 0, f.engine.configurations)
                val expectedSpeech = if (action == ReadyAction.PLAY)
                    listOf("explicit replacement" to "daiyu-tts-1") else emptyList()
                assertEquals(case, expectedSpeech, f.engine.spoken)

                // Duplicate readiness and unrelated utterance callbacks cannot resurrect prewarming.
                f.engine.onReady(null)
                f.engine.onReady(SpeechError.INITIALIZATION_FAILED)
                for (event in SpeechEvent.entries) f.engine.onEvent("never-submitted", event, -7)
                assertEquals(case, 1, observations)
                assertEquals(case, expected, f.player.state.value)
                assertEquals(case, expectedSpeech, f.engine.spoken)
                assertEquals(case, 1, f.engine.initializations)
                assertEquals(case, 1, f.engine.stops)
                if (action == ReadyAction.BIND_OWNER || action == ReadyAction.CLOSE) {
                    assertFalse(f.play(text = "rejected old-owner text"))
                    assertFalse(f.player.stop("a"))
                    assertFalse(f.player.initialize("a"))
                    assertEquals(case, expected, f.player.state.value)
                }
            } finally {
                collector.cancel()
                f.player.close()
                // This observer has an independent Job: fail on the JUnit call stack, not only that Job.
                observerFailures.firstOrNull()?.let { failure ->
                    observerFailures.drop(1).forEach { failure.addSuppressed(it) }
                    throw failure
                }
            }
            assertEquals(case, 1, f.engine.shutdowns)
            assertEquals(case, if (action in listOf(ReadyAction.STOP, ReadyAction.PLAY)) 2 else 1,
                f.engine.stops)
            f.player.close()
            assertEquals(case, 1, f.engine.shutdowns)
        }
    }

    @Test fun synchronousStopAndShutdownReentryCannotDisposeOrOverwriteReplacement() {
        // A ready engine's stop can replace the command on the same engine or install a new owner/engine.
        for (outerPlay in listOf(false, true)) for (switchOwner in listOf(false, true)) {
            for (outcome in listOf("success", "false", "throw")) {
                val case = "readyStop/play=$outerPlay/switch=$switchOwner/$outcome"
                val f = Fixture()
                assertTrue(f.play())
                val old = f.engine
                val oldToken = old.spoken.single().second
                var insideOuterCall = false
                var reentries = 0
                old.stopHook = {
                    old.stopHook = null // The replacement's own stop must not recursively run this hook.
                    assertTrue(case, insideOuterCall)
                    reentries++
                    if (switchOwner) f.player.bindOwner("b")
                    assertTrue(f.play(if (switchOwner) "b" else "a", text = "replacement"))
                    when (outcome) {
                        "throw" -> error("Old stop failed after reentry")
                        "false" -> false
                        else -> true
                    }
                }
                try {
                    insideOuterCall = true
                    val accepted = if (outerPlay) f.play(id = "obsolete", text = "must not be submitted")
                        else f.player.stop("a")
                    insideOuterCall = false
                    assertFalse(case, accepted)
                    assertEquals(case, 1, reentries)
                    val current = f.engine
                    val expected = SpeechPlaybackState(if (switchOwner) "b" else "a", "message", SpeechPhase.QUEUED)
                    assertEquals(case, expected, f.player.state.value)
                    assertEquals(case, if (switchOwner) 2 else 1, f.engines.size)
                    assertEquals(case, 1, old.initializations)
                    assertEquals(case, 2, old.stops)
                    assertEquals(case, if (switchOwner) 1 else 0, old.shutdowns)
                    assertEquals(case, if (switchOwner) 1 else 2, old.configurations)
                    if (switchOwner) {
                        assertNotSame(old, current)
                        assertEquals(case, listOf("original" to oldToken), old.spoken)
                        assertEquals(case, 1, current.initializations)
                        assertEquals(case, 1, current.configurations)
                        assertEquals(case, 0, current.stops)
                        assertEquals(case, 0, current.shutdowns)
                        assertEquals(case, listOf("replacement" to "daiyu-tts-2"), current.spoken)
                    } else {
                        assertSame(old, current)
                        assertEquals(case, listOf("original" to "daiyu-tts-1", "replacement" to "daiyu-tts-2"),
                            current.spoken)
                    }
                    assertEquals(case, "daiyu-tts-1", oldToken)
                    val currentToken = current.spoken.last().second
                    assertNotEquals(oldToken, currentToken)
                    val submitted = f.engines.map { it.spoken.toList() }
                    old.onReady(SpeechError.INITIALIZATION_FAILED)
                    for (event in SpeechEvent.entries) old.onEvent(oldToken, event, -8)
                    assertEquals(case, expected, f.player.state.value)
                    current.onEvent(currentToken, SpeechEvent.START, null)
                    assertEquals(case, expected.copy(phase = SpeechPhase.SPEAKING), f.player.state.value)
                    old.onEvent(oldToken, SpeechEvent.DONE, null)
                    assertEquals(case, expected.copy(phase = SpeechPhase.SPEAKING), f.player.state.value)
                    current.onEvent(currentToken, SpeechEvent.DONE, null)
                    assertEquals(case, expected.copy(phase = SpeechPhase.COMPLETED), f.player.state.value)
                    assertEquals(case, submitted, f.engines.map { it.spoken.toList() })
                } finally { f.player.close() }
                assertEquals(case, if (switchOwner) 2 else 3, old.stops)
                assertEquals(case, 1, old.shutdowns)
                if (switchOwner) {
                    assertEquals(case, 1, f.engine.stops)
                    assertEquals(case, 1, f.engine.shutdowns)
                }
                f.player.close()
                assertEquals(case, if (switchOwner) 2 else 3, old.stops)
                assertEquals(case, 1, f.engine.shutdowns)
            }
        }

        // Once detached, either disposal callback can install an engine that the old cleanup must not touch.
        for (origin in DisposalOrigin.entries) for (fromShutdown in listOf(false, true)) {
            for (switchOwner in listOf(false, true)) for (throwAfterReentry in listOf(false, true)) {
                val case = "$origin/shutdown=$fromShutdown/switch=$switchOwner/throw=$throwAfterReentry"
                val f = Fixture()
                f.readySynchronously = origin == DisposalOrigin.BIND_OWNER
                assertTrue(f.play())
                val old = f.engine
                f.readySynchronously = true
                val replacementOwner = if (switchOwner) "c" else if (origin == DisposalOrigin.BIND_OWNER) "b" else "a"
                var insideOuterCall = false
                var reentries = 0
                val replace: () -> Unit = {
                    assertTrue(case, insideOuterCall)
                    reentries++
                    if (switchOwner) f.player.bindOwner(replacementOwner)
                    assertTrue(f.play(replacementOwner, text = "replacement"))
                    if (throwAfterReentry) error("Old disposal failed after reentry")
                }
                if (fromShutdown) {
                    old.shutdownHook = { old.shutdownHook = null; replace() }
                } else {
                    old.stopHook = { old.stopHook = null; replace(); true }
                }
                try {
                    insideOuterCall = true
                    when (origin) {
                        DisposalOrigin.BIND_OWNER -> f.player.bindOwner("b")
                        DisposalOrigin.STOP_INITIALIZING -> assertFalse(f.player.stop("a"))
                        DisposalOrigin.INITIALIZATION_FAILURE -> old.onReady(SpeechError.INITIALIZATION_FAILED)
                    }
                    insideOuterCall = false
                    val current = f.engine
                    val expected = SpeechPlaybackState(replacementOwner, "message", SpeechPhase.QUEUED)
                    assertEquals(case, 1, reentries)
                    assertEquals(case, expected, f.player.state.value)
                    assertEquals(case, 2, f.engines.size)
                    assertNotSame(old, current)
                    assertEquals(case, 1, old.initializations)
                    assertEquals(case, 1, old.stops)
                    assertEquals(case, 1, old.shutdowns)
                    assertEquals(case, if (origin == DisposalOrigin.BIND_OWNER) 1 else 0, old.configurations)
                    assertEquals(case,
                        if (origin == DisposalOrigin.BIND_OWNER) listOf("original" to "daiyu-tts-1") else emptyList(),
                        old.spoken)
                    assertEquals(case, 1, current.initializations)
                    assertEquals(case, 1, current.configurations)
                    assertEquals(case, 0, current.stops)
                    assertEquals(case, 0, current.shutdowns)
                    assertEquals(case, listOf("replacement" to "daiyu-tts-2"), current.spoken)
                    val submitted = f.engines.map { it.spoken.toList() }
                    old.onReady(null)
                    old.onReady(SpeechError.INITIALIZATION_FAILED)
                    for (event in SpeechEvent.entries) old.onEvent("daiyu-tts-1", event, -9)
                    assertEquals(case, expected, f.player.state.value)
                    current.onEvent("daiyu-tts-2", SpeechEvent.START, null)
                    assertEquals(case, expected.copy(phase = SpeechPhase.SPEAKING), f.player.state.value)
                    current.onEvent("daiyu-tts-2", SpeechEvent.DONE, null)
                    assertEquals(case, expected.copy(phase = SpeechPhase.COMPLETED), f.player.state.value)
                    assertEquals(case, submitted, f.engines.map { it.spoken.toList() })
                } finally { f.player.close() }
                assertEquals(case, 1, old.stops)
                assertEquals(case, 1, old.shutdowns)
                assertEquals(case, 1, f.engine.stops)
                assertEquals(case, 1, f.engine.shutdowns)
                f.player.close()
                assertEquals(case, 1, f.engine.stops)
                assertEquals(case, 1, f.engine.shutdowns)
            }
        }
    }
}
