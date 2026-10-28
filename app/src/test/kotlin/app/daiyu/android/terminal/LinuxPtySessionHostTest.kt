package app.daiyu.android.terminal

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Uses the real native helper and a fixture launcher, not a real installed Linux distribution. */
class LinuxPtySessionHostTest {
    private class Fixture(val helper: File, badLauncher: Boolean = false) : AutoCloseable {
        val root = Files.createTempDirectory("linux-pty-production-").toFile()
        val temp = File(root, "tmp").apply { mkdirs() }
        val cwd = File(root, "cwd").apply { mkdirs() }
        val journal = File(root, "journal")
        val argvFile = File(root, "launch-argv")
        val environmentFile = File(root, "launch-environment")
        val resultFile = File(cwd, "shell-result")
        private val rootfs = File(root, "rootfs").apply { mkdirs() }
        val mount = File(root, "shared").apply { mkdirs() }
        val loader = File(root, "loader").apply { writeText("fixture loader, never loaded") }
        val launcher = File(root, "launcher").apply {
            writeText(if (badLauncher) "#!/nonexistent-daiyu-test-interpreter\n" else """
                #!/bin/sh
                printf '%s\n' "${'$'}@" > ${quote(argvFile.path)}
                printf '%s\n' "${'$'}PROOT_LOADER" "${'$'}PROOT_TMP_DIR" "${'$'}HOME" "${'$'}PATH" "${'$'}PWD" > ${quote(environmentFile.path)}
                exec /bin/sh
            """.trimIndent() + "\n")
            check(setExecutable(true))
        }
        val owner = PtyRunOwner(ConversationId("conversation"), TaskAuthorization("session", "run"), "env", "one")
        val expected = LinuxGenerationRef("env", "one")
        val installation: LinuxInstallation
        val store: LinuxEnvironmentStore
        val other: LinuxEnvironmentStore

        init {
            File(rootfs, "bin").mkdirs()
            File(rootfs, "bin/sh").writeText("fixture only, not an installed guest shell")
            installation = LinuxInstallation("fixture", "env", "one", rootfs, launcher, hash(launcher),
                loader, hash(loader), "/workspace", mapOf(mount.path to "/workspace/shared"))
            val selection = File(root, "selection")
            store = LinuxEnvironmentStore(selection).apply { select(installation) }
            other = LinuxEnvironmentStore(selection)
        }

        fun open(): LinuxPtySession = LinuxPtySession.open(owner, store, expected, helper, temp, cwd,
            journal, "scope", rows = 31, columns = 97)

        fun assertPinned() {
            try { other.clearSelection(); fail("helper still owns generation until explicit join") }
            catch (failure: IllegalStateException) { assertEquals("linux_environment_in_use", failure.message) }
        }

        fun assertReleased() {
            other.select(installation.copy(generation = "two"))
            assertEquals("two", store.status().getString("generation"))
        }

        override fun close() { root.deleteRecursively() }
    }

    private fun helper(): File {
        assumeTrue("Linux host required", System.getProperty("os.name").startsWith("Linux"))
        val path = System.getProperty("daiyu.pty.helper")
        assumeTrue("compiled native PTY helper required", path != null)
        return File(checkNotNull(path))
    }

    @Test fun productionOpenActuallyLaunchesSameLeasedArgvEnvironmentAndBoundShell() {
        Fixture(helper()).use { f ->
            val session = try { f.open() } catch (failure: LinuxPtyStartupFailure) {
                failure.cleanup.requestClose()
                assertTrue(failure.cleanup.awaitTermination(3000))
                throw failure
            }
            try {
                f.assertPinned()
                assertFalse(session.awaitTermination(1))
                val copy = TaskAuthorization(f.owner.authorization.sessionId, f.owner.authorization.taskId)
                assertEquals("pty_run_owner_mismatch", session.write(copy, "denied", 1,
                    "touch should-not-exist\n".toByteArray()).getString("error"))
                assertTrue(session.write(f.owner.authorization, "write", 1,
                    "printf 'native-session-ok\\n' > shell-result\n".toByteArray()).getBoolean("ok"))
                val deadline = System.nanoTime() + 3_000_000_000L
                while ((!f.resultFile.exists() || f.resultFile.readText() != "native-session-ok\n" ||
                        !f.environmentFile.exists()) && System.nanoTime() < deadline)
                    Thread.sleep(10)
                assertTrue("real helper must execute shell input", f.resultFile.exists())
                assertEquals("native-session-ok\n", f.resultFile.readText())
                assertEquals(listOf("-0", "-r", f.installation.rootfs.path, "-w", "/workspace", "-b",
                    "/dev:/dev", "-b", "${f.mount.path}:/workspace/shared", "/bin/sh"), f.argvFile.readLines())
                assertEquals(listOf(f.loader.path, f.temp.canonicalPath, "/root",
                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", f.cwd.canonicalPath),
                    f.environmentFile.readLines())
                assertFalse(File(f.cwd, "should-not-exist").exists())
                try { f.open(); fail("durable scope cannot create another helper") }
                catch (failure: IllegalStateException) { assertFalse(failure is LinuxPtyStartupFailure) }
                f.assertPinned()
                session.close()
                assertEquals("pty_run_closed", session.read(f.owner.authorization).getString("error"))
                f.assertPinned()
                assertTrue(session.awaitTermination(3000))
                assertTrue(session.awaitTermination(1))
                f.assertReleased()
            } finally {
                session.close()
                assertTrue("must not delete fixture until helper exits", session.awaitTermination(3000))
            }
        }
    }

    @Test fun productionExecFailureTransfersCleanupAndDoesNotReleaseEvenAnAlreadyExitedProcess() {
        Fixture(helper(), badLauncher = true).use { f ->
            val failure = try {
                val unexpected = f.open()
                unexpected.close()
                assertTrue(unexpected.awaitTermination(3000))
                error("fixture launcher cannot execute")
            } catch (failure: LinuxPtyStartupFailure) { failure }
            try {
                assertTrue(failure.cause is PtyStartupFailure)
                f.assertPinned()
                failure.cleanup.requestClose()
                f.assertPinned()
                assertTrue(failure.cleanup.awaitTermination(3000))
                assertTrue(failure.cleanup.awaitTermination(1))
                f.assertReleased()
            } finally {
                failure.cleanup.requestClose()
                assertTrue("must join original helper after failed launch", failure.cleanup.awaitTermination(3000))
            }
        }
    }

    companion object {
        private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
        private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }
}
