package app.daiyu.android.terminal

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class TerminalImageReadTest {
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + ByteArray(32)
    private class TestProcess(private val output: InputStream, active: Boolean = false, private val exit: Int = 0) : Process() {
        private val stopped = CountDownLatch(if (active) 1 else 0)
        var destroyed = false
        override fun getInputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun isAlive() = stopped.count != 0L
        override fun waitFor(): Int { stopped.await(); return exit }
        override fun waitFor(timeout: Long, unit: TimeUnit) = stopped.await(timeout, unit)
        override fun exitValue(): Int { check(!isAlive); return exit }
        override fun destroy() { destroyed = true; output.close(); stopped.countDown() }
    }
    private fun fixture(test: (File, TerminalRegistry) -> Unit) {
        org.junit.Assume.assumeFalse("Requires POSIX directory fsync", System.getProperty("os.name").orEmpty().startsWith("Windows", true))
        val root = Files.createTempDirectory("terminal-image-read").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
        }
        val linux = LinuxBackend("fixture", listOf("not-executed"), listOf("not-executed"), emptyMap(), 12345)
        val registry = TerminalRegistry(context).apply {
            configure(UUID.randomUUID().toString(), TerminalConfiguration(backend = "linux", workingDirectory = root, linux = linux))
            backendOverride = SelectedBackend("linux:fixture", 12345, false, listOf("not-executed"), emptyMap(), root, false)
            processPidReader = { 4242L }
            processStartReader = { "fixture-start" }
        }
        try {
            assertTrue(registry.execute("terminal_open", JSONObject().put("request_id", "open-linux")).getBoolean("ok"))
            test(root, registry)
        } finally {
            registry.close()
            check(registry.awaitOrdinaryTermination(5000))
            root.deleteRecursively()
        }
    }

    @Test fun imageBytesUseTheExistingBackendButNeverCreateTerminalLogsOrMetadata() = fixture { root, registry ->
        val before = root.walkTopDown().map { it.relativeTo(root).path }.toSet()
        var starts = 0
        registry.startProcess = { builder ->
            starts++
            assertTrue(builder.command().last().contains("python3 -I -S"))
            assertTrue(builder.command().last().contains("O_NOFOLLOW"))
            TestProcess(ByteArrayInputStream(("PNG:" + Base64.getEncoder().encodeToString(png) + "\r\n").toByteArray()))
        }
        assertArrayEquals(png, registry.readLinuxTmpPng("/tmp/daiyu-video-test/frame.png") { false })
        assertEquals(1, starts)
        assertEquals(before, root.walkTopDown().map { it.relativeTo(root).path }.toSet())
        assertEquals(0, registry.inspectJobs().getJSONArray("jobs").length())
    }

    @Test fun finishedImageReadsAndFailedStartsLeaveNoProcessRegistryEntries() = fixture { root, registry ->
        val field = TerminalRegistry::class.java.getDeclaredField("jobs").apply { isAccessible = true }
        val map = field.get(null) as Map<*, *>
        fun ownedEntries() = synchronized(map) { map.values.count { job ->
            val log = job!!.javaClass.getDeclaredField("log").apply { isAccessible = true }.get(job) as File
            log.parentFile.parentFile == root
        } }
        registry.startProcess = { TestProcess(ByteArrayInputStream(("PNG:" +
            Base64.getEncoder().encodeToString(png) + "\n").toByteArray())) }
        repeat(4) { assertArrayEquals(png, registry.readLinuxTmpPng("/tmp/frame.png") { false }) }
        assertEquals(0, ownedEntries())
        registry.startProcess = { throw java.io.IOException("fixture start failed") }
        assertThrows(java.io.IOException::class.java) { registry.readLinuxTmpPng("/tmp/frame.png") { false } }
        assertEquals(0, ownedEntries())
    }

    @Test fun traversalNonAsciiMalformedAndTruncatedFramesAreNotDelivered() = fixture { _, registry ->
        var starts = 0
        registry.startProcess = { starts++; throw AssertionError("Invalid path must not launch") }
        for (path in listOf("/workspace/frame.png", "/tmp/../frame.png", "/tmp//frame.png", "/tmp/./frame.png")) {
            assertThrows(IllegalArgumentException::class.java) { registry.readLinuxTmpPng(path) { false } }
        }
        assertEquals(0, starts)
        for (wire in listOf("PNG:not-base64!\n".toByteArray(), "PNG:YQ==\n".toByteArray(),
            ("PNG:" + Base64.getEncoder().encodeToString(png)).toByteArray(),
            "ERR:python3_missing\n".toByteArray(), byteArrayOf(-1, 10))) {
            registry.startProcess = { TestProcess(ByteArrayInputStream(wire)) }
            assertThrows(Exception::class.java) { registry.readLinuxTmpPng("/tmp/frame.png") { false } }
        }
    }

    @Test fun completedReaderErrorsRetainTheirRecoveryGuidanceAndUnknownExitDoesNot() = fixture { _, registry ->
        registry.startProcess = { TestProcess(ByteArrayInputStream("ERR:python3_missing\n".toByteArray()), exit = 127) }
        val missing = assertThrows(LinuxTmpPngReadException::class.java) {
            registry.readLinuxTmpPng("/tmp/frame.png") { false }
        }
        assertEquals("linux_tmp_png_python3_required", missing.reason)
        assertTrue(missing.guidance.contains("不要切换环境"))
        registry.startProcess = { TestProcess(ByteArrayInputStream("ERR:too_large\n".toByteArray()), exit = 1) }
        assertEquals("linux_tmp_png_too_large", assertThrows(LinuxTmpPngReadException::class.java) {
            registry.readLinuxTmpPng("/tmp/frame.png") { false }
        }.reason)
        registry.startProcess = { TestProcess(ByteArrayInputStream("ERR:python3_missing\n".toByteArray()), exit = 0) }
        val unconfirmed = assertThrows(Exception::class.java) { registry.readLinuxTmpPng("/tmp/frame.png") { false } }
        assertFalse(unconfirmed is LinuxTmpPngReadException)
    }

    @Test fun cancellationDestroysTheOwnedReaderAndAllowsTheOriginalRegistryToDrain() = fixture { _, registry ->
        val release = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int { release.await(); return -1 }
            override fun close() { release.countDown() }
        }
        val cancelled = AtomicBoolean(false)
        val process = TestProcess(input, active = true)
        registry.startProcess = { cancelled.set(true); process }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            registry.readLinuxTmpPng("/tmp/frame.png") { cancelled.get() }
        }
        assertTrue(process.destroyed)
        registry.close()
        assertTrue(registry.awaitOrdinaryTermination(5000))
    }
}
