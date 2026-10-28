package app.daiyu.android.terminal.install

import app.daiyu.android.terminal.hostLinuxCatalog
import app.daiyu.android.terminal.hostDirectorySync
import app.daiyu.android.terminal.hostLinuxStore
import app.daiyu.android.terminal.LinuxInstallation
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

/** Inert helpers/archive/guest shell; injected probe never launches a process. */
class LinuxInstallRecoveryBoundaryTest : TestCase() {
    private class Fixture(val root: File) {
        val payload = "inert archive".toByteArray()
        val artifact = LinuxRootfsArtifact("fixture", "alpine", "test", "arm64-v8a", RootfsArchive.TAR_GZIP,
            payload.size.toLong(), hash(payload), listOf(RootfsMirror("primary", URI("https://example.invalid/rootfs"))))
        val managed = File(root, "linux")
        val proot = File(root, "proot").apply { writeText("inert proot"); setExecutable(true) }
        val loader = File(root, "loader").apply { writeText("inert loader") }
        val target = File(managed, "environment/generation/rootfs")
        val installation = LinuxInstallation("alpine", "environment", "generation", target,
            proot, hash(proot.readBytes()), loader, hash(loader.readBytes()))
        var requests = 0
        var probes = 0
        var moves = 0
        var probeFailure: Exception? = null
        fun installer(force: (java.nio.channels.FileChannel) -> Unit = { it.force(true) },
                      sync: (File) -> Unit = {}) = RootlessLinuxInstaller(managed,
            LinuxRootfsExtraction { _, _, stage, _ ->
                File(stage, "bin").mkdir()
                File(stage, "bin/sh").writeText("inert shell")
            }, LinuxInstallProbeRunner { _, _, _ ->
                probes++
                probeFailure?.let { throw it }
                LinuxProbeResult(0, "0\n")
            }, LinuxArtifactDownload { _, _, destination, _ -> requests++; destination.writeBytes(payload) },
            sync, { from, to -> moves++; Files.move(from.toPath(), to.toPath()); Unit }, force, fileOps = HostInstallFileOps)
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("linux-recovery-boundary").toFile()
        try { block(Fixture(root)) } finally { root.deleteRecursively() }
    }

    fun testDiscardPublishesDurableEmptyAndNeverDeletesGenerationDirectory() = fixture { f ->
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        installer.rememberRequest(prepared)
        installer.discardRequest(prepared)
        assertEquals("daiyu-install-empty-v1\n", File(f.managed, ".install-request").readText())
        assertTrue(f.target.parentFile.isDirectory)
        assertFalse(f.target.exists())
        installer.discardRequest(prepared)
        assertNull(f.installer().recoverRequest(f.packaged(), listOf(f.artifact)))
        assertEquals(0, f.requests)
        assertEquals(0, f.probes)
    }

    fun testDiscardAfterEmptyRenameSyncFailureRetriesSameDescription() = fixture { f ->
        var failEmptySync = true
        val installer = f.installer(sync = {
            val journal = File(f.managed, ".install-request")
            if (failEmptySync && journal.isFile && journal.readText() == "daiyu-install-empty-v1\n") throw IOException("empty_sync_failed")
        })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        installer.rememberRequest(prepared)
        try { installer.discardRequest(prepared); fail("discard must fail before durability is acknowledged") }
        catch (_: IOException) { }
        assertEquals("daiyu-install-empty-v1\n", File(f.managed, ".install-request").readText())
        failEmptySync = false
        installer.discardRequest(prepared)
        assertNull(f.installer().recoverRequest(f.packaged(), listOf(f.artifact)))
        assertTrue(f.target.parentFile.isDirectory)
    }

    fun testFailedDescriptionForceCanBeRetiredWithoutReplacingUnknownJournal() = fixture { f ->
        var failForce = true
        val installer = f.installer(force = { if (failForce) throw IOException("description_force_failed") else it.force(true) })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        try { installer.rememberRequest(prepared); fail("force should fail") } catch (_: IOException) { }
        assertFalse(File(f.managed, ".install-request").exists())
        assertFalse(File(f.managed, ".install-request.tmp").exists())
        failForce = false
        installer.discardRequest(prepared)
        assertEquals("daiyu-install-empty-v1\n", File(f.managed, ".install-request").readText())
        assertNull(f.installer().recoverRequest(f.packaged(), listOf(f.artifact)))
        assertEquals(0, f.requests)
    }

