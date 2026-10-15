package app.daiyu.android.terminal

import android.test.InstrumentationTestCase
import java.io.File
import java.nio.file.Files

@Suppress("DEPRECATION")
class TerminalPathsTest : InstrumentationTestCase() {
    private lateinit var temporaryRoot: File
    private val owners = mutableListOf<TerminalPaths>()
    private fun paths(root: File, guest: String) = TerminalPaths(root, guest).also { owners += it }

    override fun setUp() {
        super.setUp()
        temporaryRoot = File(instrumentation.targetContext.cacheDir, "terminal-paths-${System.nanoTime()}")
        assertTrue(temporaryRoot.mkdirs())
    }

    override fun tearDown() {
        try {
            owners.asReversed().forEach { it.close() }
        } finally {
            deleteRecursively(temporaryRoot)
            super.tearDown()
        }
    }

    fun testNormalizesHostRootAndResolvesAbsoluteGuestPath() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val attachment = File(root, "notes.txt").apply { writeText("frozen") }
        val paths = paths(root, "/guest/shared")

        assertEquals(root.canonicalFile, paths.hostRoot)
        assertEquals("/guest/shared", paths.guestRoot)
        val resolved = paths.resolveAttachment("/guest/shared/./notes.txt")
        assertEquals(attachment.canonicalFile, resolved.file.canonicalFile)
        assertTrue(resolved.revalidate().authorized)
    }

    fun testRejectsInvalidRootsAndGuestTraversal() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val regularFile = File(temporaryRoot, "not-a-directory").apply { writeText("x") }
        assertThrows<IllegalArgumentException> { paths(regularFile, "/guest") }
        assertThrows<IllegalArgumentException> { paths(root, "guest/shared") }
        assertThrows<IllegalArgumentException> { paths(root, "/guest/../shared") }
        assertThrows<IllegalArgumentException> { paths(root, "/guest/shared\u0000") }

        val paths = paths(root, "/guest/shared")
        assertThrows<IllegalArgumentException> { paths.resolveAttachment("guest/shared/file") }
        assertThrows<IllegalArgumentException> { paths.resolveAttachment("/guest/shared/../outside") }
        assertThrows<IllegalArgumentException> { paths.resolveAttachment("/guest/shared/file\u0000.txt") }
    }

    fun testReportsPathChangedNotAuthorizedAfterRootReplacement() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        File(root, "notes.txt").writeText("before")
        val paths = paths(root, "/guest/shared")
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
        val paths = paths(root, "/guest/shared")
        val resolved = paths.resolveAttachment("/guest/shared/notes.txt")

        assertTrue(file.delete())
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
        val paths = paths(root, "/guest/shared")
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
        val paths = paths(root, "/guest")
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
        val paths = paths(root, "/guest")
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
        val attachment = paths(root, "/guest").resolveAttachment("/guest/notes.txt")
        attachment.openRead().use { input ->
            assertTrue(file.renameTo(File(root, "old.txt")))
            file.writeText("replacement")
            System.gc()
            repeat(50) { file.inputStream().use { assertTrue(it.read() >= 0) } }
            assertEquals("authorized", input.bufferedReader().readText())
        }
    }

    fun testLeafIdentityIsPinnedAcrossDeleteAndRecreate() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val file = File(root, "notes.txt").apply { writeText("authorized") }
        val paths = paths(root, "/guest")
        val attachment = paths.resolveAttachment("/guest/notes.txt")
        val original = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        val pin = attachment.pins.single()
        assertCloseOnExec(pin.fileDescriptor)
        assertTrue(file.delete())
        assertEquals(original, Files.readAttributes(java.nio.file.Paths.get("/proc/self/fd/${pin.fd}"),
            java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        file.writeText("replacement")
        assertFalse(original == Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertFalse(attachment.revalidate().authorized)
        assertThrows<SecurityException> { attachment.openRead().close() }
    }

    fun testRootIdentityIsPinnedBeforeAnyAttachmentResolution() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val original = Files.readAttributes(root.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        val paths = paths(root, "/guest")
        assertEquals(1, retainedDescriptors())
        val rootPin = TerminalPaths::class.java.getDeclaredField("rootDescriptor")
            .apply { isAccessible = true }.get(paths) as android.os.ParcelFileDescriptor
        assertCloseOnExec(rootPin.fileDescriptor)
        assertTrue(root.delete())
        assertTrue(root.mkdirs())
        File(root, "notes.txt").writeText("replacement")
        assertFalse(original == Files.readAttributes(root.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertThrows<SecurityException> { paths.resolveAttachment("/guest/notes.txt") }
        paths.close()
        assertEquals(0, retainedDescriptors())
    }

    fun testParentAndLeafIdentityPinsRemainUntilAttachmentCloses() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val child = File(root, "child").apply { mkdirs() }
        val file = File(child, "notes.txt").apply { writeText("authorized") }
        val paths = paths(root, "/guest")
        val attachment = paths.resolveAttachment("/guest/child/notes.txt")
        assertEquals(3, retainedDescriptors())
        val pinned = attachment.pins.map { it.fd }
        assertTrue(file.delete())
        assertTrue(child.delete())
        assertTrue(child.mkdirs())
        file.writeText("replacement")
        pinned.forEach { assertTrue(File("/proc/self/fd/$it").exists()) }
        assertFalse(attachment.revalidate().authorized)
        assertThrows<SecurityException> { attachment.openRead().close() }
        attachment.close()
        attachment.close()
        assertEquals(1, retainedDescriptors())
        paths.close()
        assertEquals(0, retainedDescriptors())
    }

    fun testAttachmentCloseRejectsNewReadsButDoesNotRevokeAdmittedStream() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        File(root, "notes.txt").writeText("authorized")
        val paths = paths(root, "/guest")
        val attachment = paths.resolveAttachment("/guest/notes.txt")
        attachment.openRead().use { input ->
            attachment.close()
            attachment.close()
            assertFalse(attachment.revalidate().authorized)
            assertThrows<SecurityException> { attachment.openRead().close() }
            paths.close()
            assertEquals(1, retainedDescriptors())
            assertEquals("authorized", input.bufferedReader().readText())
        }
        assertEquals(0, retainedDescriptors())
    }

    fun testOwnerCloseReleasesUnclosedAttachmentsAndRejectsNewWork() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        File(root, "notes.txt").writeText("authorized")
        val paths = paths(root, "/guest")
        val first = paths.resolveAttachment("/guest/notes.txt")
        val second = paths.resolveAttachment("/guest/notes.txt")
        assertEquals(3, retainedDescriptors())
        paths.close()
        paths.close()
        first.close()
        second.close()
        assertEquals(0, retainedDescriptors())
        assertFalse(first.revalidate().authorized)
        assertFalse(paths.validate(second).authorized)
        assertThrows<SecurityException> { first.openRead().close() }
        assertThrows<SecurityException> { paths.resolveAttachment("/guest/notes.txt") }
    }

    fun testFailedResolutionReleasesAllAcquiredChildPins() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val child = File(root, "child").apply { mkdirs() }
        File(child, "notes.txt").writeText("authorized")
        val paths = paths(root, "/guest")
        paths.afterDescriptorOpened = { index -> if (index == 1) throw java.io.IOException("pin failure") }
        assertThrows<SecurityException> { paths.resolveAttachment("/guest/child/notes.txt") }
        assertEquals(1, retainedDescriptors())
        paths.afterDescriptorOpened = null
        paths.resolveAttachment("/guest/child/notes.txt").use { attachment ->
            attachment.openRead().use { assertEquals("authorized", it.bufferedReader().readText()) }
        }
        assertEquals(1, retainedDescriptors())
        paths.close()
        assertEquals(0, retainedDescriptors())
    }

    fun testPinningRaceDoesNotPublishReplacementSnapshot() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val child = File(root, "child").apply { mkdirs() }
        File(child, "notes.txt").writeText("authorized")
        val paths = paths(root, "/guest")
        paths.afterDescriptorOpened = { index ->
            if (index == 0) {
                assertTrue(child.renameTo(File(root, "old-child")))
                assertTrue(child.mkdirs())
                File(child, "notes.txt").writeText("replacement")
            }
        }
        assertThrows<SecurityException> { paths.resolveAttachment("/guest/child/notes.txt") }
        assertEquals(1, retainedDescriptors())
        paths.close()
        assertEquals(0, retainedDescriptors())
    }

    fun testCrossOwnerAttachmentCannotValidateOrOpen() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        File(root, "notes.txt").writeText("authorized")
        val first = paths(root, "/guest")
        val second = paths(root, "/guest")
        val attachment = first.resolveAttachment("/guest/notes.txt")
        assertFalse(second.validate(attachment).authorized)
        assertThrows<SecurityException> { second.openRead(attachment).close() }
        second.close()
        attachment.openRead().use { assertEquals("authorized", it.bufferedReader().readText()) }
        first.close()
        assertEquals(0, retainedDescriptors())
    }

    fun testIndependentReadsAndInPlaceChangesPreserveObjectIdentity() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val file = File(root, "notes.txt").apply { writeText("original") }
        val paths = paths(root, "/guest")
        val first = paths.resolveAttachment("/guest/notes.txt")
        val second = paths.resolveAttachment("/guest/notes.txt")
        first.close()
        assertTrue(second.revalidate().authorized)
        second.openRead().use { one ->
            second.openRead().use { two ->
                assertEquals('o'.code, one.read())
                assertEquals('r'.code, one.read())
                assertEquals('o'.code, two.read())
            }
        }
        file.writeText("same inode, different size and time")
        assertTrue(second.revalidate().authorized)
        second.openRead().use { assertEquals(file.readText(), it.bufferedReader().readText()) }
    }

    fun testFailedReadAdmissionReleasesTemporaryDescriptors() {
        val root = File(temporaryRoot, "shared").apply { mkdirs() }
        val child = File(root, "child").apply { mkdirs() }
        File(child, "notes.txt").writeText("authorized")
        val paths = paths(root, "/guest")
        val attachment = paths.resolveAttachment("/guest/child/notes.txt")
        val retained = retainedDescriptors()
        for (failAt in -1..1) {
            paths.afterDescriptorOpened = { index -> if (index == failAt) throw java.io.IOException("read failure") }
            assertThrows<SecurityException> { attachment.openRead().close() }
            assertEquals(retained, retainedDescriptors())
        }
        paths.afterDescriptorOpened = null
        attachment.openRead().use { assertEquals("authorized", it.bufferedReader().readText()) }
    }

    private fun assertCloseOnExec(descriptor: java.io.FileDescriptor) {
        val flags = android.system.Os::class.java.getMethod("fcntlInt", java.io.FileDescriptor::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(null, descriptor, android.system.OsConstants.F_GETFD, 0) as Int
        assertTrue(flags and android.system.OsConstants.FD_CLOEXEC != 0)
    }

    private fun retainedDescriptors(): Int {
        val prefix = temporaryRoot.canonicalPath + "/"
        return File("/proc/self/fd").listFiles().orEmpty().count { fd ->
            try { android.system.Os.readlink(fd.path).startsWith(prefix) }
            catch (_: android.system.ErrnoException) { false }
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
