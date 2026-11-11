package app.daiyu.android.terminal

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.integration.tools.TerminalPtyLaunch
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Suppress("DEPRECATION")
class LinuxLeaseRecoveryDeviceTest : InstrumentationTestCase() {
    fun testUpgradeObservationUsesRealBootCounterWithoutClearingCurrentBootPin() {
        val files = File(instrumentation.targetContext.cacheDir, "linux-witness-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = files
            override fun getApplicationContext(): Context = this
        }
        try {
            val boot = androidLinuxBootIdentity(context)
            assertNotNull("Android boot counter is readable by the installed app", boot)
            val directory = File(files, "linux-rootfs-access-v1").apply { mkdirs() }
            val pin = File(directory, ".runner-pin-" + "a".repeat(32)).apply {
                writeText("1\ndebian\ngeneration\n${"b".repeat(64)}\n$name\n")
            }
            val bytes = pin.readBytes(); val identity = strictLinuxFileKey(pin.toPath())
            observeExistingLinuxPins(context)
            val witness = directory.listFiles()!!.single { it.name.startsWith(".runner-boot-") }
            assertEquals(boot, witness.readLines()[1])
            observeExistingLinuxPins(context)
            assertTrue(bytes.contentEquals(pin.readBytes()))
            assertEquals(identity, strictLinuxFileKey(pin.toPath()))
        } finally { files.deleteRecursively() }
    }

    fun testPrivateWorkspaceFreezeCreatesNoExecutionPinAndPreservesSelectedGeneration() {
        val files = File(instrumentation.targetContext.cacheDir, "linux-freeze-${UUID.randomUUID()}").apply { mkdirs() }.canonicalFile
        try {
            val rootfs = File(files, "rootfs").apply { File(this, "bin").mkdirs(); File(this, "bin/sh").writeText("never executed") }
            val proot = File(files, "proot").apply { writeText("never executed"); setExecutable(true) }
            val loader = File(files, "loader").apply { writeText("never executed") }
            fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val installation = LinuxInstallation("debian", "env", "generation", rootfs, proot, hash(proot), loader, hash(loader))
            val directory = File(files, "user-terminal/linux-selection")
            val gate = LinuxRootfsBackupAccess.forFiles(files) { androidLinuxBootIdentity(instrumentation.targetContext) }
            val store = LinuxEnvironmentStore(directory, directorySync = TerminalDurability::syncDirectory, rootfsAccess = gate)
            store.select(installation)
            val selection = TerminalTrustedLinuxSelection(directory, listOf(installation), rootfsAccess = gate)
            val reference = LinuxGenerationRef(installation.environmentId, installation.generation)
            assertEquals(reference, selection.status().selected)
            val workspace = File(files, "private/project").apply { mkdirs() }
            val temporary = File(files, "tmp").apply { mkdirs() }
            selection.withInstallation(reference) { generation ->
                TerminalPtyLaunch.Linux.freeze(generation, reference, temporary, workspace,
                    LinuxWorkspaceDirectory(workspace, "/workspace", bindWorkspace = true)).use { frozen ->
                    assertEquals(reference, frozen.expected)
                    assertTrue(frozen.argv.contains("${workspace.path}:/workspace"))
                    assertFalse(File(files, "linux-rootfs-access-v1").listFiles()!!.any { it.name.startsWith(".runner-pin-") })
                    assertEquals(reference, selection.status().selected)
                }
            }
            store.acquireRootfsBackup(installation).close()
        } finally { files.deleteRecursively() }
    }
}
