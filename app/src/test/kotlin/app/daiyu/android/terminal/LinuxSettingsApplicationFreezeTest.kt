package app.daiyu.android.terminal

import app.daiyu.android.integration.tools.TerminalPtyLaunch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Tests the exact trust/admission components of the worker-only application freeze seam. */
class LinuxSettingsApplicationFreezeTest {
    @Test fun revokedOrUnknownReferenceNeverEntersFreezeAction() {
        val ref = LinuxGenerationRef("environment", "generation")
        val admission = LinuxSettingsAdmission(setOf(ref))
        val calls = AtomicInteger()
        admission.deny(ref)
        listOf(ref, ref.copy(generation = "different")).forEach { candidate ->
            try { admission.freeze(candidate) { calls.incrementAndGet() }; fail("denied freeze admitted") }
            catch (failure: IllegalStateException) { assertEquals("linux_trusted_installation_unavailable", failure.message) }
        }
        assertEquals(0, calls.get())
    }

    @Test fun denialCannotOvertakeInFlightPinAndSubsequentFreezesAreDenied() {
        val ref = LinuxGenerationRef("environment", "generation")
        val admission = LinuxSettingsAdmission(setOf(ref))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val denied = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val freezer = Thread {
            try { admission.freeze(ref) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            catch (t: Throwable) { failure.set(t) }
        }
        freezer.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val remover = Thread { admission.deny(ref); denied.countDown() }
        remover.start()
        try { assertFalse(denied.await(50, TimeUnit.MILLISECONDS)) }
        finally { release.countDown(); freezer.join(5000); remover.join(5000) }
        assertFalse(freezer.isAlive)
        assertFalse(remover.isAlive)
        assertNull(failure.get())
        assertFalse(admission.contains(ref))
        try { admission.freeze(ref) { fail("revoked generation readmitted") }; fail("freeze succeeded") }
        catch (_: IllegalStateException) {}
    }

    @Test fun onlyExistingMatchingTrustedSelectionCanProduceNonStartingPinnedLaunch() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val root = Files.createTempDirectory("terminal-trusted-freeze-").toFile()
        try {
            val rootfs = File(root, "rootfs").apply { File(this, "bin").mkdirs(); File(this, "bin/sh").writeText("never executed") }
            val proot = File(root, "proot").apply { writeText("never executed"); check(setExecutable(true)) }
            val loader = File(root, "loader").apply { writeText("fixture") }
            fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val trusted = LinuxInstallation("fixture", "environment", "generation", rootfs, proot, hash(proot), loader, hash(loader))
            val ref = LinuxGenerationRef(trusted.environmentId, trusted.generation)
            val directory = File(root, "selection")
            val selection = TerminalTrustedLinuxSelection(directory, listOf(trusted))
            try { selection.withSelection(ref) { fail("absent selection frozen") }; fail("missing selection accepted") }
            catch (_: IllegalStateException) {}
            assertFalse(directory.exists()) // No implicit selection, directories, install, or download.
            val store = LinuxEnvironmentStore(directory)
            store.select(trusted.copy(distribution = "untrusted-label"))
            try { selection.withSelection(ref) { fail("untrusted metadata frozen") }; fail("mismatch accepted") }
            catch (_: IllegalStateException) {}
            store.select(trusted)
            val temp = File(root, "tmp").apply { mkdirs() }
            val cwd = File(root, "cwd").apply { mkdirs() }
            val admission = LinuxSettingsAdmission(setOf(ref))
            val frozen = admission.freeze(ref) {
                selection.withSelection(ref) { TerminalPtyLaunch.Linux.freeze(it, ref, temp, cwd) }
            }
            assertEquals(listOf(proot.path, "-0", "-r", rootfs.path, "-w", "/", "-b", "/dev:/dev", "/bin/sh"), frozen.argv)
            assertEquals(loader.path, frozen.environment["PROOT_LOADER"])
            admission.deny(ref) // Does not release an already-owned pin.
            try { store.clearSelection(); fail("lost pre-approval pin") } catch (_: IllegalStateException) {}
            frozen.close()
            store.clearSelection()
        } finally { root.deleteRecursively() }
    }
}
