package app.daiyu.android.terminal

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class LinuxRootfsBackupAccessTest {
    private fun fixture(block: (File, LinuxRootfsBackupAccess, LinuxInstallation) -> Unit) {
        val files = Files.createTempDirectory("rootfs-access-").toFile()
        File(files, "user-terminal/linux-selection").mkdirs()
        val root = File(files, "rootfs").apply { mkdirs() }
        File(root, "bin").mkdir(); File(root, "bin/sh").writeText("never executed")
        val proot = File(files, "proot").apply { writeText("never executed"); setExecutable(true) }
        val loader = File(files, "loader").apply { writeText("never executed") }
        fun hash(value: File) = MessageDigest.getInstance("SHA-256").digest(value.readBytes()).joinToString("") { "%02x".format(it) }
        val installation = LinuxInstallation("debian", "env", "generation", root, proot, hash(proot), loader, hash(loader))
        try { block(files, LinuxRootfsBackupAccess.forFiles(files), installation) }
        finally { files.deleteRecursively() }
    }
    private fun rejected(action: () -> Unit) {
        assertThrows(IllegalStateException::class.java, action)
    }
    @Test fun usageAndExportAreMutuallyExclusiveAcrossReferences() = fixture { _, gate, installation ->
        val one = gate.acquire(installation, listOf("root"))
        val other = gate.acquire(installation.copy(generation = "other"), listOf("root"))
        rejected { gate.acquire(installation, listOf("root"), export = true) }
        one.close(); rejected { gate.acquire(installation, listOf("root"), export = true) }
        other.close()
        gate.acquire(installation, listOf("root"), export = true).use {
            rejected { gate.acquire(installation, listOf("root")) }
            rejected { gate.acquire(installation, listOf("root"), export = true) }
        }
        gate.acquire(installation, listOf("root")).close()
    }
    @Test fun unknownCommonPinNeverBecomesAnIdleRoot() = fixture { files, gate, installation ->
        File(files, "linux-rootfs-access-v1/.runner-pin-orphan").writeText("unknown")
        rejected { gate.acquire(installation, listOf("root")) }
        rejected { gate.acquire(installation, listOf("root"), export = true) }
    }
    @Test fun legacyWorkspacePinPreventsExportEvenForAnotherReference() = fixture { files, gate, installation ->
        val old = File(files, "user-terminal/workspace-generations/old").apply { mkdirs() }
        File(old, ".runner-pin-orphan").writeText("old runner")
        rejected { gate.acquire(installation, listOf("root"), export = true) }
        assertTrue(File(old, ".runner-pin-orphan").exists())
    }
    @Test fun sameFilesDirectoryAlwaysUsesTheSameGate() = fixture { files, gate, installation ->
        assertSame(gate, LinuxRootfsBackupAccess.forFiles(files))
        gate.acquire(installation, listOf("root"), export = true).use {
            rejected { LinuxRootfsBackupAccess.forFiles(files).acquire(installation, listOf("root")) }
        }
    }
    @Test fun storesAndExecutionClaimCannotReleaseRootBeforeTerminationProof() = fixture { files, gate, installation ->
        val selected = LinuxEnvironmentStore(File(files, "user-terminal/linux-selection"),
            directorySync = TerminalDurability::syncDirectory, rootfsAccess = gate).apply { select(installation) }
        val workspace = LinuxEnvironmentStore(File(files, "user-terminal/workspace-generations/new"),
            directorySync = TerminalDurability::syncDirectory, trustedInstallation = installation, rootfsAccess = gate)
        val lease = workspace.acquire()
        val running = lease.claimExecution()
        lease.close() // the execution owns release now
        rejected { selected.acquireRootfsBackup(installation) }
        running.close(); running.close()
        selected.acquireRootfsBackup(installation).use {
            rejected { workspace.acquire() }
            rejected { selected.clearSelection() }
        }
        workspace.acquire().close()
    }
    @Test fun ordinaryDirectoryLeaseAlsoPreventsExport() = fixture { files, gate, installation ->
        val store = LinuxEnvironmentStore(File(files, "user-terminal/linux-selection"),
            directorySync = TerminalDurability::syncDirectory, rootfsAccess = gate).apply { select(installation) }
        store.acquire().use { rejected { store.acquireRootfsBackup(installation) } }
        store.acquireRootfsBackup(installation).close()
    }
}
