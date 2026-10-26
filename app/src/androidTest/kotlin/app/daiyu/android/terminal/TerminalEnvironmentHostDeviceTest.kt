package app.daiyu.android.terminal

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Device-only evidence: the public UI host must use the packaged PTY and real app UID. */
@Suppress("DEPRECATION")
class TerminalEnvironmentHostDeviceTest : InstrumentationTestCase() {
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(message, condition())
    }

    fun testPublicFactoryRejectsResidualUntrustedLinuxBeforeCreatingWorkingDirectories() {
        val files = File(instrumentation.targetContext.cacheDir, "r09-trust-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = files
            override fun getApplicationContext(): Context = this
        }
        try {
            val rootfs = File(files, "guest").apply { mkdirs() }
            File(rootfs, "bin").mkdirs()
            File(rootfs, "bin/sh").writeText("test-only shell; never executed")
            val proot = File(files, "proot").apply { writeText("test-only proot; never executed"); setExecutable(true) }
            val loader = File(files, "loader").apply { writeText("test-only loader; never executed") }
            fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            val spec = LinuxInstallation("debian", "r09-linux", "generation-1", rootfs,
                proot, hash(proot), loader, hash(loader))
            val directory = File(files, "user-terminal/linux-selection")
            val store = LinuxEnvironmentStore(directory).apply { select(spec) }
            assertEquals("FILES_READY", store.status().getString("state"))
            val selection = File(directory, "selected-linux.json")
            val saved = selection.readText()
            val reference = LinuxGenerationRef(spec.environmentId, spec.generation)
            for (trusted in listOf(emptyList(), listOf(spec.copy(guestWorkingDirectory = "/different")),
                listOf(spec.copy(prootSha256 = "0".repeat(64))))) {
                val host = TerminalEnvironmentHost.android(context, trusted)
                try {
                    host.refreshLinux()
                    await("Linux trust inspection") { host.state.value.linux.state != "NOT_CHECKED" }
                    assertEquals(if (trusted.isEmpty()) "UNAVAILABLE" else "BROKEN", host.state.value.linux.state)
                    assertNull(host.state.value.linux.selected)
                    val owner = PtyRunOwner(ConversationId("r09-trust"),
                        TaskAuthorization("r09-trust", UUID.randomUUID().toString()), spec.environmentId, spec.generation)
                    assertTrue(host.open(TerminalOpenRequest(owner, "trust-${UUID.randomUUID()}", reference)))
                    await("Linux open must fail before acquiring a process") { !host.state.value.ownsResources }
                    assertEquals("FAILED", host.state.value.phase)
                    assertFalse(host.state.value.terminationConfirmed)
                    assertEquals("", host.state.value.output)
                    assertFalse(File(files, "workspace").exists())
                    assertFalse(File(files, "user-terminal/home").exists())
                    assertFalse(File(files, "user-terminal/tmp").exists())
                    assertFalse(File(files, "user-terminal/journal").exists())
                    assertEquals(saved, selection.readText())
                } finally { host.close() }
            }
        } finally { files.deleteRecursively() }
    }

    fun testTwoAndroidSessionsUseTheirOwnHostCwdEvenWithLinuxWorkspaceMetadata() {
        val root = File(instrumentation.targetContext.filesDir, "cwd-test-${UUID.randomUUID()}").apply { mkdirs() }.canonicalFile
        val hosts = (0..1).map { TerminalEnvironmentHost.android(instrumentation.targetContext) }
        val directories = listOf(File(root, "rootfs/project"), File(root, "private")).map { it.apply { mkdirs() } }
        try {
            hosts.forEachIndexed { index, host ->
                val authorization = TaskAuthorization("cwd-$index", UUID.randomUUID().toString())
                val owner = PtyRunOwner(ConversationId("cwd-$index"), authorization,
                    TerminalEnvironmentHost.ANDROID_ENVIRONMENT, TerminalEnvironmentHost.ANDROID_GENERATION)
                val workspace = TerminalWorkspaceDirectory(directories[index],
                    if (index == 0) LinuxWorkspaceDirectory(directories[index], "/project") else null)
                assertTrue(host.open(TerminalOpenRequest(owner, "cwd-${UUID.randomUUID()}", workspace = workspace)))
                await("Android workspace startup") { host.state.value.acceptsInput || !host.state.value.ownsResources }
                assertTrue("no Linux fallback: ${host.state.value.error}", host.state.value.acceptsInput)
                assertTrue(host.send(authorization, "printf '\\nSESSION_CWD=%s\\n' \"\$(pwd -P)\"; exit\n").get(5, TimeUnit.SECONDS).ok)
            }
            hosts.forEachIndexed { index, host ->
                await("Android workspace exit") { host.state.value.terminationConfirmed }
                assertTrue(host.state.value.output.lines().map(String::trim).contains("SESSION_CWD=${directories[index].path}"))
            }
        } finally {
            hosts.forEach { it.close() }
            hosts.forEach { host -> await("workspace cleanup") { !host.state.value.ownsResources } }
            root.deleteRecursively()
        }
    }

    fun testExplicitAppIdentityOpenRealOutputExitAndNoObservationReplay() {
        val host = TerminalEnvironmentHost.android(instrumentation.targetContext)
        val owner = PtyRunOwner(ConversationId("r09-device"), TaskAuthorization("r09-device", UUID.randomUUID().toString()),
            TerminalEnvironmentHost.ANDROID_ENVIRONMENT, TerminalEnvironmentHost.ANDROID_GENERATION)
        try {
            instrumentation.runOnMainSync {
                assertEquals("IDLE", host.state.value.phase)
                host.refreshLinux()
            }
            await("read-only Linux inspection") { host.state.value.linux.state != "NOT_CHECKED" }
            assertFalse(host.state.value.ownsResources)
            assertEquals("", host.state.value.output)
            instrumentation.runOnMainSync {
                assertTrue(host.open(TerminalOpenRequest(owner, "device-${UUID.randomUUID()}")))
            }
            await("real packaged helper startup: ${host.state.value.error}") {
                host.state.value.acceptsInput || !host.state.value.ownsResources
            }
            assertTrue("startup refused: ${host.state.value.error}", host.state.value.acceptsInput)
            val copied = TaskAuthorization(owner.authorization.sessionId, owner.authorization.taskId)
            assertFalse(host.send(copied, "exit 99\n").get(2, TimeUnit.SECONDS).ok)
            assertFalse(host.requestClose(copied))
            val event = checkNotNull(host.prepareInput(owner.authorization,
                "printf '\\nR09_UID=%s\\n' \"$(id -u)\"; printf 'R09_CWD=%s\\nR09_HOME=%s\\nR09_TMP=%s\\n' \"$(pwd -P)\" \"\$HOME\" \"\$TMPDIR\"; exit 7\n"))
            assertTrue(host.submit(owner.authorization, event).get(5, TimeUnit.SECONDS).ok)
            await("real output and termination") { host.state.value.terminationConfirmed }
            assertEquals(7, host.state.value.exitCode)
            assertTrue(host.state.value.output.lines().map(String::trim).contains("R09_UID=${Process.myUid()}"))
            val files = instrumentation.targetContext.filesDir.canonicalFile
            val lines = host.state.value.output.lines().map(String::trim)
            assertTrue(lines.contains("R09_CWD=${File(files, "workspace").path}"))
            val expectedHome = File(instrumentation.targetContext.filesDir, "user-terminal/home").absolutePath
            assertTrue(lines.contains("R09_HOME=$expectedHome"))
            assertTrue(lines.contains("R09_TMP=$expectedHome"))
            repeat(2) { host.refreshLinux() }
            Thread.sleep(150)
            assertFalse("observation must not restart an exited shell", host.state.value.ownsResources)
            assertEquals(7, host.state.value.exitCode)
        } finally {
            host.close()
            await("host must retain cleanup until process exit") { !host.state.value.ownsResources }
        }
    }
}
