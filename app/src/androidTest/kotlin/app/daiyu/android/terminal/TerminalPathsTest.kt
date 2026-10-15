package app.daiyu.android.terminal

import android.test.InstrumentationTestCase
import java.io.File
import java.nio.file.Files

@Suppress("DEPRECATION")
class TerminalPathsTest : InstrumentationTestCase() {
    private lateinit var temporaryRoot: File

    override fun setUp() {
        super.setUp()
        temporaryRoot = File(instrumentation.targetContext.cacheDir, "terminal-paths-${System.nanoTime()}")
        assertTrue(temporaryRoot.mkdirs())
    }

    override fun tearDown() {
        deleteRecursively(temporaryRoot)
        super.tearDown()
    }

    fun testNormalizesHostRootAndResolvesAbsoluteGuestPath() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val attachment = File(root, "notes.txt").apply { writeText("frozen") }
        val paths = TerminalPaths(root, "/guest/shared")

        assertEquals(root.canonicalFile, paths.hostRoot)
        assertEquals("/guest/shared", paths.guestRoot)
        val resolved = paths.resolveAttachment("/guest/shared/./notes.txt")
        assertEquals(attachment.canonicalFile, resolved.file.canonicalFile)
        assertTrue(resolved.revalidate().authorized)
    }

    fun testRejectsInvalidRootsAndGuestTraversal() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val regularFile = File(temporaryRoot, "not-a-directory").apply { writeText("x") }
        assertThrows<IllegalArgumentException> { TerminalPaths(regularFile, "/guest") }
        assertThrows<IllegalArgumentException> { TerminalPaths(root, "guest/shared") }
        assertThrows<IllegalArgumentException> { TerminalPaths(root, "/guest/../shared") }
        assertThrows<IllegalArgumentException> { TerminalPaths(root, "/guest/shared\u0000") }

        val paths = TerminalPaths(root, "/guest/shared")
        assertThrows<IllegalArgumentException> { paths.resolveAttachment("guest/shared/file") }
        assertThrows<IllegalArgumentException> { paths.resolveAttachment("/guest/shared/../outside") }
        assertThrows<IllegalArgumentException> { paths.resolveAttachment("/guest/shared/file\u0000.txt") }
    }

    fun testReportsPathChangedNotAuthorizedAfterRootReplacement() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        File(root, "notes.txt").writeText("before")
        val paths = TerminalPaths(root, "/guest/shared")
        val resolved = paths.resolveAttachment("/guest/shared/notes.txt")

        val oldRoot = File(temporaryRoot, "old-shared")
        assertTrue(root.renameTo(oldRoot))
        assertTrue(root.mkdirs())

        val result = resolved.revalidate()
        assertFalse(result.authorized)
        assertEquals(TerminalPaths.PATH_CHANGED_NOT_AUTHORIZED, result.reason)
    }

    fun testReportsPathChangedNotAuthorizedAfterAttachmentReplacement() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val file = File(root, "notes.txt").apply { writeText("before") }
        val paths = TerminalPaths(root, "/guest/shared")
        val resolved = paths.resolveAttachment("/guest/shared/notes.txt")

        // Keep the original inode alive so replacement cannot reuse its identity.
        assertTrue(file.renameTo(File(root, "old-notes.txt")))
        file.writeText("replacement")
        val result = paths.validate(resolved)
        assertFalse(result.authorized)
        assertEquals(TerminalPaths.PATH_CHANGED_NOT_AUTHORIZED, result.reason)
        assertThrows<SecurityException> { resolved.openRead().close() }
    }

    fun testRejectsSymlinkAttachmentInsteadOfFollowingIt() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val outside = File(temporaryRoot, "outside.txt").apply { writeText("outside") }
        val link = File(root, "link.txt")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: SecurityException) {
            return
        }
        val paths = TerminalPaths(root, "/guest/shared")
        assertThrows<SecurityException> { paths.resolveAttachment("/guest/shared/link.txt") }
        val original = File(root, "notes.txt").apply { writeText("authorized") }
        val attachment = paths.resolveAttachment("/guest/shared/notes.txt")
        val moved = File(root, "old.txt")
        assertTrue(original.renameTo(moved))
        Files.createSymbolicLink(original.toPath(), moved.toPath())
        assertThrows<SecurityException> { attachment.openRead().close() }
    }

    fun testOpenedLeafNeverReadsReplacementPath() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val file = File(root, "notes.txt").apply { writeText("authorized") }
        val paths = TerminalPaths(root, "/guest")
        val attachment = paths.resolveAttachment("/guest/notes.txt")
        paths.afterDescriptorOpened = { index ->
            if (index == 0) {
                assertTrue(file.renameTo(File(root, "old.txt")))
                file.writeText("replacement")
            }
        }
        attachment.openRead().use { input ->
            val descriptor = (input as java.io.FileInputStream).fd
            val flags = android.system.Os::class.java.getMethod("fcntlInt", java.io.FileDescriptor::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(null, descriptor, android.system.OsConstants.F_GETFD, 0) as Int
            assertTrue(flags and android.system.OsConstants.FD_CLOEXEC != 0)
            assertEquals("authorized", input.bufferedReader().readText())
        }
    }

    fun testDirectoryDescriptorDoesNotFollowReplacementSymlink() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val child = File(root, "child").apply { mkdirs() }
        File(child, "notes.txt").writeText("authorized")
        val outside = File(temporaryRoot, "outside").apply { mkdirs() }
        File(outside, "notes.txt").writeText("outside")
        val paths = TerminalPaths(root, "/guest")
        val attachment = paths.resolveAttachment("/guest/child/notes.txt")
        paths.afterDescriptorOpened = { index ->
            if (index == 0) {
                assertTrue(child.renameTo(File(root, "old-child")))
                Files.createSymbolicLink(child.toPath(), outside.toPath())
            }
        }
        try {
            attachment.openRead().use { assertEquals("authorized", it.bufferedReader().readText()) }
        } finally { Files.deleteIfExists(child.toPath()) }
    }

    fun testHeldDescriptorSurvivesGcAndPathReplacement() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val file = File(root, "notes.txt").apply { writeText("authorized") }
        val attachment = TerminalPaths(root, "/guest").resolveAttachment("/guest/notes.txt")
        attachment.openRead().use { input ->
            assertTrue(file.renameTo(File(root, "old.txt")))
            file.writeText("replacement")
            System.gc()
            repeat(50) { file.inputStream().use { assertTrue(it.read() >= 0) } }
            assertEquals("authorized", input.bufferedReader().readText())
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }

    private fun deleteRecursively(file: File) {
        if (Files.isSymbolicLink(file.toPath())) { Files.deleteIfExists(file.toPath()); return }
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        file.delete()
    }
}
