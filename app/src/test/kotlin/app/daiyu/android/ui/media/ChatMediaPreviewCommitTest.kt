package app.daiyu.android.ui.media

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaPreviewCommitTest {
    @Test
    fun closeFailureDoesNotUndoSuccessfulCommit() = runBlocking {
        var committed = false
        var closed = false
        val original = ChatMediaPreviewOriginal(object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closed = true; throw IOException("close failed") }
        }, "image/png", "photo.png")
        chatMediaPreviewCommitOriginal(original) { committed = true }
        assertTrue(committed)
        assertTrue(closed)
    }

    @Test
    fun cancellationRemainsPrimaryWhenClosingAlsoFails() = runBlocking {
        val cancellation = CancellationException("owner cancelled")
        val closeFailure = IOException("close failed")
        val original = ChatMediaPreviewOriginal(object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { throw closeFailure }
        }, "image/png", "photo.png")
        try {
            chatMediaPreviewCommitOriginal(original) { throw cancellation }
            throw AssertionError("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
            assertEquals(listOf(closeFailure), actual.suppressed.toList())
        }
    }

    @Test
    fun copyFailureRemainsPrimaryAndSourceIsClosed() = runBlocking {
        val copyFailure = IOException("copy failed")
        var closed = false
        val original = ChatMediaPreviewOriginal(object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closed = true }
        }, "image/png", "photo.png")
        try {
            chatMediaPreviewCommitOriginal(original) { throw copyFailure }
            throw AssertionError("Copy failure must propagate")
        } catch (actual: IOException) {
            assertSame(copyFailure, actual)
        }
        assertTrue(closed)
    }
}
