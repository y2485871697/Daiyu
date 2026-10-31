package app.daiyu.android.ui.media

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMediaPreviewVideoLifecycleTest {
    @Before fun installMain() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test
    fun playbackOnlySeesPrivateVerifiedCopyAndItIsDeletedOnReturn() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val bytes = byteArrayOf(0, 3, -1, 7)
        var sourceClosed = false
        val source = object : ByteArrayInputStream(bytes) {
            override fun close() { sourceClosed = true; super.close() }
        }
        val item = ChatMediaPreviewItem("stable-owner-key", "video/mp4", "video.mp4", { null }) {
            ChatMediaPreviewOriginal(source, "video/mp4", "video.mp4")
        }
        var playbackFile: File? = null
        withChatMediaPreviewVideo(context, item) { file ->
            playbackFile = file
            assertTrue(sourceClosed)
            assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            assertArrayEquals(bytes, file.readBytes())
        }
        assertFalse(requireNotNull(playbackFile).exists())
    }

    @Test
    fun suspendedPlaybackKeepsFileUntilPageExitCancellationAndStopsBeforeDeletion() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val item = ChatMediaPreviewItem("owner-video", "video/mp4", "video.mp4", { null }) {
            ChatMediaPreviewOriginal(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "video/mp4", "video.mp4")
        }
        val started = CompletableDeferred<File>()
        var stoppedBeforeDeletion = false
        val playback = launch {
            withChatMediaPreviewVideo(context, item) { file ->
                try {
                    started.complete(file)
                    awaitCancellation()
                } finally {
                    // The VideoView adapter releases its decoder in this same lifetime.
                    stoppedBeforeDeletion = file.exists()
                }
            }
        }
        val file = started.await()
        assertTrue(file.exists())
        playback.cancelAndJoin()
        assertTrue(stoppedBeforeDeletion)
        assertFalse(file.exists())
    }

    @Test
    fun playbackFailureAlsoDeletesPrivateCopy() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val item = ChatMediaPreviewItem("stable-owner-key", "video/mp4", "video.mp4", { null }) {
            ChatMediaPreviewOriginal(ByteArrayInputStream(byteArrayOf(1)), "video/mp4", "video.mp4")
        }
        var playbackFile: File? = null
        try {
            withChatMediaPreviewVideo(context, item) { file ->
                playbackFile = file
                throw IOException("decoder unavailable")
            }
        } catch (_: IOException) {
            // Disposal must remove the private copy even when the decoder rejects it.
        }
        assertFalse(requireNotNull(playbackFile).exists())
    }
}
