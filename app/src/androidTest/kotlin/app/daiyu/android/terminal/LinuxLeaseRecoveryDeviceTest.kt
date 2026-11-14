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
        assertUpgradeObservation(listOf("linux-rootfs-access-v1", "user-terminal/linux-selection",
            "user-terminal/workspace-generations/" + "d".repeat(64)))
    }

    fun testUpgradeObservationDoesNotRequireTheCommonPinDirectory() {
        assertUpgradeObservation(listOf("user-terminal/linux-selection",
            "user-terminal/workspace-generations/" + "e".repeat(64)))
    }

    private fun assertUpgradeObservation(relativeDirectories: List<String>) {
        val files = File(instrumentation.targetContext.cacheDir, "linux-witness-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = files
            override fun getApplicationContext(): Context = this
        }
        try {
            val boot = androidLinuxBootIdentity(context)
            assertNotNull("Android boot counter is readable by the installed app", boot)
            val pins = relativeDirectories.map { relative ->
                val directory = File(files, relative).apply { mkdirs() }
                File(directory, ".runner-pin-" + "a".repeat(32)).apply {
                    writeText("1\ndebian\ngeneration\n${"b".repeat(64)}\n$name\n")
                }
            }
            val originals = pins.map { it.readBytes() to strictLinuxFileKey(it.toPath()) }
            observeExistingLinuxPins(context)
            pins.forEach { pin ->
                val witness = pin.parentFile!!.listFiles()!!.single { it.name.startsWith(".runner-boot-") }
                assertEquals(boot, witness.readLines()[1])
            }
            observeExistingLinuxPins(context)
            pins.zip(originals).forEach { (pin, original) ->
                assertTrue(original.first.contentEquals(pin.readBytes()))
                assertEquals(original.second, strictLinuxFileKey(pin.toPath()))
            }
        } finally { files.deleteRecursively() }
    }

    fun testSameBootSharedOccupancyPreservesSelectedGenerationAndBlocksExport() {
        val files = File(instrumentation.targetContext.cacheDir, "linux-shared-${UUID.randomUUID()}").apply { mkdirs() }.canonicalFile
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
            val retained = listOf(directory, File(files, "linux-rootfs-access-v1")).map { parent ->
                File(parent, ".runner-pin-" + "a".repeat(32)).apply {
                    writeText("2\nenv\ngeneration\n${"b".repeat(64)}\n$name\nshared\n")
                }
            }
            val originals = retained.map { it.readBytes() to strictLinuxFileKey(it.toPath()) }
            retained.zip(originals).forEach { (pin, original) ->
                val boot = checkNotNull(androidLinuxBootIdentity(instrumentation.targetContext))
                val key = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(checkNotNull(original.second).toString().toByteArray(Charsets.UTF_8))
                File(pin.parentFile, ".runner-boot-" + "a".repeat(32))
                    .writeText("2\n$boot\n${pin.name}\n${hash(pin)}\n$key\nclaim\n")
            }
            val selection = TerminalTrustedLinuxSelection(directory, listOf(installation), rootfsAccess = gate)
            val expected = LinuxGenerationRef(installation.environmentId, installation.generation)
            assertEquals(expected, selection.status().selected)
            val lease = store.acquire(expected)
            val execution = lease.claimExecution()
            lease.close()
            execution.close()
            assertEquals(expected, selection.status().selected)
            try { store.acquireRootfsBackup(installation); fail("unknown shared run still excludes export") }
            catch (expectedFailure: IllegalStateException) { }
            try { store.clearSelection(); fail("unknown shared run still excludes selection replacement") }
            catch (expectedFailure: IllegalStateException) { }
            retained.zip(originals).forEach { (pin, original) ->
                assertTrue(original.first.contentEquals(pin.readBytes()))
                assertEquals(original.second, strictLinuxFileKey(pin.toPath()))
                assertEquals(1, pin.parentFile!!.listFiles()!!.count { it.name.startsWith(".runner-pin-") })
            }
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
