package app.daiyu.android.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PrivateWorkspacePathsTest {
    private fun fixture(work: (File, PrivateWorkspacePaths) -> Unit) {
        val root = Files.createTempDirectory("private-workspace-").toFile()
        try { work(root, PrivateWorkspacePaths(root)) } finally { root.deleteRecursively() }
    }

    @Test fun constructionIsReadOnlyAndEnsurePreservesExistingFiles() = fixture { files, paths ->
        assertEquals(File(files, "workspace"), paths.root)
        assertFalse(paths.root.exists())
        val root = paths.ensureRoot()
        assertEquals(paths.root.canonicalFile, root)
        val saved = File(root, "saved.txt").apply { writeText("preserve") }
        assertEquals(root, paths.ensureRoot())
        assertEquals("preserve", saved.readText())
        assertFalse(File(files, "user-terminal").exists())
    }

    @Test fun conflictFileIsNotRemovedOrReplaced() = fixture { _, paths ->
        paths.root.writeText("not a directory")
        try { paths.ensureRoot(); fail("file accepted") }
        catch (failure: IllegalStateException) { assertEquals("workspace_storage_unavailable", failure.message) }
        assertEquals("not a directory", paths.root.readText())
    }

    @Test fun rootSymlinkCannotRedirectEvenWithinPrivateStorage() = fixture { files, paths ->
        val other = File(files, "other").apply { mkdir() }
        Files.createSymbolicLink(paths.root.toPath(), other.toPath())
        try {
            try { paths.ensureRoot(); fail("symlink accepted") }
            catch (failure: IllegalStateException) { assertEquals("workspace_root_changed", failure.message) }
            assertTrue(other.isDirectory)
            assertTrue(Files.isSymbolicLink(paths.root.toPath()))
        } finally { Files.delete(paths.root.toPath()) }
    }
}
