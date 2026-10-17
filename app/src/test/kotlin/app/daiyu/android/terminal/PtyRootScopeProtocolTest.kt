package app.daiyu.android.terminal

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PtyRootScopeProtocolTest {
    private val scope = "daiyu-" + "a".repeat(32)
    private fun frame(type: Char, bytes: ByteArray) = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).apply { writeByte(type.code); writeInt(bytes.size); write(bytes) }
    }.toByteArray()
    private fun text(type: Char, value: String) = frame(type, value.toByteArray(Charsets.US_ASCII))
    private fun hello() = text('S', scope) + text('H', "DPTY2")
    private fun status() = frame('X', byteArrayOf(0, 0, 0, 0))
    private fun drain(name: String = scope) = text('D', "DROOT1 scope=$name kill=1 populated=0 reaped=1 removed=1")

    private class EndedProcess(bytes: ByteArray) : Process() {
        private val input = ByteArrayInputStream(bytes)
        private val output = ByteArrayOutputStream()
        var destroys = 0
        override fun getInputStream() = input
        override fun getOutputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = 0
        override fun isAlive() = false
        override fun destroy() { destroys++ }
    }

    private fun verify(bytes: ByteArray, drained: Boolean, state: String) {
        val root = Files.createTempDirectory("pty-root-protocol-").toFile()
        val process = EndedProcess(bytes)
        val session = PtySession(process, hostJournal(root, "scope"), true)
        try {
            assertEquals(drained, session.awaitTermination(2_000))
            val result = session.read()
            assertEquals(drained, result.getBoolean("scope_drain_confirmed"))
            assertEquals(state, result.getString("state"))
            session.close()
            assertEquals(0, process.destroys)
        } finally { session.close(); root.deleteRecursively() }
    }

    @Test fun launcherExitAndLeaderStatusWithoutDrainRetainOwnership() =
        verify(hello() + status(), false, "unknown")

    @Test fun fullReceiptAndEofConfirmDrain() =
        verify(hello() + status() + drain(), true, "exited")

    @Test fun otherScopeReceiptCannotReleaseOwnership() =
        verify(hello() + status() + drain("daiyu-" + "b".repeat(32)), false, "unknown")

    @Test fun duplicateReceiptCannotReleaseOwnership() =
        verify(hello() + status() + drain() + drain(), false, "unknown")

    @Test fun partialTrailingFrameInvalidatesPreviouslyReceivedDrain() =
        verify(hello() + status() + drain() + byteArrayOf('O'.code.toByte()), false, "unknown")

    @Test fun receiptWithoutHelloOrExecFailureCannotAuthenticateDrain() =
        verify(text('S', scope) + drain(), false, "unknown")

    @Test fun legacyHelloCannotAuthenticateRootLaunch() =
        verify(text('S', scope) + text('H', "DPTY1") + drain(), false, "unknown")

    @Test fun execFailureCanDrainWithoutBecomingSuccessfulExecution() =
        verify(text('S', scope) + frame('E', byteArrayOf(0, 0, 0, 2)) + drain(), true, "unknown")

    @Test fun repeatedExecErrorDoesNotAuthenticateDrain() =
        verify(text('S', scope) + frame('E', byteArrayOf(0, 0, 0, 2)) +
            frame('E', byteArrayOf(0, 0, 0, 2)) + drain(), false, "unknown")

    @Test fun errorBeforeScopeDoesNotAuthenticateDrain() =
        verify(frame('E', byteArrayOf(0, 0, 0, 2)) + text('S', scope) + drain(), false, "unknown")

    @Test fun repeatedTruncationDoesNotAuthenticateDrain() =
        verify(hello() + frame('T', ByteArray(0)) + frame('T', ByteArray(0)) + status() + drain(), false, "unknown")

    @Test fun outputAfterExecErrorDoesNotAuthenticateDrain() =
        verify(hello() + frame('E', byteArrayOf(0, 0, 0, 2)) + text('O', "late") + drain(), false, "unknown")

    @Test fun rootLaunchRebuildsGuestEnvironmentAfterSuShell() {
        val root = Files.createTempDirectory("pty-root-env-").toFile()
        val helper = root.resolve("helper with ' quote").apply { writeText("helper"); setExecutable(true) }
        val su = root.resolve("su with space").apply { writeText("su"); setExecutable(true) }
        val rootfs = root.resolve("root fs").apply { mkdirs() }
        val launch = RootScopeLaunch(rootfs.canonicalFile, su.canonicalFile)
        val environment = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/root", "LANG" to "zh_CN.UTF-8")
        var captured: ProcessBuilder? = null
        val session = PtySession.openBound(helper, launch.guestArgv(), environment, root,
            root.resolve("journal"), "env", 24, 80, null,
            startProcess = { builder -> captured = builder; EndedProcess(hello() + status() + drain()) },
            createJournal = { directory, scope, binding -> hostJournal(directory, scope, binding = binding) },
            rootScope = launch)
        try {
            fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
            val expected = listOf("/system/bin/env", "-i") +
                environment.toSortedMap().map { (key, value) -> "$key=$value" } +
                listOf(helper.path, "--root-scope", "24", "80", "--") + launch.guestArgv()
            assertEquals(listOf(su.path, "-c", "exec " + expected.joinToString(" ") { quote(it) }),
                checkNotNull(captured).command())
            assertEquals(environment, checkNotNull(captured).environment())
            assertTrue(session.awaitTermination(2_000))
        } finally { session.close(); root.deleteRecursively() }
    }

    @Test fun rootConstructionCleanupNeverFallsBackToLauncherExit() {
        val process = EndedProcess(ByteArray(0))
        var requested = false
        val cleanup = PtyStartupCleanup(process, { requested = true }, { false })
        cleanup.requestClose()
        assertTrue(requested)
        assertFalse(cleanup.awaitTermination(1))
        assertEquals(0, process.destroys)
    }

    @Test fun drainReceiptDoesNotConfirmWhileOutputReaderHasNotReachedEof() {
        val root = Files.createTempDirectory("pty-root-eof-").toFile()
        val eof = CountDownLatch(1)
        val bytes = ByteArrayInputStream(hello() + status() + drain())
        val process = object : Process() {
            override fun getInputStream() = object : java.io.InputStream() {
                override fun read(): Int {
                    val value = bytes.read()
                    if (value >= 0) return value
                    eof.await()
                    return -1
                }
            }
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
            override fun waitFor() = 0
            override fun waitFor(timeout: Long, unit: TimeUnit) = true
            override fun exitValue() = 0
            override fun isAlive() = false
            override fun destroy() = Unit
        }
        val session = PtySession(process, hostJournal(root, "scope"), true)
        try {
            assertFalse(session.awaitTermination(10))
            eof.countDown()
            assertTrue(session.awaitTermination(2_000))
        } finally { eof.countDown(); session.close(); session.awaitTermination(2_000); root.deleteRecursively() }
    }
}
