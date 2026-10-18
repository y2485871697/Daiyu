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
}