    fun testFailedAncestorSyncCanBeRetiredWithoutCreatingGeneration() = fixture { f ->
        var failSync = true
        val installer = f.installer(sync = { if (failSync) throw IOException("ancestor_sync_failed") })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        try { installer.rememberRequest(prepared); fail("ancestor sync should fail") } catch (_: IOException) { }
        failSync = false
        installer.discardRequest(prepared)
        assertEquals("daiyu-install-empty-v1\n", File(f.managed, ".install-request").readText())
        assertFalse(f.target.exists())
        assertNull(f.installer().recoverRequest(f.packaged(), listOf(f.artifact)))
        assertEquals(0, f.probes)
    }

    fun testDiscardRejectsTmpForeignJournalAndEveryGenerationResidueWithoutDeletingThem() {
        for (residue in listOf(".install-request.tmp", "foreign-journal", ".install-lock", ".staging-owned", "archive", ".registration", "rootfs")) fixture { f ->
            val installer = f.installer()
            val prepared = installer.prepare(f.artifact, "primary", f.installation)
            installer.rememberRequest(prepared)
            val journal = File(f.managed, ".install-request")
            val extra = when (residue) {
                ".install-request.tmp" -> File(f.managed, residue)
                "foreign-journal" -> journal
                else -> File(f.target.parentFile, residue)
            }
            extra.writeText("retained-$residue")
            val journalBytes = journal.readBytes()
            try { installer.discardRequest(prepared); fail("must retain $residue") } catch (_: IllegalStateException) { }
            assertTrue(journalBytes.contentEquals(journal.readBytes()))
            assertEquals("retained-$residue", extra.readText())
            assertEquals(0, f.requests)
        }
    }

