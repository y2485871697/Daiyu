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

    @Test fun existingRootIsReadOnlyNestedAndNeverCreatesOrReplacesDirectories() = fixture { files, _ ->
        val selected = File(files, "linux-installations/env/generation/rootfs")
        val paths = PrivateWorkspacePaths.existingRoot(files, selected)
        assertFalse(selected.exists())
        try { paths.ensureRoot(); fail("missing Rootfs created") } catch (_: IllegalStateException) { }
        assertFalse(File(files, "linux-installations").exists())
        selected.mkdirs()
        File(selected, "keep").writeText("preserve")
        assertEquals(selected.canonicalFile, paths.ensureRoot())
        assertEquals("preserve", File(selected, "keep").readText())
        for (bad in listOf(files, files.parentFile, File(files, "linux-installations/../other"))) {
            try { PrivateWorkspacePaths.existingRoot(files, bad); fail("invalid root accepted: $bad") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun existingRootRejectsAnIntermediateSymlink() = fixture { files, _ ->
        val outside = File(files, "other").apply { mkdir() }
        val link = File(files, "linked")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        try {
            val paths = PrivateWorkspacePaths.existingRoot(files, File(link, "rootfs"))
            try { paths.ensureRoot(); fail("linked ancestor accepted") }
            catch (failure: IllegalStateException) { assertEquals("workspace_root_changed", failure.message) }
            assertFalse(File(outside, "rootfs").exists())
        } finally { Files.delete(link.toPath()) }
    }

}
