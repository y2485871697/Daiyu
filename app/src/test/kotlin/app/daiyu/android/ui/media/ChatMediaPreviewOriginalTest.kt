package app.daiyu.android.ui.media

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaPreviewOriginalTest {
    @Test
    fun originalOwnsAndClosesTheAuthorizedStream() {
        val source = object : ByteArrayInputStream(byteArrayOf(0, 1, -1, 13, 10)) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        ChatMediaPreviewOriginal(source, "image/png", "photo.png").use {
            assertEquals("image/png", it.mimeType)
            assertEquals("photo.png", it.displayName)
            assertArrayEquals(byteArrayOf(0, 1, -1, 13, 10), it.stream.readBytes())
        }
        assertTrue(source.closed)
    }

    @Test
    fun originalClosesWhenCopyFails() {
        val source = object : ByteArrayInputStream(byteArrayOf(1)) {
            var closed = false
            override fun close() { closed = true }
        }
        try {
            ChatMediaPreviewOriginal(source, "video/mp4", "movie.mp4").use {
                throw IOException("copy failed")
            }
        } catch (_: IOException) {
            // A failing destination still releases the source.
        }
        assertTrue(source.closed)
    }
}
