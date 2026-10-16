package app.daiyu.android.media.video

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Synthetic headers exercise only recognition, not validity or media decoding. */
class VideoContainerTest {
    private fun sniff(bytes: ByteArray): String? {
        val file = Files.createTempFile("video-header", ".bin").toFile()
        return try { file.writeBytes(bytes); sniffVideoContainer(file) } finally { file.delete() }
    }
    private fun ftyp(brand: String) = byteArrayOf(0, 0, 0, 16) + "ftyp".toByteArray() + brand.toByteArray() + ByteArray(4)
    private fun ebml(docType: String): ByteArray {
        val payload = byteArrayOf(0x42, 0x82.toByte(), (0x80 + docType.length).toByte()) + docType.toByteArray()
        return byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), (0x80 + payload.size).toByte()) + payload
    }

    @Test fun recognizesContainerRatherThanExtensionOrMimeHint() {
        assertEquals("video/mp4", sniff(ftyp("isom")))
        assertEquals("video/quicktime", sniff(ftyp("qt  ")))
        assertEquals("video/3gpp", sniff(ftyp("3gp4")))
        assertEquals("video/webm", sniff(ebml("webm")))
        assertEquals("video/x-matroska", sniff(ebml("matroska")))
    }

    @Test fun audioImageTextUnknownAndTruncatedHeadersAreNotVideoEvidence() {
        assertNull(sniff(ftyp("M4A ")))
        assertNull(sniff(ftyp("avif")))
        assertNull(sniff(ftyp("zzzz")))
        assertNull(sniff("video/mp4".toByteArray()))
        assertNull(sniff(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertNull(sniff(ftyp("isom").copyOf(12)))
        assertNull(sniff(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())))
        assertNull(sniff(ebml("webm").dropLast(1).toByteArray()))
        assertNull(sniff(ebml("unknown")))
        assertNull(sniff(byteArrayOf()))
    }

    @Test fun malformedSizesCannotOverflowOrReadOutsideTheHeader() {
        val huge = ftyp("isom").also { it[0] = 0x7f }
        assertNull(sniff(huge))
        val badVint = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0)
        assertNull(sniff(badVint))
        assertNull(sniff(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0xff.toByte())))
    }
}
