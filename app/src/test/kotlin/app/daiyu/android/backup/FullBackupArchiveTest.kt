package app.daiyu.android.backup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FullBackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun metadata() = JSONObject().put("distribution", "debian").put("mode", "proot")
        .put("abi", "arm64-v8a").put("host_uid", 10123).put("host_gid", 10123)
    private fun archive(): ByteArray {
        val data = temp.newFile().apply { writeText("""{"format":"daiyu-neutral-complete"}""") }
        val linux = temp.newFile().apply { writeBytes(byteArrayOf(31, -117, 8, 0, 1, 2, 3)) }
        return FullBackupArchive.prepare(temp.root, data, linux, metadata()).use { prepared ->
            ByteArrayOutputStream().also { prepared.writeTo(it) }.toByteArray()
        }
    }
    private fun file(bytes: ByteArray) = temp.newFile().apply { writeBytes(bytes) }
    private fun rewrite(bytes: ByteArray, change: (String, ByteArray) -> Pair<String, ByteArray>): ByteArray {
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { out -> ZipInputStream(bytes.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val (name, data) = change(entry.name, input.readBytes())
                out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry()
            }
        } }
        return result.toByteArray()
    }
    @Test fun roundTripVerifiesPayloadsAndKeepsSourceBytes() {
        val bytes = archive(); val source = file(bytes)
        assertTrue(FullBackupArchive.recognizes(source))
        FullBackupArchive.read(source, temp.root).use { prepared ->
            assertEquals("daiyu-neutral-complete", JSONObject(prepared.data.readText()).getString("format"))
            assertArrayEquals(byteArrayOf(31, -117, 8, 0, 1, 2, 3), prepared.linux.readBytes())
            assertEquals("debian", prepared.manifest.getJSONObject("environment").getString("distribution"))
            assertTrue(prepared.id.matches(Regex("[a-f0-9]{64}")))
        }
        assertArrayEquals(bytes, source.readBytes())
    }
    @Test fun checksumFailureCleansOnlyItsOwnStaging() {
        val bytes = rewrite(archive()) { name, value -> name to if (name == "data.json") byteArrayOf(0) else value }
        val source = file(bytes); val before = temp.root.list()!!.toSet()
        assertThrows(IllegalArgumentException::class.java) { FullBackupArchive.read(source, temp.root) }
        assertEquals(before, temp.root.list()!!.toSet())
    }
    @Test fun traversalAndUnknownEntriesNeverBecomePaths() {
        for (bad in listOf("../escape", "/absolute", "linux/../data.json")) {
            val source = file(rewrite(archive()) { name, value -> (if (name == "linux.rootfs.gz") bad else name) to value })
            val before = temp.root.list()!!.toSet()
            assertThrows(IllegalArgumentException::class.java) { FullBackupArchive.read(source, temp.root) }
            assertEquals(before, temp.root.list()!!.toSet())
        }
    }
    @Test fun invalidEnvironmentOrManifestIsRejectedBeforePublication() {
        for (field in listOf("mode", "abi", "host_uid", "host_gid", "distribution")) {
            val source = file(rewrite(archive()) { name, value -> name to if (name != "manifest.json") value else
                JSONObject(value.toString(Charsets.UTF_8)).apply { getJSONObject("environment").put(field, "invalid!") }.toString().toByteArray() })
            assertThrows(Exception::class.java) { FullBackupArchive.read(source, temp.root) }
        }
    }
    @Test fun completeArchiveIsDetectedAndUnrecognizedArchivesAreRejected() {
        val bytes = archive()
        val source = BackupImportSource.prepare(temp.root) { bytes.inputStream() }
        try {
            assertEquals(BackupImportSource.Kind.ARCHIVE, source.kind)
            assertArrayEquals(bytes, source.open().use { it.readBytes() })
        } finally { assertTrue(source.delete()) }
        val legacy = ByteArrayOutputStream().also { stream -> ZipOutputStream(stream).use {
            it.putNextEntry(ZipEntry("old.json")); it.write("{}".toByteArray()); it.closeEntry()
        } }.toByteArray()
        assertThrows(Exception::class.java) { BackupImportSource.prepare(temp.root) { legacy.inputStream() } }
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith("backup-import-") })
    }
    @Test fun brokenZipProbeDoesNotMisclassifyPlainBackup() {
        assertFalse(FullBackupArchive.recognizes(file("{}".toByteArray())))
    }
}
