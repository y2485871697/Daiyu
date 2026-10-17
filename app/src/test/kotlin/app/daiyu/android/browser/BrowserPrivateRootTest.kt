package app.daiyu.android.browser

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BrowserPrivateRootTest {
    private fun delete(root: Path) = Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }; Unit
    }
    @Test fun freshInstallCreatesOnlyFixedRootAndRechecksExistingRoot() {
        val files = Files.createTempDirectory("browser-fresh-files-")
        try {
            assertFalse(Files.exists(files.resolve("browser-runs")))
            val root = BrowserPrivateRoot.prepare(files)
            assertEquals(files.toRealPath().resolve("browser-runs"), root)
            assertTrue(Files.isDirectory(root))
            assertEquals(root, BrowserPrivateRoot.prepare(files))
            assertEquals(listOf("browser-runs"), Files.list(files).use { stream ->
                stream.toArray().map { (it as Path).fileName.toString() }
            })
            Files.delete(root)
            Files.write(root, byteArrayOf(1))
            try { BrowserPrivateRoot.prepare(files); fail("file must not become root") }
            catch (_: IllegalArgumentException) {}
        } finally { delete(files) }
    }
    @Test fun symlinkLeafIsRejectedWithoutWritingThroughIt() {
        val files = Files.createTempDirectory("browser-root-links-")
        val outside = Files.createTempDirectory("browser-outside-")
        try {
            try { Files.createSymbolicLink(files.resolve("browser-runs"), outside) }
            catch (unsupported: UnsupportedOperationException) { assumeNoException(unsupported) }
            catch (unsupported: java.nio.file.FileSystemException) { assumeNoException(unsupported) }
            try { BrowserPrivateRoot.prepare(files); fail("symlink must not become root") }
            catch (_: IllegalArgumentException) {}
            assertEquals(0L, Files.list(outside).use { it.count() })
        } finally { delete(files); delete(outside) }
    }
}
