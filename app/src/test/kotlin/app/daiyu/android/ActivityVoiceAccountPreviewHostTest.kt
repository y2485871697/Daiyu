package app.daiyu.android

import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.SpeechPhase
import app.daiyu.android.voice.SpeechPlaybackState
import app.daiyu.android.voice.VoiceOwner
import app.daiyu.android.voice.account.AccountPreviewRequest
import app.daiyu.android.voice.account.VoiceAccountPreviewPort
import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

/** Dedicated gates are test-only: poisoning the process production singleton would pollute the suite. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ActivityVoiceAccountPreviewHostTest {
    private fun config(key: String = "test-key", model: String = "seed-tts-2.0", headers: Map<String, String> = emptyMap()) =
        SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", key, model, "saved-provider", headers)
    private fun request(config: SpeechProviderConfig = config(), sentKey: String = "test-key", id: String = "S_test") =
        AccountPreviewRequest(config, DoubaoPersonalVoice(id, DoubaoPersonalVoice.accountFingerprint(sentKey), 2, setOf(5), true),
            "https://untrusted.example.test/demo?private=never-fetch")
    private class Listener : VoiceAccountPreviewPort.Listener {
        var starts = 0; var stops = 0; var failures = 0
        override fun started() { starts++ }
        override fun stopped() { stops++ }
        override fun failed() { failures++ }
    }
    private class Player(val log: MutableList<String> = mutableListOf()) : VoiceHostPlayer {
        override val state = MutableStateFlow(SpeechPlaybackState())
        var owner = ""
        var id = ""
        var closes = 0
        var failClose = false
        var failBind = false
        var reject = false
        var text = ""
        override fun bindOwner(owner: String) { this.owner = owner; if (failBind) error("bind") }
        override fun play(owner: String, requestId: String, text: String): Boolean {
            this.id = requestId; this.text = text; log += "play"
            state.value = SpeechPlaybackState(owner, requestId, SpeechPhase.QUEUED)
            return !reject
        }
        fun emit(phase: SpeechPhase, owner: String = this.owner, id: String = this.id) {
            state.value = SpeechPlaybackState(owner, id, phase)
        }
        override fun stop(owner: String): Boolean = error("host must use strict close, not best-effort stop")
        override fun close(): Unit = error("best effort close forbidden")
        override fun closeStrict() { closes++; log += "strict-close"; if (failClose) error("drain") }
    }
    private class Fixture(scope: CoroutineScope, val frozen: SpeechProviderConfig) {
        val gate = AudioOwnerGate()
        val players = mutableListOf<Player>()
        var foreground = true
        var page = true
        var current = true
        var customize: (Player) -> Unit = { }
        val host = ActivityVoiceAccountPreviewHost(scope, frozen, { foreground && page && current }, gate) {
            Player().also { customize(it); players += it }
        }
    }

    @Test fun constructionAdmissionAndCloseDoNotCreatePlayersOrNetwork() = runTest {
        val f = Fixture(backgroundScope, config())
        f.host.admissionChanged()
        f.foreground = false
        val listener = Listener()
        f.host.play(request(), listener).stop()
        assertEquals(1, listener.failures)
        assertTrue(f.players.isEmpty())
        f.foreground = true; f.page = false
        f.host.play(request(), Listener())
        f.page = true; f.current = false
        f.host.play(request(), Listener())
        f.host.close()
        f.host.play(request(), Listener())
        assertTrue(f.players.isEmpty())
    }

    @Test fun strictMutationFenceProvesDrainAndDoesNotCreateAudioWhenIdle() = runTest {
        val f = Fixture(backgroundScope, config())
        assertTrue(f.host.stopPreviewStrictly())
        assertTrue(f.players.isEmpty())
        f.host.play(request(), Listener())
        assertTrue(f.host.stopPreviewStrictly())
        assertEquals(1, f.players.single().closes)
        assertTrue(f.host.stopPreviewStrictly())
        assertEquals(1, f.players.single().closes)
    }

    @Test fun strictMutationFenceCannotForgetNaturalCompletionReleaseFailure() = runTest {
        val f = Fixture(backgroundScope, config()); f.customize = { it.failClose = true }
        f.host.play(request(), Listener())
        f.players.single().emit(SpeechPhase.COMPLETED); runCurrent()
        advanceTimeBy(ActivityVoiceAccountPreviewHost.COMPLETION_TAIL_MS); runCurrent()
        assertFalse(f.host.stopPreviewStrictly())
        assertFalse(f.host.stopPreviewStrictly())
        assertEquals(1, f.players.single().closes)
    }

    @Test fun idlePreviewCannotClaimHealthAfterAnotherOwnersFailedRelease() = runTest {
        val f = Fixture(backgroundScope, config())
        val owner = VoiceOwner("foreign", "failed")
        f.gate.acquire(owner) { error("release unconfirmed") }
        try { f.gate.cancel(owner); fail("must poison") } catch (_: IllegalStateException) { }
        assertFalse(f.host.stopPreviewStrictly())
        assertTrue(f.players.isEmpty())
    }

    @Test fun exactConfigurationAndActualHeaderAccountAreRequired() = runTest {
        val frozen = config(headers = mapOf("x-api-key" to "override"))
        val f = Fixture(backgroundScope, frozen)
        val stale = Listener()
        f.host.play(request(config()), stale)
        assertEquals(1, stale.failures)
        f.host.play(request(frozen, sentKey = "test-key"), Listener())
        assertTrue(f.players.isEmpty())
        val good = request(config(headers = mapOf("x-api-key" to "override")), sentKey = "override")
        val lease = f.host.play(good, Listener())
        assertEquals(1, f.players.size)
        assertEquals(ActivityVoiceAccountPreviewHost.PREVIEW_TEXT, f.players.single().text)
        assertFalse(f.players.single().text.contains(good.demoUrl))
        lease.stop(); f.host.close()
    }

    @Test fun onlyRealMatchingSpeakingStartsAndLoadingStopStrictlyCloses() = runTest {
        val f = Fixture(backgroundScope, config()); val listener = Listener()
        val lease = f.host.play(request(), listener)
        val p = f.players.single()
        runCurrent()
        assertEquals(0, listener.starts)
        p.emit(SpeechPhase.SPEAKING, owner = "wrong"); runCurrent()
        p.emit(SpeechPhase.SPEAKING, id = "wrong"); runCurrent()
        assertEquals(0, listener.starts)
        p.emit(SpeechPhase.SPEAKING); runCurrent()
        assertEquals(1, listener.starts)
        p.emit(SpeechPhase.QUEUED); runCurrent()
        p.emit(SpeechPhase.SPEAKING); runCurrent()
        assertEquals(1, listener.starts)
        lease.stop()
        assertEquals(1, p.closes)
        runCurrent()
        assertEquals(1, listener.stops)
        p.emit(SpeechPhase.ERROR); runCurrent()
        assertEquals(0, listener.failures)
        f.host.close()
        // A second click stopped in QUEUED must release without inventing SPEAKING.
        val g = Fixture(backgroundScope, config()); val loading = Listener()
        g.host.play(request(), loading).stop(); runCurrent()
        assertEquals(0, loading.starts); assertEquals(1, loading.stops)
        assertEquals(1, g.players.single().closes)
    }

    @Test fun oldLeaseAndLateStateCannotStopOrStartNewPreview() = runTest {
        val f = Fixture(backgroundScope, config()); val old = Listener(); val fresh = Listener()
        val first = f.host.play(request(), old)
        val oldPlayer = f.players.single()
        f.host.play(request(id = "S_second"), fresh)
        val next = f.players.last()
        assertEquals(1, oldPlayer.closes)
        first.stop()
        oldPlayer.emit(SpeechPhase.SPEAKING); runCurrent()
        assertEquals(0, old.starts); assertEquals(0, fresh.starts)
        assertEquals(0, next.closes)
        next.emit(SpeechPhase.SPEAKING); runCurrent()
        assertEquals(1, fresh.starts)
        f.host.close(); runCurrent()
    }

    @Test fun parentRevocationStopsImmediatelyWithoutWaitingForAnotherStateOrClick() = runTest {
        for (change in listOf<(Fixture) -> Unit>({ it.current = false }, { it.page = false }, { it.foreground = false })) {
            val f = Fixture(backgroundScope, config()); val listener = Listener()
            f.host.play(request(), listener)
            change(f); f.host.admissionChanged()
            assertEquals(1, f.players.single().closes)
            runCurrent(); assertEquals(1, listener.stops)
        }
    }

    @Test fun sharedGateRevokesPreviousOwnerBeforeFactoryAndCompletionKeepsBoundedTail() = runTest {
        val gate = AudioOwnerGate(); val log = mutableListOf<String>(); val listener = Listener()
        gate.acquire(VoiceOwner("dictation", "old")) { log += "dictation-drained" }
        val p = Player(log)
        val host = ActivityVoiceAccountPreviewHost(backgroundScope, config(), { true }, gate) {
            log += "factory"; p
        }
        host.play(request(), listener)
        assertEquals(listOf("dictation-drained", "factory", "play"), log)
        p.emit(SpeechPhase.COMPLETED); runCurrent()
        advanceTimeBy(ActivityVoiceAccountPreviewHost.COMPLETION_TAIL_MS - 1); runCurrent()
        assertEquals(0, p.closes); assertEquals(0, listener.stops)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, p.closes); assertEquals(1, listener.stops)
        val next = gate.acquire(VoiceOwner("microphone", "new")) { }
        assertTrue(gate.current(next)); gate.release(next)
        host.close()
    }

    @Test fun preemptionDuringTailCancelsDelayAndStrictlyClosesBeforeNewAudio() = runTest {
        val f = Fixture(backgroundScope, config()); val listener = Listener()
        f.host.play(request(), listener)
        val p = f.players.single(); p.emit(SpeechPhase.COMPLETED); runCurrent()
        val next = f.gate.acquire(VoiceOwner("duplex", "new")) { assertEquals(1, p.closes) }
        assertEquals(1, p.closes)
        advanceTimeBy(500); runCurrent()
        assertEquals(1, p.closes); assertEquals(1, listener.stops)
        assertTrue(f.gate.current(next)); f.gate.cancel(next.owner)
    }

    @Test fun failedCloseNeverReportsStoppedAndPoisonsGateForEverySubsequentOwner() = runTest {
        val f = Fixture(backgroundScope, config()); f.customize = { it.failClose = true }
        val listener = Listener()
        f.host.play(request(), listener).stop(); runCurrent()
        assertEquals(1, listener.failures); assertEquals(0, listener.stops)
        val denied = Listener(); f.host.play(request(), denied); runCurrent()
        assertEquals(1, denied.failures); assertEquals(1, f.players.size)
        try { f.gate.acquire(VoiceOwner("other", "new")) { }; fail("unconfirmed release must poison gate") }
        catch (_: IllegalStateException) { }
        f.host.close()
    }

    @Test fun naturalCompletionDrainFailureAlsoPoisonsGateInsteadOfReportingStopped() = runTest {
        val f = Fixture(backgroundScope, config()); f.customize = { it.failClose = true }
        val listener = Listener(); f.host.play(request(), listener)
        f.players.single().emit(SpeechPhase.COMPLETED); runCurrent()
        advanceTimeBy(ActivityVoiceAccountPreviewHost.COMPLETION_TAIL_MS); runCurrent()
        assertEquals(1, listener.failures); assertEquals(0, listener.stops)
        try { f.gate.acquire(VoiceOwner("other", "new")) { }; fail("completion drain failure") }
        catch (_: IllegalStateException) { }
    }

    @Test fun previousOwnersFailedDrainNeverRunsFactoryAndAdmissionIsRecheckedAfterRevocation() = runTest {
        val f = Fixture(backgroundScope, config()); val listener = Listener()
        f.gate.acquire(VoiceOwner("old", "unknown")) { error("cannot drain old owner") }
        f.host.play(request(), listener); runCurrent()
        assertTrue(f.players.isEmpty()); assertEquals(1, listener.failures)
        val g = Fixture(backgroundScope, config())
        g.gate.acquire(VoiceOwner("old", "valid")) { g.current = false }
        val changed = Listener(); g.host.play(request(), changed); runCurrent()
        assertTrue(g.players.isEmpty()); assertEquals(1, changed.failures)
    }

    @Test fun bindFailureAndPlayRejectionBothStrictlyCloseWithoutStarted() = runTest {
        for (bind in listOf(true, false)) {
            val f = Fixture(backgroundScope, config())
            f.customize = { it.failBind = bind; it.reject = !bind }
            val listener = Listener(); f.host.play(request(), listener); runCurrent()
            assertEquals(1, f.players.single().closes)
            assertEquals(0, listener.starts); assertEquals(1, listener.failures)
            f.host.close()
        }
    }

    private class FakeCall(private val request: Request) : Call {
        lateinit var callback: Callback
        var cancelled = false
        override fun request() = request
        override fun execute(): Response = error("not used")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = ::callback.isInitialized
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall(request)
    }
    @Test fun cancelledContinuationIsNotEnoughHttpCallbackMustActuallyDrain() {
        lateinit var original: FakeCall
        val calls = AccountPreviewCalls(Call.Factory { FakeCall(it).also { original = it } })
        val call = calls.newCall(Request.Builder().url("https://example.test/").build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { }
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
        try { calls.cancelAndDrain(System.nanoTime()); fail("undrained callback must reject release") }
        catch (_: IllegalStateException) { }
        assertTrue(original.cancelled)
        original.callback.onFailure(original, IOException("cancelled"))
        calls.cancelAndDrain(System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
        try { calls.newCall(Request.Builder().url("https://example.test/").build()); fail("late HTTP creation") }
        catch (_: IllegalStateException) { }
    }
    @Test fun cancellationBeforeEnqueueForbidsLateEnqueueWithoutNetwork() {
        val calls = AccountPreviewCalls(Call.Factory { FakeCall(it) })
        val call = calls.newCall(Request.Builder().url("https://example.test/").build())
        calls.cancelAndDrain(System.nanoTime())
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { }
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
            fail("late enqueue")
        } catch (_: IllegalStateException) { }
    }
}
