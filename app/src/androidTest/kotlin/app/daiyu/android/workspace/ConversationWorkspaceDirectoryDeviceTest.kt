package app.daiyu.android.workspace

import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import java.io.File
import java.nio.file.Files

class ConversationWorkspaceDirectoryDeviceTest : InstrumentationTestCase() {
    private fun fixture(action: (File, ConversationWorkspaceAccess) -> Unit) {
        val context = instrumentation.targetContext
        val storage = File(context.filesDir, "picker-test-${java.util.UUID.randomUUID()}").apply { mkdir() }
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir(): File = storage }
        try { action(storage, ConversationWorkspaceAccess(wrapped) { null }) }
        finally { Files.walk(storage.toPath()).use { paths ->
            paths.sorted(compareByDescending { it.nameCount }).forEach { Files.deleteIfExists(it) }
        } }
    }

    fun testPrivateBrowseListsFoldersAndParentWithoutChangingBinding() = fixture { storage, access ->
        val root = File(storage, "workspace").apply { mkdir() }
        File(root, "项目 空格").mkdir()
        File(root, "file.txt").writeText("not a folder")
        val listing = access.directory("private", root.path)
        assertEquals(root.canonicalPath, listing.path)
        assertEquals(storage.canonicalPath, listing.parentPath)
        assertEquals(listOf("项目 空格"), listing.folders)
        assertTrue(listing.selectable)
        assertNull(access.editor(null).binding)
        assertEquals(root.path, access.editor(null).path)
        assertFalse(access.directory("private", storage.path).selectable)
        assertNull(access.directory("private", storage.path).parentPath)
    }

    fun testFolderListingDoesNotSilentlyStopAfterOneHundredFiles() = fixture { storage, access ->
        val root = File(storage, "workspace").apply { mkdir() }
        repeat(160) { File(root, "file-$it").writeText("") }
        repeat(130) { File(root, "目录-$it").mkdir() }
        val names = access.directory("private", root.path).folders
        assertEquals(130, names.size)
        assertTrue(names.contains("目录-129"))
    }

    fun testDirectoryLinksAndOutsidePrivateStorageCannotBeSelected() = fixture { storage, access ->
        val root = File(storage, "workspace").apply { mkdir() }
        val actual = File(root, "real").apply { mkdir() }
        val link = File(root, "linked")
        Files.createSymbolicLink(link.toPath(), actual.toPath())
        assertEquals(listOf("real"), access.directory("private", root.path).folders)
        try { access.directory("private", link.path); fail("symlink must not be followed") }
        catch (expected: IllegalStateException) { assertEquals("workspace_symlink_not_supported", expected.message) }
        try { access.directory("private", storage.parentFile.path); fail("outside root must be rejected") }
        catch (expected: IllegalArgumentException) { assertEquals("workspace_private_root_required", expected.message) }
        try { access.directory("missing:generation", "/"); fail("unapproved rootfs must not fall back") }
        catch (expected: IllegalStateException) { assertEquals("workspace_rootfs_changed", expected.message) }
    }
}
