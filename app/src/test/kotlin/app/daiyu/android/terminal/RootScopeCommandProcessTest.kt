package app.daiyu.android.terminal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RootScopeCommandProcessTest {
    private val scope = "daiyu-" + "c".repeat(32)
    private fun frame(type: Char, value: ByteArray) = ByteArrayOutputStream().also {
        DataOutputStream(it).apply { writeByte(type.code); writeInt(value.size); write(value) }
    }.toByteArray()
    private fun text(type: Char, value: String) = frame(type, value.toByteArray())
    private fun frames(exit: Int = 7, drain: Boolean = true, output: String = "result\r\n", leader: Boolean = true): ByteArray =
        text('S', scope) + text('H', "DPTY2") + text('O', output) +
            (if (leader) frame('X', java.nio.ByteBuffer.allocate(4).putInt(exit shl 8).array()) else ByteArray(0)) +
            if (drain) text('D', "DROOT1 scope=$scope kill=1 populated=0 reaped=1 removed=1") else ByteArray(0)
    private class Launcher(bytes: ByteArray, @Volatile var ended: Boolean = true) : Process() {
        private val input = ByteArrayInputStream(bytes)
        var inputClosed = false
        var destroys = 0
        override fun getInputStream() = input
        override fun getOutputStream() = object : ByteArrayOutputStream() { override fun close() { inputClosed = true } }
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = ended
        override fun exitValue(): Int { if (!ended) throw IllegalThreadStateException(); return 0 }
        override fun isAlive() = !ended
        override fun destroy() { destroys++ }
    }
    private fun fixture(bytes: ByteArray, ended: Boolean = true, test: (RootScopeCommandProcess, Launcher) -> Unit) {
        val root = Files.createTempDirectory("root-command-").toFile()
        val launcher = Launcher(bytes, ended)
        val session = PtySession(launcher, hostJournal(root, "command"), true)
        val process = RootScopeCommandProcess(session)
        try { test(process, launcher) } finally { process.destroy(); root.deleteRecursively() }
    }
    @Test fun exposesOnlyGuestOutputAndRealGuestExitCodeAfterDrain() = fixture(frames()) { process, _ ->
        assertTrue(process.waitFor(2000, TimeUnit.MILLISECONDS))
        assertEquals("result\r\n", process.inputStream.readBytes().toString(Charsets.UTF_8))
        assertEquals(7, process.exitValue())
        assertFalse(process.isAlive)
    }
    @Test fun launcherExitAndLeaderStatusDoNotReplaceDrain() = fixture(frames(drain = false)) { process, _ ->
        assertFalse(process.waitFor(20, TimeUnit.MILLISECONDS))
        assertTrue(process.isAlive)
        try { process.exitValue(); fail("exit without D") } catch (_: IllegalThreadStateException) { }
    }
    @Test fun receiptCannotFinishBeforeOriginalLauncherExits() = fixture(frames(), false) { process, launcher ->
        assertFalse(process.waitFor(20, TimeUnit.MILLISECONDS))
        launcher.ended = true
        assertTrue(process.waitFor(2000, TimeUnit.MILLISECONDS))
    }
    @Test fun destroyClosesOwnedControlPipeWithoutKillingBareLauncher() = fixture(frames(drain = false)) { process, launcher ->
        process.destroy()
        assertTrue(launcher.inputClosed)
        assertEquals(0, launcher.destroys)
        assertTrue(process.isAlive)
    }
    @Test fun boundedOutputTruncationRemainsReadableAndReported() = fixture(frames(output = "x".repeat(60000)) + ByteArray(0)) { process, _ ->
        assertTrue(process.waitFor(2000, TimeUnit.MILLISECONDS))
        assertEquals(60000, process.inputStream.readBytes().size)
        assertFalse(process.outputTruncated)
    }
    @Test fun drainWithoutLeaderStatusSettlesOwnershipButKeepsExitUnknown() = fixture(frames(leader = false)) { process, _ ->
        assertTrue(process.waitFor(2000, TimeUnit.MILLISECONDS))
        assertFalse(process.isAlive)
        assertNull(process.exitCodeOrNull())
        try { process.exitValue(); fail("unknown exit code") } catch (_: IllegalThreadStateException) { }
    }
    @Test fun malformedReceiptNeverBecomesExited() = fixture(frames() + byteArrayOf('O'.code.toByte())) { process, _ ->
        assertFalse(process.waitFor(20, TimeUnit.MILLISECONDS))
        assertTrue(process.isAlive)
    }
    @Test fun commandBridgeCompletesLongGitDiffWithoutStartingConfiguredPager() {
        val root = Files.createTempDirectory("root-command-pager-").toFile().canonicalFile
        var session: PtySession? = null
        try {
            val work = root.resolve("work 'quoted' \$(literal)").apply { mkdir() }
            val old = work.resolve("old").apply { mkdir() }
            val new = work.resolve("new").apply { mkdir() }
            repeat(60) { index ->
                old.resolve("file-$index.txt").writeText("old\n")
                new.resolve("file-$index.txt").writeText("new\n")
            }
            val marker = root.resolve("pager-invoked")
            val pager = root.resolve("pager").apply {
                writeText("#!/bin/sh\nprintf invoked > '${marker.path}'\ncat\n")
                assertTrue(setExecutable(true))
            }
            val launch = RootScopeLaunch(root.resolve("rootfs").apply { mkdir() },
                root.resolve("su").apply { writeText("fixture"); setExecutable(true) },
                guestWorkingDirectory = work.path,
                command = "test -t 1 || exit 90\n" +
                    "[ \"\$PAGER:\$GIT_PAGER:\$SYSTEMD_PAGER\" = cat:cat:cat ] || exit 91\n" +
                    "git -c core.pager='${pager.path}' diff --no-index --stat old new\n" +
                    "code=\$?; printf '\nDIFF-EXIT:%s\n' \"\$code\"; exit \"\$code\"")
            val helper = java.io.File(checkNotNull(System.getProperty("daiyu.pty.helper")))
            // Exercise the exact guest shell bridge on a real host PTY, without pretending to chroot.
            session = PtySession.openBound(helper, launch.guestArgv().drop(2),
                mapOf("PATH" to "/usr/bin:/bin", "TERM" to "xterm-256color", "HOME" to work.path,
                    "PAGER" to pager.path, "GIT_PAGER" to pager.path, "SYSTEMD_PAGER" to pager.path),
                root, root.resolve("journal"), "pager", 24, 80, null,
                createJournal = { directory, scope, binding -> hostJournal(directory, scope, binding = binding) })
            assertTrue("command must finish without pager input", session.awaitTermination(10_000))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var result = session.read(0, 65536)
            while (result.getString("state") != "exited" && System.nanoTime() < deadline) {
                Thread.sleep(5)
                result = session.read(0, 65536)
            }
            assertEquals(result.toString(), "exited", result.getString("state"))
            assertEquals(1 shl 8, result.getInt("wait_status"))
            assertTrue(result.toString(), result.getString("stdout").contains("60 files changed"))
            assertTrue(result.toString(), result.getString("stdout").contains("DIFF-EXIT:1"))
            assertFalse("configured pager must not launch", marker.exists())
        } finally {
            session?.close()
            assertTrue(session?.awaitTermination(3000) != false)
            root.deleteRecursively()
        }
    }
}
