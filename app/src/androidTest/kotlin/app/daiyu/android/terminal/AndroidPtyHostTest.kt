package app.daiyu.android.terminal

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.test.InstrumentationTestCase
import android.test.mock.MockPackageManager
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import java.io.File
import java.util.UUID

/** Device-only: validates the real APK payload and exec policy, not a host binary. */
@Suppress("DEPRECATION")
class AndroidPtyHostTest : InstrumentationTestCase() {
    private val context: Context get() = instrumentation.targetContext

    private fun owner() = PtyRunOwner(ConversationId("android-pty-test"),
        TaskAuthorization("android-pty-test", UUID.randomUUID().toString()), "android", "installed")

    private fun withNativeDirectory(path: File): Context {
        val info = ApplicationInfo(context.applicationInfo).apply { nativeLibraryDir = path.path }
        return object : ContextWrapper(context) {
            override fun getPackageManager(): PackageManager = object : MockPackageManager() {
                override fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo {
                    assertEquals(context.packageName, packageName)
                    return info
                }
            }
        }
    }

    private fun expectRefusal(expected: String, action: () -> Unit) {
        try {
            action()
            fail("helper must be rejected before launching")
        } catch (failure: IllegalStateException) {
            assertEquals(expected, failure.message)
        }
    }

    fun testHelperIsExecutableImmutableInstalledPayload() {
        val executable = AndroidPtyHost.helper(context)
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        val installation = File(context.applicationInfo.sourceDir).canonicalFile.parentFile!!
        assertEquals(File(nativeDirectory, "libdaiyu_pty.so"), executable)
        assertTrue(executable.toPath().startsWith(installation.toPath()))
        assertTrue(executable.isFile)
        assertTrue(executable.canExecute())
        assertFalse(executable.canWrite())
        assertFalse(nativeDirectory.canWrite())
        // A real ELF executable must be delivered, not a script or placeholder.
        val magic = executable.inputStream().use { input -> ByteArray(4).also { assertEquals(4, input.read(it)) } }
        assertTrue(magic.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())))
    }

    fun testAppWritableDirectoryIsNeverAHelperFallback() {
        val root = File(context.cacheDir, "pty-untrusted-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            expectRefusal("pty_helper_untrusted_installation") {
                AndroidPtyHost.helper(withNativeDirectory(root))
            }
        } finally { root.deleteRecursively() }
    }

    fun testMissingAbiPayloadRefusesBeforeJournalCreation() {
        val root = File(context.cacheDir, "pty-missing-${UUID.randomUUID()}").apply { mkdirs() }
        val journal = File(root, "journal")
        val missingAbi = File(context.applicationInfo.nativeLibraryDir, "missing-abi-${UUID.randomUUID()}")
        try {
            expectRefusal("pty_helper_unavailable_for_installed_abi") {
                AndroidPtyHost.open(withNativeDirectory(missingAbi), owner(), listOf("/system/bin/sh"),
                    emptyMap(), root, journal, "missing")
            }
            assertFalse("a preflight refusal must not consume a durable scope", journal.exists())
        } finally { root.deleteRecursively() }
    }

    fun testActualOpenKeepsOwnerUnknownDeliveryAndWaitStatusSemantics() {
        val root = File(context.cacheDir, "pty-installed-${UUID.randomUUID()}").apply { mkdirs() }
        val owner = owner()
        var session: PtyRunSession? = null
        var startupCleanup: PtyStartupCleanup? = null
        try {
            session = AndroidPtyHost.open(context, owner, listOf("/system/bin/sh"),
                mapOf("PATH" to "/system/bin", "PS1" to "", "TERM" to "xterm"),
                root, File(root, "journal"), "real", rows = 31, columns = 97)
            val copied = TaskAuthorization(owner.authorization.sessionId, owner.authorization.taskId)
            assertEquals("pty_run_owner_mismatch", session.read(copied).getString("error"))
            val denied = session.write(copied, "denied", 1, "exit 99\n".toByteArray())
            assertEquals("not_executed", denied.getString("execution"))
            val delivery = session.write(owner.authorization, "probe", 1,
                ("if [ -t 0 ] && [ -t 1 ] && [ -t 2 ]; then stty size; " +
                    "printf '\\nandroid-pty-live\\n'; exit 7; else exit 91; fi\n").toByteArray())
            assertTrue(delivery.getBoolean("helper_input_written"))
            assertEquals("unknown", delivery.getString("pty_delivery"))
            assertEquals("unknown", delivery.getString("command_completion"))
            assertTrue("must observe helper termination, not infer it from close", session.awaitTermination(5000))
            // Process exit can beat the reader thread draining its final X frame.
            val deadline = System.nanoTime() + 2_000_000_000L
            var result = session.read(owner.authorization)
            while (result.getString("state") == "running" && System.nanoTime() < deadline) {
                Thread.sleep(10)
                result = session.read(owner.authorization)
            }
            assertEquals("exited", result.getString("state"))
            assertEquals(7 shl 8, result.getInt("wait_status"))
            val lines = result.getString("stdout").lines().map(String::trim)
            assertTrue("shell stdio must be a real PTY", "android-pty-live" in lines)
            assertTrue("host dimensions must reach the slave", "31 97" in lines)
            assertTrue("the adapter must never create WRITE grants", owner.authorization.allowedWrites.isEmpty())
        } catch (failure: PtyStartupFailure) {
            startupCleanup = failure.cleanup
            throw failure
        } finally {
            val owned = session
            val startup = startupCleanup
            if (owned != null) {
                try { owned.close() } finally {
                    if (owned.awaitTermination(5000)) root.deleteRecursively()
                    else fail("retain resources if termination is not established")
                }
            } else if (startup != null) {
                try { startup.requestClose() } finally {
                    if (startup.awaitTermination(5000)) root.deleteRecursively()
                    else fail("retain resources after uncertain startup")
                }
            } else root.deleteRecursively()
        }
    }
}
