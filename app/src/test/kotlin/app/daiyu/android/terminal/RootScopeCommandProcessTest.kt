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
}