    fun testMissingJournalCannotHideOtherGenerationOrSymlinkData() {
        for (symlink in listOf(false, true)) fixture { f ->
            val installer = f.installer()
            val prepared = installer.prepare(f.artifact, "primary", f.installation)
            installer.rememberRequest(prepared)
            val journal = File(f.managed, ".install-request")
            assertTrue(journal.delete())
            val retained = File(f.root, "retained").apply { writeText("must-stay") }
            val extra = File(f.managed, "other-generation")
            if (symlink) Files.createSymbolicLink(extra.toPath(), retained.toPath()) else extra.writeText("other-data")
            try { installer.discardRequest(prepared); fail("missing journal is not proof of an empty installation") }
            catch (_: IllegalStateException) { }
            assertFalse(journal.exists())
            assertTrue(Files.exists(extra.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("must-stay", retained.readText())
        }
    }

    fun testSuccessfulRegistrationRestartRetiresRequestAndAllowsRemovalWithoutResurrection() {
        for (scenario in listOf("completed", "crash-after-approve", "tombstone", "unknown")) fixture { f -> runBlocking {
            val first = f.installer()
            val prepared = first.prepare(f.artifact, "primary", f.installation)
            first.rememberRequest(prepared)
            assertTrue(first.install(first.approveDownloadAndInstall(prepared), LinuxInstallCancellation()).directorySyncConfirmed)
            val catalog = app.daiyu.android.terminal.hostLinuxCatalog(File(f.root, "catalog"))
            val approval = catalog.freezeForApproval(f.installation)
            first.rememberRegistration(prepared, approval)
            catalog.approve(approval)
            val reference = app.daiyu.android.terminal.LinuxGenerationRef(
                approval.installation.environmentId, approval.installation.generation)
            if (scenario == "completed") first.completeRegistration(prepared, approval)
            if (scenario == "tombstone") catalog.remove(reference)
            val unknownLock = File(f.target.parentFile, ".install-lock")
            if (scenario == "unknown") assertTrue(unknownLock.mkdir())
            val restarted = f.installer()
            var completions = 0
            val transactions = object : LinuxInstallTransactions {
                override fun prepare(artifact: LinuxRootfsArtifact, mirrorId: String, installation: LinuxInstallation) =
                    error("restart must not prepare a replacement")
                override fun install(prepared: PreparedLinuxInstall, cancellation: LinuxInstallCancellation): LinuxInstallReceipt =
                    error("restart must not replay installation")
                override fun confirmPublishedDurability(prepared: PreparedLinuxInstall): LinuxInstallReceipt =
                    error("recorded registration needs no new install consent")
                override fun remember(prepared: PreparedLinuxInstall) = error("must not replace request")
                override fun rememberRegistration(prepared: PreparedLinuxInstall, approval: app.daiyu.android.terminal.FrozenLinuxApproval) =
                    error("must not approve again")
                override fun discard(prepared: PreparedLinuxInstall) = error("must not discard")
                override fun recover(helpers: PackagedProotHelpers) = restarted.recoverRequest(helpers, listOf(f.artifact))
                override fun completeRegistration(prepared: PreparedLinuxInstall, approval: app.daiyu.android.terminal.FrozenLinuxApproval) {
                    completions++
                    restarted.completeRegistration(prepared, approval)
                }
            }
            val adapter = app.daiyu.android.terminal.LinuxSettingsCatalogAdapter(catalog)
            val coordinator = LinuxInstallCoordinator(transactions, { f.packaged() }, f.managed, adapter)
            withTimeout(5000) { coordinator.state.first { !it.busy } }
            val journal = File(f.managed, ".install-request")
            val recoveredIdentity = coordinator.state.value.prepared
            if (scenario == "unknown") {
                assertTrue(coordinator.state.value.unknown)
                assertTrue(coordinator.blocksManual)
                assertEquals(0, completions)
                assertEquals(f.installation, coordinator.state.value.prepared!!.installation)
                assertTrue(unknownLock.isDirectory)
                assertTrue(journal.readText().startsWith("daiyu-install-request-v1"))
            } else if (scenario == "crash-after-approve") {
                assertTrue(coordinator.blocksManual)
                assertEquals(approval.installation, coordinator.state.value.registration?.installation)
                assertFalse(checkNotNull(coordinator.state.value.receipt).directorySyncConfirmed)
                assertEquals(0, completions)
                assertTrue(journal.readText().startsWith("daiyu-install-request-v1"))
            } else {
                assertFalse(coordinator.blocksManual)
                assertNull(coordinator.state.value.prepared)
                assertEquals("daiyu-install-empty-v1\n", journal.readText())
                assertNull(restarted.recoverRequest(f.packaged(), listOf(f.artifact)))
                assertEquals(if (scenario == "completed") 0 else 1, completions)
            }
            if (scenario != "tombstone") {
                val admission = app.daiyu.android.terminal.LinuxSettingsAdmission(setOf(reference))
                val controller = app.daiyu.android.terminal.LinuxSettingsController(adapter, admission,
                    startupEntries = listOf(approval.installation), installation = coordinator)
                val page = controller.enterPage()
                withTimeout(5000) { page.state.first { !it.busy } }
                assertTrue(page.remove(reference)) // even UNKNOWN cannot block known-reference denial
                assertFalse(admission.contains(reference)) // synchronous, before catalog IO
                withTimeout(5000) { page.state.first { !it.busy } }
                assertNull(page.state.value.pendingRemoval)
                controller.close()
            }
            if (scenario == "unknown") {
                assertSame(recoveredIdentity, coordinator.state.value.prepared)
                assertTrue(coordinator.state.value.unknown)
                assertTrue(unknownLock.isDirectory)
            }
            assertTrue(catalog.load().isEmpty())
            assertTrue(runCatching { catalog.approve(approval) }.isFailure)
            assertTrue(f.target.isDirectory)
            assertTrue(File(f.target.parentFile, ".registration-request").isFile)
            assertEquals(1, f.requests)
            assertEquals(1, f.probes)
            coordinator.close()
        } }
    }

    fun testIdenticalRegistrationRetryMustForceFileBeforeParentAndJournalLink() = fixture { f ->
        var reject = false
        var attempts = 0
        val events = mutableListOf<String>()
        val installer = f.installer(force = { channel ->
            if (reject) { attempts++; events.add("force"); throw IOException("file force") }
            events.add("force")
            channel.force(true)
        }, sync = { events.add("sync:${it.path}") })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        installer.rememberRequest(prepared)
        installer.install(installer.approveDownloadAndInstall(prepared), LinuxInstallCancellation())
        val approval = app.daiyu.android.terminal.FrozenLinuxApproval(f.installation.copy(generation = "registry"))
        val request = File(f.managed, ".install-request")
        val unlinked = request.readBytes()
        val registration = File(f.target.parentFile, ".registration-request")
        reject = true
        repeat(2) {
            events.clear()
            assertTrue(runCatching { installer.rememberRegistration(prepared, approval) }.isFailure)
            assertEquals(listOf("force"), events) // no directory sync / journal link on either failure
            assertTrue(unlinked.contentEquals(request.readBytes()))
            assertTrue(registration.isFile)
        }
        assertEquals(2, attempts)
        val sameBytes = registration.readBytes()
        reject = false
        events.clear()
        installer.rememberRegistration(prepared, approval)
        assertEquals("force", events[0])
        assertEquals("sync:${f.target.parentFile.path}", events[1])
        assertTrue(sameBytes.contentEquals(registration.readBytes()))
        assertTrue(request.readText().contains("registration_generation=registry\n"))
        assertEquals(1, f.requests)
        assertEquals(1, f.probes)
    }

    fun testUnknownRetainsStagingArchiveAndExclusiveWriterLease() = fixture { f ->
        val retained = Any()
        f.probeFailure = LinuxInstallProbeUnknown(retained)
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        val failure = runCatching { installer.install(installer.approveDownloadAndInstall(prepared), LinuxInstallCancellation()) }.exceptionOrNull()
        assertTrue(failure is LinuxInstallProbeUnknown)
        assertSame(retained, (failure as LinuxInstallProbeUnknown).retainedResource)
        val parent = f.target.parentFile
        assertTrue(File(parent, ".install-lock").isDirectory)
        assertTrue(parent.listFiles()!!.any { it.name.startsWith(".staging-") && it.isDirectory })
        assertTrue(parent.listFiles()!!.any { it.name.endsWith(".archive") })
        assertFalse(f.target.exists())
        assertTrue(runCatching { installer.install(installer.approveDownloadAndInstall(prepared), LinuxInstallCancellation()) }.isFailure)
        assertEquals(1, f.requests)
    }

    fun testExplicitDurabilityRecoveryRequiresRequestMarkerAndWriterExclusion() = fixture { f ->
        var failSync = true
        val installer = f.installer { directory ->
            if (directory == f.target.parentFile && f.target.exists() && failSync) throw IOException("parent fsync")
        }
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        val pending = try {
            installer.install(installer.approveDownloadAndInstall(prepared), LinuxInstallCancellation())
            error("persistent sync failure must block cleanup")
        } catch (failure: LinuxInstallCleanupPending) { failure }
        assertFalse(pending.receipt!!.directorySyncConfirmed)
        assertNotNull(installer.inspectPublished(prepared))
        assertTrue(File(f.target.parentFile, ".install-lock").exists())
        assertTrue(runCatching { installer.confirmPublishedDurability(prepared) }.isFailure)
        failSync = false
        assertFalse(installer.retryCleanup(prepared)!!.directorySyncConfirmed)
        val lock = File(f.target.parentFile, ".install-lock")
        assertTrue(lock.mkdir())
        assertTrue(runCatching { installer.confirmPublishedDurability(prepared) }.isFailure)
        assertTrue(lock.isDirectory)
        assertTrue(lock.delete())
        val other = installer.prepare(f.artifact, "primary", f.installation.copy(prootSha256 = "0".repeat(64)))
        assertTrue(runCatching { installer.confirmPublishedDurability(other) }.isFailure)
        val otherOwner = f.installer()
        assertTrue(runCatching { otherOwner.confirmPublishedDurability(prepared) }.isFailure)
        assertTrue(installer.confirmPublishedDurability(prepared).directorySyncConfirmed)
        assertEquals(1, f.requests)
        assertEquals(1, f.probes)
        assertEquals(1, f.moves)
    }
    private fun Fixture.packaged() = PackagedProotHelpers(artifact.abi, proot, installation.prootSha256,
        loader, installation.loaderSha256)

    private fun Fixture.tree(): Map<String, List<Byte>> = managed.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(managed).path to it.readBytes().toList() }

    fun testRestartRebuildsPublishedRequestReadOnlyThenExplicitlyConfirmsOriginalPath() = fixture { f ->
        var failSync = true
        val first = f.installer { directory ->
            if (directory == f.target.parentFile && f.target.exists() && failSync) throw IOException("directory fsync")
        }
        val original = first.prepare(f.artifact, "primary", f.installation)
        first.rememberRequest(original)
        val pending = try {
            first.install(first.approveDownloadAndInstall(original), LinuxInstallCancellation())
            error("persistent sync failure must block cleanup")
        } catch (failure: LinuxInstallCleanupPending) { failure }
        assertFalse(pending.receipt!!.directorySyncConfirmed)
        failSync = false
        assertFalse(first.retryCleanup(original)!!.directorySyncConfirmed)
        val frozen = app.daiyu.android.terminal.FrozenLinuxApproval(f.installation.copy(generation = "original-catalog-generation"))
        first.rememberRegistration(original, frozen)
        val before = f.tree()
        val directories = f.managed.walkTopDown().map { it.path }.toList()
        var syncs = 0
        val restarted = f.installer { syncs++ }
        val recovered = restarted.recoverRequest(f.packaged(), listOf(f.artifact))!!
        assertEquals(0, syncs)
        assertEquals(before, f.tree())
        assertEquals(directories, f.managed.walkTopDown().map { it.path }.toList())
        assertEquals(f.installation, recovered.prepared.installation)
        assertTrue(recovered.published)
        assertFalse(recovered.unknown)
        assertEquals(frozen.installation, recovered.registration!!.installation)
        assertEquals(1, f.requests)
        assertEquals(1, f.probes)
        assertEquals(1, f.moves)
        // Rebuilding gives only metadata. An explicit same-path durability confirmation is needed.
        assertTrue(restarted.confirmPublishedDurability(recovered.prepared).directorySyncConfirmed)
        assertTrue(syncs > 0)
        assertEquals(1, f.requests)
        assertEquals(1, f.probes)
        assertEquals(1, f.moves)
        restarted.rememberRegistration(recovered.prepared, checkNotNull(recovered.registration))
    }

    fun testRestartBeforeInstallRetainsTargetAndRequiresFreshExplicitApproval() = fixture { f ->
        val first = f.installer()
        val original = first.prepare(f.artifact, "primary", f.installation)
        first.rememberRequest(original)
        val before = f.tree()
        val restarted = f.installer { error("recovery must not fsync") }
        val recovered = restarted.recoverRequest(f.packaged(), listOf(f.artifact))!!
        assertEquals(original.installation, recovered.prepared.installation)
        assertFalse(recovered.published)
        assertFalse(recovered.unknown)
        assertNull(recovered.registration)
        assertEquals(before, f.tree())
        assertEquals(0, f.requests)
        assertEquals(0, f.probes)
        assertTrue(runCatching { restarted.install(first.approveDownloadAndInstall(original), LinuxInstallCancellation()) }.isFailure)
        assertEquals(0, f.requests)
        val explicit = f.installer()
        val request = explicit.recoverRequest(f.packaged(), listOf(f.artifact))!!.prepared
        assertTrue(explicit.install(explicit.approveDownloadAndInstall(request), LinuxInstallCancellation()).directorySyncConfirmed)
        assertEquals(f.target, request.targetRootfs)
        assertEquals(1, f.requests)
    }

    fun testMissingOrTamperedJournalMarkerAndRegistrationFailClosedWithoutEffects() {
        for (damage in listOf("journal-missing", "journal-hash", "journal-url", "journal-generation",
            "marker-missing", "marker-changed", "target-missing", "registration-missing", "registration-changed", "request-temp")) fixture { f ->
            val first = f.installer()
            val request = first.prepare(f.artifact, "primary", f.installation)
            first.rememberRequest(request)
            first.install(first.approveDownloadAndInstall(request), LinuxInstallCancellation())
            first.rememberRegistration(request,
                app.daiyu.android.terminal.FrozenLinuxApproval(f.installation.copy(generation = "registry")))
            val journal = File(f.managed, ".install-request")
            val marker = File(f.target, ".daiyu-rootfs-install")
            val registration = File(f.target.parentFile, ".registration-request")
            when (damage) {
                "journal-missing" -> assertTrue(journal.delete())
                "journal-hash" -> journal.writeText(journal.readText().replace(f.artifact.sha256, "0".repeat(64)))
                "journal-url" -> journal.writeText(journal.readText().replace("example.invalid", "other.invalid"))
                "journal-generation" -> journal.writeText(journal.readText().replace("generation=generation", "generation=other"))
                "marker-missing" -> assertTrue(marker.delete())
                "marker-changed" -> marker.appendText("changed\n")
                "target-missing" -> assertTrue(f.target.deleteRecursively())
                "registration-missing" -> assertTrue(registration.delete())
                "registration-changed" -> registration.appendText("changed\n")
                "request-temp" -> File(f.managed, ".install-request.tmp").writeText("incomplete")
            }
            val before = f.tree()
            val restarted = f.installer { error("recovery must not fsync") }
            assertTrue(damage, runCatching { restarted.recoverRequest(f.packaged(), listOf(f.artifact)) }.isFailure)
            assertEquals(before, f.tree())
            assertEquals(1, f.requests)
            assertEquals(1, f.probes)
        }
    }

    fun testRestartRejectsChangedOrMissingOriginalHelpersAndChangedPinnedCatalog() = fixture { f ->
        val first = f.installer()
        val request = first.prepare(f.artifact, "primary", f.installation)
        first.rememberRequest(request)
        val restarted = f.installer { error("recovery must not fsync") }
        assertTrue(runCatching { restarted.recoverRequest(f.packaged().copy(proot = File(f.root, "new-proot")), listOf(f.artifact)) }.isFailure)
        assertTrue(runCatching { restarted.recoverRequest(f.packaged(), emptyList()) }.isFailure)
        f.proot.appendText("tampered")
        assertTrue(runCatching { restarted.recoverRequest(f.packaged(), listOf(f.artifact)) }.isFailure)
        assertTrue(f.proot.delete())
        assertTrue(runCatching { restarted.recoverRequest(f.packaged(), listOf(f.artifact)) }.isFailure)
        assertEquals(0, f.requests)
        assertEquals(0, f.probes)
    }

    fun testRestartUnknownProbeAndUnexplainedLockRemainUnknownWithoutCleanupOrReplay() = fixture { f ->
        val first = f.installer()
        val request = first.prepare(f.artifact, "primary", f.installation)
        first.rememberRequest(request)
        f.probeFailure = LinuxInstallProbeUnknown(Any())
        assertTrue(runCatching { first.install(first.approveDownloadAndInstall(request), LinuxInstallCancellation()) }.exceptionOrNull() is LinuxInstallProbeUnknown)
        val before = f.tree()
        val restarted = f.installer { error("recovery must not fsync") }
        val recovered = restarted.recoverRequest(f.packaged(), listOf(f.artifact))!!
        assertTrue(recovered.unknown)
        assertFalse(recovered.published)
        assertTrue(File(f.target.parentFile, ".install-lock").isDirectory)
        assertEquals(before, f.tree())
        assertEquals(1, f.requests)
        assertEquals(1, f.probes)
        assertTrue(runCatching { restarted.discardRequest(recovered.prepared) }.isFailure)
        assertTrue(File(f.target.parentFile, ".install-lock").isDirectory)
    }

    fun testUnexplainedWriterLockAloneIsUnknownAndNeverDeleted() = fixture { f ->
        val first = f.installer()
        val request = first.prepare(f.artifact, "primary", f.installation)
        first.rememberRequest(request)
        val lock = File(f.target.parentFile, ".install-lock")
        assertTrue(lock.mkdir())
        val recovered = f.installer { error("read-only") }.recoverRequest(f.packaged(), listOf(f.artifact))!!
        assertTrue(recovered.unknown)
        assertFalse(recovered.published)
        assertTrue(lock.isDirectory)
        assertEquals(0, f.requests)
        assertEquals(0, f.probes)
        assertEquals(0, f.moves)
    }

    fun testMissingJournalWithOrphanTargetNeverInventsReplacementAndEmptyStoreIsReadOnly() = fixture { f ->
        val restarted = f.installer { error("recovery must not fsync") }
        assertNull(restarted.recoverRequest(f.packaged(), listOf(f.artifact)))
        assertFalse(f.managed.exists())
        assertTrue(f.target.mkdirs())
        assertTrue(runCatching { restarted.recoverRequest(f.packaged(), listOf(f.artifact)) }.isFailure)
        assertTrue(f.target.isDirectory)
        assertEquals(0, f.requests)
    }

    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
