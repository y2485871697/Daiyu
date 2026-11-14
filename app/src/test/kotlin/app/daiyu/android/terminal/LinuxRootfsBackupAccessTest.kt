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
    @Test fun retainedSharedOccupancyKeepsSelectedEnvironmentUsableButStillPreventsExport() = fixture { files, _, installation ->
        val gate = LinuxRootfsBackupAccess(files, fileKey = ::hostLinuxFileKey, bootIdentity = { "android:10" })
        val directory = File(files, "user-terminal/linux-selection")
        val selected = LinuxEnvironmentStore(directory, directorySync = {}, fileKey = ::hostLinuxFileKey,
            rootfsAccess = gate).apply { select(installation) }
        val common = File(files, "linux-rootfs-access-v1")
        assertEquals("CLAIMED", runLinuxPinChild(common, "claim"))
        assertEquals("CLAIMED", runLinuxPinChild(directory, "claim"))
        val originals = listOf(common, directory).map { parent ->
            parent.listFiles()!!.single { it.name.startsWith(".runner-pin-") }.let { it to it.readBytes() }
        }
        selected.inspectTrustedSelection(installation)
        assertEquals("FILES_READY", selected.status().getString("state"))
        val expected = LinuxGenerationRef(installation.environmentId, installation.generation)
        val lease = selected.acquire(expected)
        assertEquals("env:generation", lease.backend(files).environmentId)
        val running = lease.claimExecution()
        lease.close()
        rejected { selected.clearSelection() }
        rejected { selected.acquireRootfsBackup(installation) }
        running.close()
        selected.acquire(expected).close()
        rejected { selected.select(installation.copy(generation = "replacement")) }
        rejected { selected.acquireRootfsBackup(installation) }
        originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertEquals(1, common.listFiles()!!.count { it.name.startsWith(".runner-pin-") })
        assertEquals(1, directory.listFiles()!!.count { it.name.startsWith(".runner-pin-") })
    }

    @Test fun sharedClaimsAcrossIndependentGenerationsKeepBothInstallationsAndExportFence() = fixture { files, _, installation ->
        val gate = LinuxRootfsBackupAccess(files, fileKey = ::hostLinuxFileKey, bootIdentity = { "android:10" })
        val directory = File(files, "linux-rootfs-access-v1")
        assertEquals("CLAIMED", runLinuxPinChild(directory, "claim"))
        val retained = directory.listFiles()!!.single { it.name.startsWith(".runner-pin-") }
        assertEquals("environment", retained.readLines()[1])
        assertEquals("generation", retained.readLines()[2])
        val bytes = retained.readBytes()
        gate.reserve(installation.copy(environmentId = "other", generation = "independent"), listOf("different identity")).close()
        gate.acquire(installation.copy(environmentId = "other", generation = "independent"), listOf("different identity")).close()
        assertArrayEquals(bytes, retained.readBytes())
        rejected { gate.acquire(installation, listOf("root"), export = true) }
    }

    @Test fun retainedExclusiveOccupancyStillBlocksStatusInspectionAndNewExecution() = fixture { files, _, installation ->
        val gate = LinuxRootfsBackupAccess(files, fileKey = ::hostLinuxFileKey, bootIdentity = { "android:10" })
        assertEquals("CLAIMED", runLinuxPinChild(File(files, "linux-rootfs-access-v1"), "exclusive-claim"))
        rejected { gate.inspectAdmission() }
        rejected { gate.reserve(installation, listOf("root")) }
        rejected { gate.acquire(installation, listOf("root")) }
        rejected { gate.acquire(installation, listOf("root"), export = true) }
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
    @Test fun metadataReservationWritesNoExecutionPinAndStillExcludesExport() = fixture { files, gate, installation ->
        val store = LinuxEnvironmentStore(File(files, "user-terminal/linux-selection"),
            directorySync = TerminalDurability::syncDirectory, rootfsAccess = gate).apply { select(installation) }
        val lease = store.acquire()
        assertFalse(File(files, "linux-rootfs-access-v1").listFiles()!!.any { it.name.startsWith(".runner-pin-") })
        rejected { store.acquireRootfsBackup(installation) }
        val running = lease.claimExecution()
        assertEquals(1, File(files, "linux-rootfs-access-v1").listFiles()!!.count { it.name.startsWith(".runner-pin-") })
        lease.close(); rejected { store.acquireRootfsBackup(installation) }
        running.close()
        store.acquireRootfsBackup(installation).close()
    }

    @Test fun failedStoreClaimReleasesOnlyItsUnstartedCommonExecution() = fixture { files, gate, installation ->
        val directory = File(files, "user-terminal/linux-selection")
        val store = LinuxEnvironmentStore(directory,
            directorySync = TerminalDurability::syncDirectory, rootfsAccess = gate).apply { select(installation) }
        val lease = store.acquire()
        val orphan = File(directory, ".runner-pin-orphan").apply { writeText("unknown") }
        rejected { lease.claimExecution() }
        assertTrue(orphan.exists())
        assertFalse(File(files, "linux-rootfs-access-v1").listFiles()!!.any { it.name.startsWith(".runner-pin-") })
        lease.close()
        gate.acquire(installation, listOf("root")).close()
    }

    @Test fun metadataOwnerDeathDoesNotLeaveADurableExecutionFence() = fixture { files, _, _ ->
        assertEquals("RESERVED", runRootfsReservationChild(files, "reserve"))
        assertFalse(File(files, "linux-rootfs-access-v1").listFiles()!!.any { it.name.startsWith(".runner-pin-") })
        assertEquals("EXPORTED", runRootfsReservationChild(files, "export"))
    }

    @Test fun failedUnstartedRollbackRetainsTheOriginalCloseForRetry() = fixture { files, _, installation ->
        var failCommonSync = false
        var failStoreClaim = false
        val gate = LinuxRootfsBackupAccess(files, directorySync = {
            if (failCommonSync) throw java.io.IOException("common sync")
        })
        val directory = File(files, "user-terminal/linux-selection")
        val store = LinuxEnvironmentStore(directory, directorySync = {
            if (failStoreClaim && it.listFiles()!!.any { file -> file.name.startsWith(".runner-pin-") }) {
                failCommonSync = true
                throw java.io.IOException("store claim sync")
            }
        }, rootfsAccess = gate).apply { select(installation) }
        val lease = store.acquire()
        failStoreClaim = true
        assertThrows(java.io.IOException::class.java) { lease.claimExecution() }
        assertThrows(java.io.IOException::class.java) { lease.close() }
        assertTrue(directory.listFiles()!!.any { it.name.startsWith(".runner-pin-") })
        failCommonSync = false
        lease.close(); lease.close()
        assertFalse(File(files, "linux-rootfs-access-v1").listFiles()!!.any { it.name.startsWith(".runner-pin-") })
        // The uncertain store claim remains fenced; it is not erased as part of common cleanup.
        failStoreClaim = false
        rejected { store.acquire() }
        gate.reserve(installation, listOf("root")).close()
    }

    @Test fun ordinaryDirectoryLeaseAlsoPreventsExport() = fixture { files, gate, installation ->
        val store = LinuxEnvironmentStore(File(files, "user-terminal/linux-selection"),
            directorySync = TerminalDurability::syncDirectory, rootfsAccess = gate).apply { select(installation) }
        store.acquire().use { rejected { store.acquireRootfsBackup(installation) } }
        store.acquireRootfsBackup(installation).close()
    }
}

private fun runRootfsReservationChild(directory: File, operation: String): String {
    val java = File(System.getProperty("java.home"), "bin/" + if (File.separatorChar == '\\') "java.exe" else "java")
    val classes = listOf(LinuxRootfsReservationProcess::class.java, LinuxInstallation::class.java,
        TerminalDurability::class.java, Unit::class.java).map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct()
    return finishLinuxPinChild(ProcessBuilder(java.path, "-cp", classes.joinToString(File.pathSeparator),
        LinuxRootfsReservationProcess::class.java.name, directory.path, operation).redirectErrorStream(true).start())
}

internal object LinuxRootfsReservationProcess {
    @JvmStatic fun main(args: Array<String>) {
        val files = File(args[0])
        val gate = LinuxRootfsBackupAccess(files, fileKey = ::hostLinuxFileKey)
        val installation = LinuxInstallation("debian", "env", "generation", File(files, "rootfs"))
        if (args[1] == "reserve") {
            gate.reserve(installation, listOf("root"))
            println("RESERVED") // Deliberately exit without closing the metadata reservation.
        } else {
            gate.acquire(installation, listOf("root"), export = true).close()
            println("EXPORTED")
        }
    }
}
