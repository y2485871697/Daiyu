package app.daiyu.android.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConversationWorkspaceSelectionTest {
    private fun fixture(action: (File, File) -> Unit) {
        val temporary = Files.createTempDirectory("workspace-selection-").toFile().canonicalFile
        val files = File(temporary, "files").apply { mkdir() }
        try { action(files, temporary) }
        finally { Files.walk(temporary.toPath()).use { paths ->
            paths.sorted(compareByDescending { it.nameCount }).forEach { Files.deleteIfExists(it) }
        } }
    }

    private fun rejected(message: String, action: () -> Unit) {
        try { action(); fail("Expected $message") }
        catch (failure: RuntimeException) { assertEquals(message, failure.message) }
    }

    @Test fun rootCanBeCreatedAndSelectedWithoutChangingOtherFiles() = fixture { files, _ ->
        val saved = File(files, "saved").apply { writeText("preserve") }
        val selection = ConversationWorkspaceSelection(files)
        val root = File(files, "workspace")
        assertEquals(ConversationWorkspaceBinding.Private(), selection.binding("private", root.path))
        assertTrue(root.isDirectory)
        assertNull(selection.parentPath(root))
        assertEquals("preserve", saved.readText())
        assertEquals(setOf("saved", "workspace"), files.list()!!.toSet())
    }

    @Test fun descendantsRetainExactNamesAndParentStopsAtRoot() = fixture { files, _ ->
        val root = File(files, "workspace").apply { mkdir() }
        val name = if (File.separatorChar == '\\') " 项目 ' 空格" else " 项目 ' 空格 "
        val child = File(root, name).apply { check(mkdir()) }
        val nested = File(child, "第二级").apply { mkdir() }
        val selection = ConversationWorkspaceSelection(files)
        for (directory in listOf(child, nested)) {
            assertEquals(ConversationWorkspaceBinding.Private(directory.path), selection.binding("private", directory.path))
            assertEquals(directory.parentFile.path, selection.parentPath(directory))
        }
        assertNull(selection.parentPath(root))
    }

    @Test fun nativeAndForwardSeparatorsKeepTheSameSelectionBoundary() = fixture { files, _ ->
        val root = File(files, "workspace").apply { mkdir() }
        val child = File(root, "native/child").apply { mkdirs() }
        val selection = ConversationWorkspaceSelection(files)
        for (path in listOf(child.absolutePath, child.absolutePath.replace(File.separatorChar, '/'))) {
            assertEquals(ConversationWorkspaceBinding.Private(child.path), selection.binding("private", path))
        }
        val separator = File.separator
        for (path in listOf("$root$separator${separator}native", "$child$separator",
            "$root${separator}native${separator}..${separator}native${separator}child",
            "$root${separator}.${separator}native")) {
            rejected("workspace_private_root_required") { selection.binding("private", path) }
        }
    }

    @Test fun samePrefixSiblingAndOtherPrivateDirectoriesAreRejected() = fixture { files, _ ->
        File(files, "workspace").mkdir()
        val selection = ConversationWorkspaceSelection(files)
        for (name in listOf("workspace-other", "other", "workspace-other/child")) {
            val sibling = File(files, name).apply { mkdirs() }
            rejected("workspace_selection_root_required") { selection.binding("private", sibling.path) }
        }
        rejected("workspace_private_root_required") { selection.binding("private", files.path) }
    }

    @Test fun rootfsAndOutsideStorageAreRejectedWithoutCreatingWorkspace() = fixture { files, temporary ->
        val outside = File(temporary, "outside").apply { mkdir() }
        val selection = ConversationWorkspaceSelection(files)
        rejected("workspace_selection_root_required") { selection.binding("environment:generation", "/") }
        rejected("workspace_private_root_required") { selection.binding("private", outside.path) }
        assertFalse(File(files, "workspace").exists())
    }

    @Test fun unnormalizedPathsAreNotCleanedBeforeValidation() = fixture { files, _ ->
        val root = File(files, "workspace").apply { mkdir() }
        File(root, "child").mkdir()
        val selection = ConversationWorkspaceSelection(files)
        for (path in listOf("workspace", "$root/../other", "$root/child/..", "$root/./child", "$root//child", "$root/", "$root/\u0000")) {
            rejected("workspace_private_root_required") { selection.binding("private", path) }
        }
    }

    @Test fun missingAndRegularFileAreRejectedWithoutCreationOrReplacement() = fixture { files, _ ->
        val root = File(files, "workspace").apply { mkdir() }
        val document = File(root, "document").apply { writeText("preserve") }
        val missing = File(root, "missing")
        val selection = ConversationWorkspaceSelection(files)
        for (path in listOf(document.path, missing.path)) {
            rejected("workspace_path_unavailable") { selection.binding("private", path) }
        }
        assertFalse(missing.exists())
        assertEquals("preserve", document.readText())
    }

    @Test fun leafIntermediateAndDanglingLinksAreNotFollowed() = fixture { files, _ ->
        val root = File(files, "workspace").apply { mkdir() }
        val actual = File(root, "actual").apply { mkdir() }
        File(actual, "child").mkdir()
        val link = File(root, "link")
        val dangling = File(root, "dangling")
        Files.createSymbolicLink(link.toPath(), actual.toPath())
        Files.createSymbolicLink(dangling.toPath(), File(root, "missing").toPath())
        val selection = ConversationWorkspaceSelection(files)
        for (path in listOf(link.path, "$link/child", dangling.path)) {
            rejected("workspace_symlink_not_supported") { selection.binding("private", path) }
        }
    }

    @Test fun workspaceRootCannotBeASymlinkEvenIntoPrivateStorage() = fixture { files, _ ->
        val actual = File(files, "actual").apply { mkdir() }
        val root = File(files, "workspace")
        Files.createSymbolicLink(root.toPath(), actual.toPath())
        rejected("workspace_symlink_not_supported") {
            ConversationWorkspaceSelection(files).binding("private", root.path)
        }
    }

    @Test fun trustedStorageAliasSelectsSameCanonicalDirectory() = fixture { files, temporary ->
        val child = File(files, "workspace/child").apply { mkdirs() }
        val alias = File(temporary, "storage-alias")
        Files.createSymbolicLink(alias.toPath(), files.toPath())
        val selection = ConversationWorkspaceSelection(alias)
        for (base in listOf(alias, files)) {
            assertEquals(ConversationWorkspaceBinding.Private(), selection.binding("private", File(base, "workspace").path))
            assertEquals(ConversationWorkspaceBinding.Private(child.path), selection.binding("private", File(base, "workspace/child").path))
        }
        val root = File(files, "workspace")
        assertNull(selection.parentPath(root))
        assertEquals(root.path, selection.parentPath(child))
        val linked = File(root, "linked")
        Files.createSymbolicLink(linked.toPath(), child.toPath())
        rejected("workspace_symlink_not_supported") {
            selection.binding("private", File(alias, "workspace/linked").path)
        }
    }

    @Test fun aliasPrefixAndWorkspacePrefixSiblingsAreRejected() = fixture { files, temporary ->
        File(files, "workspace").mkdir()
        File(files, "workspace-other").mkdir()
        val alias = File(temporary, "storage-alias")
        Files.createSymbolicLink(alias.toPath(), files.toPath())
        val otherAlias = File(temporary, "storage-alias-other/workspace").apply { mkdirs() }
        val selection = ConversationWorkspaceSelection(alias)
        rejected("workspace_selection_root_required") { selection.binding("private", "$alias/workspace-other") }
        rejected("workspace_private_root_required") { selection.binding("private", otherAlias.path) }
    }

    @Test fun confirmationRechecksDirectoryReplacedAfterBrowsing() = fixture { files, _ ->
        val child = File(files, "workspace/child").apply { mkdirs() }
        val other = File(files, "other").apply { mkdir() }
        val selection = ConversationWorkspaceSelection(files)
        assertEquals(child.path, selection.binding("private", child.path).path)
        assertTrue(child.delete())
        Files.createSymbolicLink(child.toPath(), other.toPath())
        rejected("workspace_symlink_not_supported") { selection.binding("private", child.path) }
    }
}
