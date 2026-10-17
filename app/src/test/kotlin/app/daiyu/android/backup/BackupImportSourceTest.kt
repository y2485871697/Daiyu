package app.daiyu.android.backup

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException

class BackupImportSourceTest {
    @get:Rule val temp = TemporaryFolder()

    private fun prepare(bytes: ByteArray) = BackupImportSource.prepare(temp.root) { bytes.inputStream() }
    private fun rejected(bytes: ByteArray) {
        assertThrows(Exception::class.java) { prepare(bytes) }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun formatsAreDetectedWithoutChangingSourceBytes() {
        for ((format, kind) in listOf(
            "daiyu-neutral-complete" to BackupImportSource.Kind.COMPLETE,
            "daiyu-neutral-text" to BackupImportSource.Kind.TEXT,
            "eta-backup" to BackupImportSource.Kind.LEGACY,
            "eta-conversation" to BackupImportSource.Kind.LEGACY,
        )) {
            val bytes = """{"ignored":{"nested":[true,null,2,"字😀"]},"format":"$format"}""".toByteArray()
            val source = prepare(bytes)
            try {
                assertEquals(kind, source.kind)
                repeat(2) { assertArrayEquals(bytes, source.open().use { it.readBytes() }) }
            } finally { assertTrue(source.delete()) }
            assertTrue(temp.root.listFiles()!!.isEmpty())
        }
    }

    @Test fun providerIsReadOnceAndClosedBeforePublication() {
        var opens = 0
        var closed = false
        val bytes = """{"format":"daiyu-neutral-text"}""".toByteArray()
        val source = BackupImportSource.prepare(temp.root) {
            opens++
            object : ByteArrayInputStream(bytes) {
                override fun close() { closed = true; super.close() }
            }
        }
        try {
            assertEquals(1, opens)
            assertTrue(closed)
            assertArrayEquals(bytes, source.open().use { it.readBytes() })
        } finally { assertTrue(source.delete()) }
    }

    @Test fun zipIsOnlyRoutedToLegacyImporterNotApplied() {
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0, 0)
        val source = prepare(bytes)
        try {
            assertEquals(BackupImportSource.Kind.LEGACY, source.kind)
            assertArrayEquals(bytes, source.open().use { it.readBytes() })
        } finally { assertTrue(source.delete()) }
    }

    @Test fun unknownMissingDuplicateOrWrongTypedFormatIsRejectedAndCleaned() {
        listOf("{}", """{"format":"unknown"}""", """{"format":42}""",
            """{"format":"daiyu-neutral-text","format":"daiyu-neutral-complete"}""")
            .forEach { rejected(it.toByteArray()) }
    }

    @Test fun malformedUtf8AndTrailingContentAreRejectedAndCleaned() {
        rejected(byteArrayOf(0xc3.toByte(), 0x28))
        rejected("""{"format":"daiyu-neutral-text"} true""".toByteArray())
        rejected("""{"format":"daiyu-neutral-text","x":[1,]}""".toByteArray())
    }

    @Test fun providerReadFailureCleansTemporaryFileAndClosesProvider() {
        var closed = false
        assertThrows(IOException::class.java) {
            BackupImportSource.prepare(temp.root) {
                object : java.io.InputStream() {
                    override fun read(): Int = throw IOException("synthetic read failure")
                    override fun close() { closed = true }
                }
            }
        }
        assertTrue(closed)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun excessiveDepthOrProbeMetadataIsRejected() {
        rejected(("{\"format\":\"daiyu-neutral-text\",\"x\":" + "[".repeat(34) + "0" + "]".repeat(34) + "}").toByteArray())
        rejected(("{\"format\":\"" + "x".repeat(65) + "\"}").toByteArray())
        rejected(("{\"" + "k".repeat(513) + "\":0,\"format\":\"daiyu-neutral-text\"}").toByteArray())
    }

    @Test fun largeIgnoredStringIsSkippedWithoutChangingStagedData() {
        val bytes = ("{\"ignored\":\"" + "字😀\\n".repeat(200_000) + "\",\"format\":\"daiyu-neutral-complete\"}").toByteArray()
        val source = prepare(bytes)
        try { assertEquals(bytes.size.toLong(), source.open().use { it.readBytes().size.toLong() }) }
        finally { assertTrue(source.delete()) }
    }
}
