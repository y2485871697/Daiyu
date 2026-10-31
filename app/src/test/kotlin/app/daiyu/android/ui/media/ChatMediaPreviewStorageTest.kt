package app.daiyu.android.ui.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatMediaPreviewStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun mixedMediaUsesPicturesAndMovies() {
        assertEquals("Pictures", chatMediaPreviewDirectory("image/webp"))
        assertEquals("Movies", chatMediaPreviewDirectory("VIDEO/mp4"))
        assertFalse(chatMediaPreviewIsVideo("image/png"))
    }

    @Test
    fun filenameCannotEscapeDestination() {
        assertEquals("photo.png", chatMediaPreviewFileName("../../photo.png", "image/png"))
        assertEquals("movie.mp4", chatMediaPreviewFileName("C:\\private\\movie.mp4", "video/mp4"))
        assertEquals("image.jpg", chatMediaPreviewFileName("..", "image/png"))
        assertEquals("video.mp4", chatMediaPreviewFileName("", "video/mp4"))
        assertEquals("a_b.png", chatMediaPreviewFileName("a\nb.png", "image/png"))
    }

    @Test
    fun existingOriginalIsNotOverwritten() {
        val directory = temporary.newFolder()
        val first = chatMediaPreviewReserveFile(directory, "photo.png")
        first.writeBytes(byteArrayOf(7, 8))
        val second = chatMediaPreviewReserveFile(directory, "photo.png")
        assertEquals("photo (1).png", second.name)
        assertArrayEquals(byteArrayOf(7, 8), first.readBytes())
    }

    @Test
    fun copyPreservesAllOriginalBytesWithoutEncoding() = runTest {
        val original = ByteArray(150_000) { (it % 256).toByte() }
        val output = ByteArrayOutputStream()
        chatMediaPreviewCopy(ByteArrayInputStream(original), output)
        assertArrayEquals(original, output.toByteArray())
    }

    @Test(expected = IOException::class)
    fun copyDoesNotHideWriteFailures() = runTest {
        chatMediaPreviewCopy(ByteArrayInputStream(byteArrayOf(1)), object : OutputStream() {
            override fun write(value: Int) { throw IOException("destination unavailable") }
        })
    }
}
