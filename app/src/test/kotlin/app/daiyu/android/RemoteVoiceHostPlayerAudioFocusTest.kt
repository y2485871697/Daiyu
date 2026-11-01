package app.daiyu.android

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import app.daiyu.android.voice.SpeechAudioFixtures
import app.daiyu.android.voice.SpeechAudioFile
import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.SpeechError
import app.daiyu.android.voice.SpeechPhase
import app.daiyu.android.voice.providers.VoiceHttpResponse
import app.daiyu.android.voice.providers.VoiceHttpTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteVoiceHostPlayerAudioFocusTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var context: Context
    private lateinit var audio: AudioManager
    private lateinit var host: RemoteVoiceHostPlayer
    private lateinit var runtime: ControllerVoiceRuntime
    private lateinit var config: VoiceConfiguration
    private val media = mutableListOf<Pair<MediaPlayer, ShadowMediaPlayer>>()
    private var requests = 0
    private var synthesizedContentType = "audio/mpeg"
    private var synthesizedAudio = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0, 0)

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
        audio = context.getSystemService(AudioManager::class.java)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowMediaPlayer.setCreateListener { player, shadow -> media += player to shadow }
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(1000, -1) }
        config = VoiceConfiguration(engine = "DOUBAO", endpoint = "https://openspeech.bytedance.com",
            key = "fixture-only", model = "seed-tts-2.0", provider = "fixture", voice = "zh_female_vv_uranus_bigtts")
        runtime = ControllerVoiceRuntime(context)
        runtime.javaClass.getDeclaredField("state").apply { isAccessible = true }
            .set(runtime, VoiceProfileEnvelope(mapOf(VoiceProfile.READ_ALOUD to config)))
        host = newHost()
    }

    private fun newHost(
        frozen: FrozenVoiceSpeech = FrozenVoiceSpeech(config),
        transport: VoiceHttpTransport = VoiceHttpTransport { _, _ ->
            requests++
            VoiceHttpResponse(200, synthesizedContentType, synthesizedAudio)
        },
        playerScope: CoroutineScope = scope,
    ): RemoteVoiceHostPlayer = RemoteVoiceHostPlayer(context, playerScope, runtime, frozen, transport)
        .also { it.bindOwner("owner") }

    private fun drainUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            dispatcher.scheduler.runCurrent()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertTrue(condition())
    }

    private fun assertGateReleased(gate: AudioOwnerGate) {
        assertTrue(gate.releaseHealthy())
        assertNull(gate.javaClass.getDeclaredField("lease").apply { isAccessible = true }.get(gate))
    }

    @After fun teardown() {
        runCatching { host.closeStrict() }
        scope.cancel()
        ShadowMediaPlayer.resetStaticState()
        Dispatchers.resetMain()
    }

    private fun preparing(id: String = "message"): Pair<MediaPlayer, ShadowMediaPlayer> {
        val before = media.size
        assertTrue(host.play("owner", id, "你好"))
        // Synthesis deliberately uses the production IO dispatcher. Only drain queued main work,
        // never advance virtual time through the playback watchdog.
        val timeout = System.nanoTime() + 5_000_000_000L
        while (media.size == before && host.state.value.phase != SpeechPhase.ERROR && System.nanoTime() < timeout) {
            dispatcher.scheduler.runCurrent()
            Thread.sleep(2)
        }
        assertEquals(SpeechPhase.QUEUED, host.state.value.phase)
        assertEquals(before + 1, media.size)
        return media.last()
    }

    @Test fun grantedFocusPrecedesActualStartAndCompletionReleasesTheSameRequest() {
        val (player, shadow) = preparing()
        assertFalse(player.isPlaying)
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        shadow.invokePreparedListener()
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.focusGain)
        assertEquals(android.media.AudioAttributes.USAGE_MEDIA, request.audioAttributes.usage)
        assertEquals(android.media.AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        assertTrue(request.willPauseWhenDucked())
        assertTrue(player.isPlaying)
        assertEquals(SpeechPhase.SPEAKING, host.state.value.phase)
        shadow.invokeCompletionListener()
        assertEquals(SpeechPhase.COMPLETED, host.state.value.phase)
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertEquals(ShadowMediaPlayer.State.END, shadow.state)
        assertEquals(1, requests)
    }

    @Test fun deniedFocusNeverStartsOrPretendsToSpeakAndDoesNotResynthesize() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val (_, shadow) = preparing()
        shadow.invokePreparedListener()
        assertEquals(SpeechPhase.ERROR, host.state.value.phase)
        assertEquals(SpeechError.AUDIO_FOCUS_DENIED, host.state.value.error)
        assertEquals(ShadowMediaPlayer.State.END, shadow.state)
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertEquals(1, requests)
    }

    @Test fun lossTransientAndDuckStopWithoutAutomaticResumeOrReplay() {
        for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
            val (_, shadow) = preparing("message-$loss")
            shadow.invokePreparedListener()
            val focusRequest = shadowOf(audio).lastAudioFocusRequest
            val request = focusRequest.audioFocusRequest
            focusRequest.listener.onAudioFocusChange(loss)
            assertEquals(SpeechPhase.ERROR, host.state.value.phase)
            assertEquals(SpeechError.AUDIO_FOCUS_LOST, host.state.value.error)
            assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
            assertEquals(ShadowMediaPlayer.State.END, shadow.state)
            focusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(SpeechPhase.ERROR, host.state.value.phase)
        }
        assertEquals(3, requests)
    }

    @Test fun oldFocusLossCannotStopTheNextTrackEvenForTheSameMessageId() {
        preparing().second.invokePreparedListener()
        val oldFocus = shadowOf(audio).lastAudioFocusRequest
        val old = oldFocus.audioFocusRequest
        val (current, shadow) = preparing()
        shadow.invokePreparedListener()
        val latest = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        oldFocus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertTrue(current.isPlaying)
        assertEquals(SpeechPhase.SPEAKING, host.state.value.phase)
        assertSame(old, shadowOf(audio).lastAbandonedAudioFocusRequest)
        host.closeStrict()
        host.closeStrict()
        assertSame(latest, shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertEquals(ShadowMediaPlayer.State.END, shadow.state)
    }

    @Test fun closeBeforePreparedFencesLateCallbackWithoutRequestingFocus() {
        val (_, shadow) = preparing()
        host.closeStrict()
        shadow.invokePreparedListener()
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        assertNotEquals(SpeechPhase.SPEAKING, host.state.value.phase)
    }

    @Test fun manualReadAloudStartsUnknownAndLongPreparedTracksAsInTheOriginal() {
        for (duration in listOf(-1, 0, 120_001)) {
            ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(duration, -1) }
            val (_, shadow) = preparing("manual-$duration")
            shadow.invokePreparedListener()
            assertEquals(SpeechPhase.SPEAKING, host.state.value.phase)
            assertEquals(ShadowMediaPlayer.State.STARTED, shadow.state)
        }
        assertEquals(3, requests)
    }

    @Test fun explicitUploadReservationStillUsesConsumedBudgetNotTheDefault() {
        for (duration in listOf(-1, 0, 1_001, 1_000)) {
            host.closeStrict()
            host = newHost(FrozenVoiceSpeech(config).withUpload(PersonalCascadeUpload { _, _ -> 1_000 }, 1_500))
            ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(duration, -1) }
            val (_, shadow) = preparing("bounded-$duration")
            shadow.invokePreparedListener()
            assertEquals(if (duration == 1_000) SpeechPhase.SPEAKING else SpeechPhase.ERROR, host.state.value.phase)
            if (duration == 1_000) {
                dispatcher.scheduler.runCurrent()
                dispatcher.scheduler.advanceTimeBy(1_000)
                dispatcher.scheduler.runCurrent()
                assertEquals(SpeechPhase.ERROR, host.state.value.phase)
                assertEquals(ShadowMediaPlayer.State.END, shadow.state)
            }
        }
        assertEquals(4, requests)
    }

    @Test fun unpluggingOutputStopsAndReleasesWithoutReplay() {
        preparing().second.invokePreparedListener()
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        context.sendBroadcast(android.content.Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(SpeechPhase.ERROR, host.state.value.phase)
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertEquals(1, requests)
    }

    @Test fun mediaFailureAlsoAbandonsFocusAndFailedReleaseRemainsStrict() {
        val (_, shadow) = preparing()
        shadow.invokePreparedListener()
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        shadow.invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        assertEquals(SpeechPhase.ERROR, host.state.value.phase)
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertEquals(ShadowMediaPlayer.State.END, shadow.state)
        assertTrue(runCatching { host.closeStrict() }.isFailure)
        assertTrue(runCatching { host.closeStrict() }.isFailure)
    }

    @Test fun nativePreparationCannotKeepMessageLoadingForever() {
        val (_, shadow) = preparing()
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(RemoteVoiceHostPlayer.PREPARATION_TIMEOUT_MS - 1)
        dispatcher.scheduler.runCurrent()
        assertEquals(SpeechPhase.QUEUED, host.state.value.phase)
        dispatcher.scheduler.advanceTimeBy(1)
        dispatcher.scheduler.runCurrent()
        assertEquals(SpeechPhase.ERROR, host.state.value.phase)
        assertEquals(SpeechError.PREPARATION_TIMEOUT, host.state.value.error)
        assertEquals(ShadowMediaPlayer.State.END, shadow.state)
        shadow.invokePreparedListener()
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        assertEquals(SpeechPhase.ERROR, host.state.value.phase)
        assertEquals(1, requests)
    }

    @Test fun preparedCancelsItsOwnPreparationDeadline() {
        val (_, shadow) = preparing()
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(RemoteVoiceHostPlayer.PREPARATION_TIMEOUT_MS - 1)
        dispatcher.scheduler.runCurrent()
        shadow.invokePreparedListener()
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(1)
        dispatcher.scheduler.runCurrent()
        assertEquals(SpeechPhase.SPEAKING, host.state.value.phase)
    }

    @Test fun synthesizedStreamedWaveIsFinalizedAtTheActualPlaybackBoundary() {
        val wave = SpeechAudioFixtures.wave()
        synthesizedContentType = "application/json"
        synthesizedAudio = ("{\"code\":0,\"audio\":\"" + java.util.Base64.getEncoder().encodeToString(wave) + "\"}").toByteArray()
        val (_, shadow) = preparing()
        val file = host.javaClass.getDeclaredField("file").apply { isAccessible = true }.get(host) as java.io.File
        assertArrayEquals(SpeechAudioFile.normalize(wave), file.readBytes())
        assertEquals(163532L, SpeechAudioFixtures.read(file.readBytes(), 74))
        shadow.invokePreparedListener()
        assertEquals(SpeechPhase.SPEAKING, host.state.value.phase)
        host.closeStrict()
        assertFalse(file.exists())
    }

    @Test fun runtimeInvalidationEndsQueuedMessageAndReleasesItsActualGate() {
        host.closeStrict()
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val response = CompletableDeferred<VoiceHttpResponse>()
        host = newHost(transport = VoiceHttpTransport { _, _ ->
            requests++; entered.countDown()
            try { response.await() } finally { cancelled.set(true) }
        })
        val gate = AudioOwnerGate()
        val message = VoiceHost(scope, gate) { host }
        message.enter("conversation"); message.setForeground(true)
        try {
            assertTrue(message.speak(message.owner, "message", "你好"))
            drainUntil { entered.count == 0L }
            assertEquals(SpeechPhase.QUEUED, message.state.value.phase)
            runtime.invalidateSpeech(VoiceProfile.CONVERSATION)
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeechPhase.QUEUED, message.state.value.phase)
            runtime.invalidateSpeech(VoiceProfile.READ_ALOUD)
            drainUntil { message.state.value.phase == SpeechPhase.STOPPED && cancelled.get() }
            assertGateReleased(gate)
            response.complete(VoiceHttpResponse(200, synthesizedContentType, synthesizedAudio))
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeechPhase.STOPPED, message.state.value.phase)
            assertTrue(media.isEmpty())
            assertEquals(1, requests)
        } finally { message.close() }
    }

    @Test fun synthesisCancellationDoesNotLeaveMessageOrLeaseQueued() {
        host.closeStrict()
        host = newHost(transport = VoiceHttpTransport { _, _ ->
            requests++
            throw CancellationException("fixture-only")
        })
        val gate = AudioOwnerGate()
        val message = VoiceHost(scope, gate) { host }
        message.enter("conversation"); message.setForeground(true)
        try {
            assertTrue(message.speak(message.owner, "message", "你好"))
            drainUntil { message.state.value.phase == SpeechPhase.ERROR }
            assertEquals(SpeechError.PLAYBACK_FAILED, message.state.value.error)
            assertGateReleased(gate)
            assertTrue(media.isEmpty())
            assertEquals(1, requests)
        } finally { message.close() }
    }

    @Test fun cancelledPlaybackScopeRejectsBeforePublishingQueuedOrCallingTransport() {
        scope.cancel()
        assertFalse(host.play("owner", "message", "你好"))
        assertFalse(voiceHostIsPreparing(host.state.value))
        dispatcher.scheduler.runCurrent()
        assertEquals(0, requests)
        assertTrue(media.isEmpty())
    }

    @Test fun cancellationBeforeLaunchStartsStillTerminatesObservedMessage() {
        host.closeStrict()
        val playbackScope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = AudioOwnerGate()
        host = newHost(playerScope = playbackScope)
        val message = VoiceHost(scope, gate) { host }
        message.enter("conversation"); message.setForeground(true)
        try {
            assertTrue(message.speak(message.owner, "message", "你好"))
            playbackScope.cancel()
            drainUntil { message.state.value.phase == SpeechPhase.ERROR }
            assertGateReleased(gate)
            assertEquals(0, requests)
            assertTrue(media.isEmpty())
        } finally { message.close(); playbackScope.cancel() }
    }

    @Test fun synthesisBudgetIncludesWaitingBeforeMediaExistsAndCancelsTransport() {
        host.closeStrict()
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val response = CompletableDeferred<VoiceHttpResponse>()
        host = newHost(transport = VoiceHttpTransport { _, _ ->
            requests++; entered.countDown()
            try { response.await() } finally { cancelled.set(true) }
        })
        val gate = AudioOwnerGate()
        val message = VoiceHost(scope, gate) { host }
        message.enter("conversation"); message.setForeground(true)
        try {
            assertTrue(message.speak(message.owner, "message", "你好"))
            drainUntil { entered.count == 0L }
            dispatcher.scheduler.advanceTimeBy(210_000L - 1)
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeechPhase.QUEUED, message.state.value.phase)
            assertTrue(media.isEmpty())
            dispatcher.scheduler.advanceTimeBy(1)
            drainUntil { message.state.value.phase == SpeechPhase.ERROR && cancelled.get() }
            assertEquals(SpeechError.SYNTHESIS_TIMEOUT, message.state.value.error)
            assertGateReleased(gate)
            response.complete(VoiceHttpResponse(200, synthesizedContentType, synthesizedAudio))
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeechError.SYNTHESIS_TIMEOUT, message.state.value.error)
            assertTrue(media.isEmpty())
            assertEquals(1, requests)
        } finally { message.close() }
    }

    @Test fun successfulSynthesisCancelsItsBudgetBeforePreparationAndPlayback() {
        host.closeStrict()
        host = newHost(FrozenVoiceSpeech(config, playbackLimitMs = 500_000L))
        val (_, shadow) = preparing()
        shadow.invokePreparedListener()
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(210_000L)
        dispatcher.scheduler.runCurrent()
        assertEquals(SpeechPhase.SPEAKING, host.state.value.phase)
    }

    @Test fun configurationBecomingStaleBeforeMediaCreationTerminatesTheRequest() {
        host.closeStrict()
        val entered = java.util.concurrent.CountDownLatch(1)
        val response = CompletableDeferred<VoiceHttpResponse>()
        host = newHost(transport = VoiceHttpTransport { _, _ -> entered.countDown(); response.await() })
        assertTrue(host.play("owner", "message", "你好"))
        drainUntil { entered.count == 0L }
        runtime.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(runtime, VoiceProfileEnvelope(emptyMap()))
        response.complete(VoiceHttpResponse(200, synthesizedContentType, synthesizedAudio))
        drainUntil { host.state.value.phase == SpeechPhase.ERROR }
        assertTrue(media.isEmpty())
    }

    @Test fun explicitClosePublishesTerminalOnlyAfterNativeResourcesAreReleased() {
        val (_, shadow) = preparing()
        var observedReleased = false
        val observation = scope.launch(UnconfinedTestDispatcher(dispatcher.scheduler), start = CoroutineStart.UNDISPATCHED) {
            host.state.collect { state ->
                if (state.phase == SpeechPhase.STOPPED) observedReleased = shadow.state == ShadowMediaPlayer.State.END
            }
        }
        try {
            host.closeStrict()
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeechPhase.STOPPED, host.state.value.phase)
            assertTrue(observedReleased)
        } finally { observation.cancel() }
    }

}
