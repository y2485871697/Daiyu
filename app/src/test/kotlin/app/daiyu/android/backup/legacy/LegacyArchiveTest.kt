package app.daiyu.android.backup.legacy

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LegacyArchiveTest {
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()
    private fun rejects(block: () -> Unit) { try { block(); fail("Accepted invalid archive") } catch (_: Exception) {} }
    @Test fun jsonAndZipAreReadWithoutFilesystem() {
        val bytes = "{\"format\":\"eta-conversation\"}".toByteArray()
        assertFalse(LegacyArchive.read(ByteArrayInputStream(bytes)).zip)
        val result = LegacyArchive.read(ByteArrayInputStream(zip("eta-conversation.json" to bytes, "attachments/imports/a" to byteArrayOf(1, 2))))
        assertTrue(result.zip); assertArrayEquals(byteArrayOf(1, 2), result.entries.getValue("attachments/imports/a"))
    }
    @Test fun maliciousNamesAndTrailingBytesAreRejected() {
        listOf("../bad", "/data/private", "a/../b", "a\\b", "a//b", "a/%2e", "a:evil").forEach { name ->
            rejects { LegacyArchive.read(ByteArrayInputStream(zip(name to byteArrayOf(1)))) }
        }
        rejects { LegacyArchive.read(ByteArrayInputStream(zip("eta-conversation.json" to byteArrayOf(1)) + byteArrayOf(0))) }
    }
    @Test fun storedSameLengthCorruptionFailsCrc() {
        val original = byteArrayOf(19, 29, 39, 49)
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z ->
            val e = ZipEntry("attachments/imports/test").apply { method = ZipEntry.STORED; size = original.size.toLong(); compressedSize = size; crc = CRC32().apply { update(original) }.value }
            z.putNextEntry(e); z.write(original); z.closeEntry()
        } }.toByteArray()
        val p = 30 + "attachments/imports/test".length
        bytes[p] = (bytes[p].toInt() xor 1).toByte()
        rejects { LegacyArchive.read(ByteArrayInputStream(bytes)) }
    }
    @Test fun duplicateCentralDirectoryNamesRejected() {
        val bytes = zip("a" to byteArrayOf(1), "b" to byteArrayOf(2))
        // Equal length rename both local and central records; duplicate is independent of CRC.
        for (i in 0 until bytes.size - 46) {
            if (bytes[i] == 0x50.toByte() && bytes[i+1] == 0x4b.toByte() && bytes[i+2] == 1.toByte() && bytes[i+3] == 2.toByte() && bytes[i+46] == 'b'.code.toByte()) bytes[i+46] = 'a'.code.toByte()
        }
        rejects { LegacyArchive.read(ByteArrayInputStream(bytes)) }
    }
    @Test fun strictJsonRejectsDuplicatesDepthInvalidUtf8AndLenientSyntax() {
        listOf("{\"a\":1,\"a\":2}", "{a:1}", "{\"a\":01}", "[1,]", "{}x", "{\"x\":\"\\uD800\"}", "[".repeat(66) + "0" + "]".repeat(66)).forEach { text -> rejects { LegacyJson.parse(text) } }
        rejects { LegacyJson.objectOf(byteArrayOf(0xc0.toByte(), 0x80.toByte())) }
    }
    @Test fun declaredTextBlobAndCentralDirectoryBudgetsRejectedBeforeInflation() {
        fun central(bytes: ByteArray): Int = (0 until bytes.size - 4).first { p ->
            bytes[p] == 0x50.toByte() && bytes[p+1] == 0x4b.toByte() && bytes[p+2] == 1.toByte() && bytes[p+3] == 2.toByte()
        }
        fun put32(bytes: ByteArray, offset: Int, value: Int) { (0..3).forEach { bytes[offset + it] = (value ushr (8 * it)).toByte() } }
        val oversizedText = zip("eta-conversation.json" to byteArrayOf(1))
        put32(oversizedText, central(oversizedText) + 24, LegacyArchive.TEXT_LIMIT + 1)
        rejects { LegacyArchive.read(ByteArrayInputStream(oversizedText)) }
        val oversizedBlob = zip("attachments/imports/a" to byteArrayOf(1))
        put32(oversizedBlob, central(oversizedBlob) + 24, LegacyArchive.BLOB_LIMIT + 1)
        rejects { LegacyArchive.read(ByteArrayInputStream(oversizedBlob)) }
        val directory = zip("eta-conversation.json" to byteArrayOf(1))
        put32(directory, directory.size - 22 + 12, 1024 * 1024 + 1)
        rejects { LegacyArchive.read(ByteArrayInputStream(directory)) }
        val understatedInflation = zip("attachments/imports/a" to ByteArray(100000))
        put32(understatedInflation, central(understatedInflation) + 24, 1)
        rejects { LegacyArchive.read(ByteArrayInputStream(understatedInflation)) }
    }
    @Test fun textLimitIsNotTruncatedOrExpanded() {
        rejects { LegacyArchive.read(ByteArrayInputStream(ByteArray(LegacyArchive.TEXT_LIMIT + 1) { ' '.code.toByte() })) }
    }
}
