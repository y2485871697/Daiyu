package app.daiyu.android.attachments

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.util.concurrent.CancellationException

class OpenedFileCopyTest {
    @Test fun copiesExactBytesAcrossManyChunks() {
        val bytes = ByteArray(140003) { (it % 251).toByte() }; val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), copyOpenedFile(ByteArrayInputStream(bytes), output, bytes.size.toLong()) { false })
        assertArrayEquals(bytes, output.toByteArray())
    }
    @Test fun permitsEmptyDocumentAtZeroLimit() {
        assertEquals(0L, copyOpenedFile(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 0) { false })
    }
    @Test fun rejectsFirstByteBeyondZeroWithoutWriting() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { copyOpenedFile(ByteArrayInputStream(byteArrayOf(1)), output, 0) { false } }
        assertEquals(0, output.size())
    }
    @Test fun checksRealStreamLengthRatherThanReportedMetadata() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { copyOpenedFile(ByteArrayInputStream(ByteArray(65540)), output, 65536) { false } }
        assertTrue(output.size() <= 65536)
    }
    @Test fun cancellationBeforeReadTouchesNeitherStream() {
        var reads = 0; val source = object : InputStream() { override fun read(): Int { reads++; return -1 } }
        assertThrows(CancellationException::class.java) { copyOpenedFile(source, ByteArrayOutputStream(), 5) { true } }
        assertEquals(0, reads)
    }
    @Test fun cancellationDuringReadStopsNextChunk() {
        var stop = false; var reads = 0
        val source = object : ByteArrayInputStream(ByteArray(100000)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; stop = true; return super.read(buffer, offset, length) }
        }
        assertThrows(CancellationException::class.java) { copyOpenedFile(source, ByteArrayOutputStream(), 100000) { stop } }
        assertEquals(1, reads)
    }
    @Test fun doesNotSwallowProviderReadFailure() {
        val source = object : InputStream() { override fun read(): Int = throw IOException("provider failed") }
        val failure = assertThrows(IOException::class.java) { copyOpenedFile(source, ByteArrayOutputStream(), 10) { false } }
        assertEquals("provider failed", failure.message)
    }
    @Test fun doesNotSwallowDestinationFailure() {
        val output = object : OutputStream() { override fun write(value: Int) = throw IOException("disk failed") }
        assertThrows(IOException::class.java) { copyOpenedFile(ByteArrayInputStream(byteArrayOf(1)), output, 10) { false } }
    }
}
