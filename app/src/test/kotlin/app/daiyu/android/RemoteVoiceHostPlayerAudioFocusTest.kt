package app.daiyu.android

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import app.daiyu.android.voice.SpeechAudioFixtures
import app.daiyu.android.voice.SpeechAudioFile
import app.daiyu.android.voice.SpeechError
import app.daiyu.android.voice.SpeechPhase
import app.daiyu.android.voice.providers.VoiceHttpResponse
import app.daiyu.android.voice.providers.VoiceHttpTransport
import kotlinx.coroutines.*
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

    private fun newHost(frozen: FrozenVoiceSpeech = FrozenVoiceSpeech(config)): RemoteVoiceHostPlayer =
        RemoteVoiceHostPlayer(context, scope, runtime, frozen, VoiceHttpTransport { _, _ ->
            requests++
            VoiceHttpResponse(200, synthesizedContentType, synthesizedAudio)
        }).also { it.bindOwner("owner") }

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

}
