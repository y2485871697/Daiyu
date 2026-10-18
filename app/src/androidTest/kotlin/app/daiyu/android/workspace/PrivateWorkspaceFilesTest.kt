package app.daiyu.android.workspace

import android.test.InstrumentationTestCase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class PrivateWorkspaceFilesTest : InstrumentationTestCase() {
    private fun fixture(test: (File, PrivateWorkspaceFiles) -> Unit) {
        val files = File(instrumentation.targetContext.cacheDir, "private-files-${UUID.randomUUID()}").apply { mkdir() }
        try { test(files, PrivateWorkspaceFiles(PrivateWorkspacePaths(files))) }
        finally { files.deleteRecursively() }
    }
    fun testImportExportAndDeleteUsePrivateRootAndKeepSource() = fixture { files, access ->
        val root = access.list().directory
        val bytes = "真实私有文件".toByteArray()
        val name = access.importFile(root, "original.txt", { ByteArrayInputStream(bytes) })
        val entry = access.list().entries.single()
        assertEquals(name, entry.name)
        assertTrue(File(files, "workspace/$name").isFile)
        val output = ByteArrayOutputStream()
        access.exportFile(entry, { output })
        assertTrue(bytes.contentEquals(output.toByteArray()))
        access.delete(entry)
        assertTrue(access.list().entries.isEmpty())
    }
    fun testImportKeepsPartialBytesHiddenUntilCompleted() = fixture { files, access ->
        val root = access.list().directory
        val workspace = File(files, "workspace")
        var observed = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (pos > 0) {
                    observed = true
                    val staging = workspace.listFiles()!!.single()
                    assertTrue(staging.name.startsWith(".workspace-import-"))
                    assertEquals(3L, staging.length())
                    assertTrue(access.list().entries.isEmpty())
                }
                return super.read(buffer, offset, length)
            }
        }
        val name = access.importFile(root, "complete.bin", { input })
        assertTrue(observed)
        assertEquals(setOf(name), workspace.list()!!.toSet())
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(File(workspace, name).readBytes()))
    }
    fun testImportCollisionPreservesExistingTargetAndCleansStaging() = fixture { files, access ->
        val root = access.list().directory
        val workspace = File(files, "workspace")
        var existing: File? = null
        try {
            access.importFile(root, "collision.bin", {
                val token = workspace.list()!!.single().removePrefix(".workspace-import-")
                existing = File(workspace, "$token-collision.bin").apply { writeText("keep") }
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            })
            fail("existing target replaced")
        } catch (error: android.system.ErrnoException) { assertEquals(android.system.OsConstants.EEXIST, error.errno) }
        assertEquals("keep", checkNotNull(existing).readText())
        assertEquals(setOf(checkNotNull(existing).name), workspace.list()!!.toSet())
    }
    fun testInputCloseCancellationDoesNotPublishOrLeaveStaging() = fixture { files, access ->
        val root = access.list().directory
        val cancelled = AtomicBoolean()
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { cancelled.set(true); super.close() }
        }
        try { access.importFile(root, "cancel-on-close.bin", { input }, cancelled::get); fail("close cancellation ignored") }
        catch (_: InterruptedException) { }
        assertTrue(File(files, "workspace").list()!!.isEmpty())
    }
    fun testLongUnicodeSourceNameRespectsFilesystemByteLimit() = fixture { _, access ->
        val name = access.importFile(access.list().directory, "汉".repeat(73) + ".txt", { ByteArrayInputStream(byteArrayOf(1)) })
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals(name, access.list().entries.single().name)
    }
    fun testSymlinksCannotBeListedOrRedirectStaleDirectories() = fixture { files, access ->
        access.list()
        val workspace = File(files, "workspace")
        val outside = File(files, "outside").apply { mkdir() }
        File(outside, "keep.txt").writeText("keep")
        val child = File(workspace, "child").apply { mkdir() }
        val selected = access.list().entries.single()
        assertTrue(child.delete())
        java.nio.file.Files.createSymbolicLink(child.toPath(), outside.toPath())
        try {
            assertTrue(access.list().entries.isEmpty())
            try { access.importFile(selected, "escape.txt", { ByteArrayInputStream(byteArrayOf(1)) }); fail("stale directory accepted") }
            catch (_: Exception) { }
            assertEquals(listOf("keep.txt"), outside.list()!!.toList())
        } finally { java.nio.file.Files.delete(child.toPath()) }
    }
    fun testCancelledImportRemovesStagingAndDoesNotPublish() = fixture { files, access ->
        val cancel = AtomicBoolean()
        val root = access.list().directory
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length); cancel.set(true); return count
            }
        }
        try { access.importFile(root, "cancel.txt", { input }, cancel::get); fail("cancel ignored") }
        catch (_: InterruptedException) { }
        assertTrue(access.list().entries.isEmpty())
        assertTrue(File(files, "workspace").list()!!.isEmpty())
    }
    fun testStaleFileExportDoesNotOpenDestinationAndRootCannotBeDeleted() = fixture { files, access ->
        val root = access.list().directory
        val name = access.importFile(root, "first.txt", { ByteArrayInputStream(byteArrayOf(1)) })
        val entry = access.list().entries.single()
        val source = File(files, "workspace/$name")
        source.writeText("different size")
        var opened = false
        try { access.exportFile(entry, { opened = true; ByteArrayOutputStream() }); fail("stale file exported") }
        catch (_: IllegalStateException) { }
        assertFalse(opened)
        try { access.delete(root); fail("root deleted") } catch (_: IllegalStateException) { }
        assertTrue(source.exists())
    }
    fun testParentNavigationAndNonEmptyDirectoryProtection() = fixture { files, access ->
        access.list()
        val child = File(files, "workspace/sub").apply { mkdir() }
        File(child, "keep.txt").writeText("keep")
        val selected = access.list().entries.single()
        assertEquals("sub", access.list(selected).directory.relative)
        assertEquals("", access.parent(selected).directory.relative)
        try { access.delete(selected); fail("nonempty directory deleted") } catch (_: Exception) { }
        assertEquals("keep", File(child, "keep.txt").readText())
    }
    fun testListingCapsCandidatesIncludingHiddenStaging() = fixture { files, access ->
        access.list()
        val workspace = File(files, "workspace")
        val limit = 100
        assertEquals(limit, PrivateWorkspaceFiles.MAX_ENTRIES)
        val expected = (0 until limit).map { "entry-$it.txt" }.toSet()
        expected.forEach { assertTrue(File(workspace, it).createNewFile()) }
        val exact = access.list()
        assertEquals(limit, exact.entries.size)
        assertEquals(expected, exact.entries.map { it.name }.toSet())
        assertFalse(exact.truncated)
        assertTrue(File(workspace, "one-more.txt").createNewFile())
        val over = access.list()
        assertEquals(limit, over.entries.size); assertTrue(over.truncated)
        workspace.listFiles()!!.forEach { assertTrue(it.delete()) }
        repeat(limit + 1) { assertTrue(File(workspace, ".workspace-import-test-$it").createNewFile()) }
        val hidden = access.list()
        assertTrue(hidden.entries.isEmpty()); assertTrue(hidden.truncated)
    }
    fun testActualImportByteLimitRemovesStagingAndDoesNotPublish() = fixture { files, access ->
        val root = access.list().directory
        val cap = 256L * 1024 * 1024
        assertEquals(cap, PrivateWorkspaceFiles.MAX_FILE_BYTES)
        var supplied = 0L; var closed = false
        val input = object : java.io.InputStream() {
            override fun read(): Int {
                if (supplied == cap + 1) return -1
                supplied++; return 0
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val remaining = cap + 1 - supplied
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
                supplied += count; return count
            }
            override fun close() { closed = true }
        }
        try { access.importFile(root, "too-large.bin", { input }); fail("actual byte limit ignored") }
        catch (error: IllegalStateException) { assertEquals("workspace_file_too_large", error.message) }
        assertEquals(cap + 1, supplied); assertTrue(closed)
        assertTrue(access.list().entries.isEmpty())
        assertTrue(File(files, "workspace").list()!!.isEmpty())
    }
    fun testStreamFailuresCleanImportStagingAndPreserveExportSource() = fixture { files, access ->
        val root = access.list().directory
        val workspace = File(files, "workspace")
        val inputFailure = java.io.IOException("injected input failure")
        var firstByteRead = false; var inputClosed = false
        val input = object : java.io.InputStream() {
            override fun read(): Int {
                if (firstByteRead) throw inputFailure
                firstByteRead = true; return 7
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                buffer[offset] = read().toByte(); return 1
            }
            override fun close() { inputClosed = true }
        }
        try { access.importFile(root, "broken.bin", { input }); fail("input failure ignored") }
        catch (error: java.io.IOException) { assertSame(inputFailure, error) }
        assertTrue(firstByteRead); assertTrue(inputClosed); assertTrue(workspace.list()!!.isEmpty())
        val bytes = byteArrayOf(1, 2, 3)
        val name = access.importFile(root, "keep.bin", { ByteArrayInputStream(bytes) })
        val entry = access.list().entries.single()
        val outputFailure = java.io.IOException("injected output failure")
        var outputAttempted = false; var outputClosed = false
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { outputAttempted = true; throw outputFailure }
            override fun close() { outputClosed = true }
        }
        try { access.exportFile(entry, { output }); fail("output failure ignored") }
        catch (error: java.io.IOException) { assertSame(outputFailure, error) }
        assertTrue(outputAttempted); assertTrue(outputClosed)
        assertTrue(bytes.contentEquals(File(workspace, name).readBytes()))
        assertEquals(setOf(name), workspace.list()!!.toSet())
    }
}
