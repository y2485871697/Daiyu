package app.daiyu.android

import android.app.Application
import android.media.MediaPlayer
import app.daiyu.android.voice.SpeechAudioFile
import app.daiyu.android.voice.SpeechAudioFixtures
import app.daiyu.android.voice.SpeechPhase
import app.daiyu.android.voice.account.AccountPreviewRequest
import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AccountPreviewSpeechPlayerTailTest {
    @After fun clearMediaFixtures() {
        ShadowMediaPlayer.resetStaticState()
    }

    @Test fun actualCompletionPublishesObservedTailBeforeHostCanRelease() = runTest {
        val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com",
            "fixture-only", "seed-tts-2.0", "fixture")
        val request = AccountPreviewRequest(config,
            DoubaoPersonalVoice("S_fixture", DoubaoPersonalVoice.accountFingerprint("fixture-only"), 2, setOf(5), true),
            "https://example.test/demo", demoOnly = true)
        val media = mutableListOf<Pair<MediaPlayer, ShadowMediaPlayer>>()
        ShadowMediaPlayer.setCreateListener { player, shadow -> media += player to shadow }
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(8000, -1) }
        val host = AccountPreviewSpeechPlayer(RuntimeEnvironment.getApplication(), backgroundScope, request, { true })
        host.bindOwner("owner")
        try {
            // Exercise native callbacks without issuing a real demo download.
            AccountPreviewSpeechPlayer::class.java.getDeclaredMethod("prepare", String::class.java,
                String::class.java, ByteArray::class.java).apply { isAccessible = true }
                .invoke(host, "owner", "preview", SpeechAudioFixtures.wave())
            val file = host.javaClass.getDeclaredField("file").apply { isAccessible = true }.get(host) as java.io.File
            assertArrayEquals(SpeechAudioFile.normalize(SpeechAudioFixtures.wave()), file.readBytes())
            val (player, shadow) = media.single()
            shadow.invokePreparedListener()
            assertTrue(player.isPlaying)
            assertEquals(SpeechPhase.SPEAKING, host.state.value.phase)
            shadow.setCurrentPosition(7324)
            shadow.invokeCompletionListener()
            assertEquals(SpeechPhase.COMPLETED, host.state.value.phase)
            assertEquals(826L, host.completionTailMs)
            assertEquals(ShadowMediaPlayer.State.PLAYBACK_COMPLETED, shadow.state)
            host.closeStrict()
            assertEquals(ShadowMediaPlayer.State.END, shadow.state)
            assertFalse(file.exists())
            // Late completion after strict close cannot change the last observed tail.
            shadow.setCurrentPosition(0)
            shadow.invokeCompletionListener()
            assertEquals(826L, host.completionTailMs)
        } finally { host.closeStrict() }
    }
}
