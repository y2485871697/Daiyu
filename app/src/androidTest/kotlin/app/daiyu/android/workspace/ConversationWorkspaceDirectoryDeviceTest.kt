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
        assertTrue(Files.isSameFile(root.toPath(), File(listing.path).toPath()))
        assertNull(listing.parentPath)
        assertEquals(listOf("项目 空格"), listing.folders)
        assertTrue(listing.selectable)
        assertNull(access.editor(null).binding)
        assertEquals(root.path, access.editor(null).path)
        val child = access.directory("private", File(root, "项目 空格").path)
        assertTrue(Files.isSameFile(root.toPath(), File(checkNotNull(child.parentPath)).toPath()))
        assertNull(access.directory("private", checkNotNull(child.parentPath)).parentPath)
        try { access.directory("private", storage.path); fail("parent must not be browsed") }
        catch (expected: IllegalArgumentException) { assertEquals("workspace_private_root_required", expected.message) }
        val sibling = File(storage, "workspace-other").apply { mkdir() }
        try { access.directory("private", sibling.path); fail("prefix sibling must not be browsed") }
        catch (expected: IllegalArgumentException) { assertEquals("workspace_selection_root_required", expected.message) }
    }

    fun testPrivateNamesKeepLeadingAndTrailingSpacesAndQuotes() = fixture { storage, access ->
        val root = File(storage, "workspace").apply { mkdir() }
        val name = " 项目 ' 空格 "
        val child = File(root, name).apply { check(mkdir()) }
        val expected = child.toPath().toRealPath().toString()
        assertEquals(listOf(name), access.directory("private", root.path).folders)
        assertEquals(ConversationWorkspaceBinding.Private(expected), access.binding("private", child.path))
        assertEquals(expected, access.directory("private", child.path).path)
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
        catch (expected: IllegalArgumentException) { assertEquals("workspace_selection_root_required", expected.message) }
    }

    fun testConfirmationRechecksSelectionAfterDirectoryReplacement() = fixture { storage, access ->
        val root = File(storage, "workspace").apply { mkdir() }
        val child = File(root, "child").apply { mkdir() }
        val outside = File(storage, "outside").apply { mkdir() }
        access.directory("private", child.path)
        assertTrue(child.delete())
        Files.createSymbolicLink(child.toPath(), outside.toPath())
        try { access.binding("private", child.path); fail("confirmation must reject replaced directory") }
        catch (expected: IllegalStateException) { assertEquals("workspace_symlink_not_supported", expected.message) }
        assertNull(access.editor(null).binding)
    }


    fun testFirstBrowseCreatesOnlySelectableWorkspaceRoot() = fixture { storage, access ->
        val root = File(storage, "workspace")
        assertFalse(root.exists())
        val listing = access.directory("private", root.path)
        assertTrue(Files.isSameFile(root.toPath(), File(listing.path).toPath()))
        assertTrue(listing.selectable)
        assertNull(listing.parentPath)
        assertTrue(listing.folders.isEmpty())
        assertEquals(ConversationWorkspaceBinding.Private(), access.binding("private", listing.path))
        assertNull(access.editor(null).binding)
        assertEquals(listOf("workspace"), storage.list()!!.toList())
    }

}
