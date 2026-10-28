package app.daiyu.android.terminal.install

import app.daiyu.android.terminal.LinuxExecutionMode
import app.daiyu.android.terminal.LinuxInstallation
import junit.framework.TestCase
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** All payloads and helpers are inert text; runner/transport/extractor are test doubles. */
class RootlessLinuxInstallerTest : TestCase() {
    private class Fixture(val root: File, distribution: String = "alpine") {
        val payload = "verified archive fixture".toByteArray()
        val mirror = RootfsMirror("primary", URI("https://example.invalid/rootfs.tar.gz"))
        val artifact = LinuxRootfsArtifact("fixture-1", distribution, "fixture", "arm64-v8a",
            RootfsArchive.TAR_GZIP, payload.size.toLong(), hash(payload), listOf(mirror))
        val privateRoot = File(root, "linux-installations")
        val proot = File(root, "proot").apply { writeText("inert proot"); setExecutable(true) }
        val loader = File(root, "loader").apply { writeText("inert loader") }
        val target = File(privateRoot, "environment/generation/rootfs")
        val installation = LinuxInstallation(distribution, "environment", "generation", target,
            proot, hash(proot.readBytes()), loader, hash(loader.readBytes()))
        var requests = 0
        var extractions = 0
        var probes = 0
        var moves = 0
        var downloadBytes = payload
        var beforeDelete: ((java.nio.file.Path) -> Unit)? = null
        var beforeDownload: (() -> Unit)? = null
        var afterExtract: (() -> Unit)? = null
        val cancellation = LinuxInstallCancellation()
        val fileOps = HostInstallLinkFileOps()

        fun installer(sync: (File) -> Unit = {},
                      move: ((File, File) -> Unit)? = null,
                      managedRoot: File = privateRoot,
                      mode: LinuxExecutionMode = LinuxExecutionMode.PROOT,
                      force: (java.nio.channels.FileChannel) -> Unit = { it.force(true) }): RootlessLinuxInstaller = RootlessLinuxInstaller(
            managedRoot,
            LinuxRootfsExtraction { _, _, stage, token ->
                extractions++
                token.check()
                File(stage, "bin").mkdir()
                File(stage, "bin/sh").writeText("inert guest shell")
                afterExtract?.invoke()
            },
            LinuxInstallProbeRunner { configuration, command, token ->
                probes++
                token.check()
                check(configuration.backend == "linux")
                check(!configuration.root)
                check(command == "id -u")
                check(configuration.linux!!.expectedUid == 0)
                check(configuration.linux!!.shellArgv.first() == proot.path)
                check(configuration.linux!!.sharedMounts.isEmpty())
                LinuxProbeResult(0, "0\n")
            },
            LinuxArtifactDownload { _, _, destination, token ->
                token.check()
                beforeDownload?.invoke()
                requests++
                destination.writeBytes(downloadBytes)
            }, sync,
            { from, to ->
                moves++
                if (move != null) move(from, to) else { Files.move(from.toPath(), to.toPath()); Unit }
            }, fileForce = force, fileOps = object : RootlessLinuxInstaller.FileOps by fileOps {
                override fun deleteIfExists(path: java.nio.file.Path): Boolean {
                    beforeDelete?.invoke(path)
                    return Files.deleteIfExists(path)
                }
            }, executionMode = mode, requireChrootTransport = {}
        )
    }

    private fun fixture(distribution: String = "alpine", block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("linux-install-test-").toFile()
        try { block(Fixture(root, distribution)) } finally { root.deleteRecursively() }
    }

    fun testChrootPreparationAndRecoveryNeverResolveOrPersistProotHelpers() = fixture { f ->
        val installer = f.installer(mode = LinuxExecutionMode.CHROOT)
        val configuration = f.installation.copy(proot = null, prootSha256 = "", loader = null, loaderSha256 = "",
            executionMode = LinuxExecutionMode.CHROOT)
        val prepared = installer.prepare(f.artifact, "primary", configuration)
        assertEquals(0, f.requests); assertEquals(0, f.probes)
        installer.rememberRequest(prepared)
        val journal = File(f.privateRoot, ".install-request")
        val before = journal.readBytes()
        assertTrue(journal.readText().contains("execution_mode=chroot\n"))
        assertFalse(journal.readText().contains("proot="))
        assertFalse(journal.readText().contains("loader="))
        val recovered = installer.recoverChrootRequest("arm64-v8a", listOf(f.artifact))!!
        assertEquals(configuration, recovered.prepared.installation)
        assertFalse(recovered.unknown)
        assertTrue(before.contentEquals(journal.readBytes()))
        assertEquals(0, f.requests); assertEquals(0, f.probes)
        journal.writeText(journal.readText().replace("execution_mode=chroot", "execution_mode=proot"))
        rejected { installer.recoverChrootRequest("arm64-v8a", listOf(f.artifact)) }
        assertEquals(0, f.requests); assertEquals(0, f.probes)
    }
    fun testChrootPreparationRejectsMixedModeHelpersAndOutsideTarget() = fixture { f ->
        val installer = f.installer(mode = LinuxExecutionMode.CHROOT)
        val configuration = f.installation.copy(proot = null, prootSha256 = "", loader = null, loaderSha256 = "",
            executionMode = LinuxExecutionMode.CHROOT)
        for (bad in listOf(f.installation, configuration.copy(proot = f.proot),
            configuration.copy(loaderSha256 = "b".repeat(64)), configuration.copy(rootfs = File(f.root, "outside")),
            configuration.copy(sharedMounts = mapOf(f.root.path to "/host")))) {
            rejected { installer.prepare(f.artifact, "primary", bad) }
        }
        assertFalse(f.privateRoot.exists()); assertEquals(0, f.requests)
    }

    fun testCatalogPreparationCoversBothDistributionsAbisAndExecutionModes() = fixture { f ->
        assertEquals(4, LinuxRootfsCatalog.artifacts.size)
        var preparedCount = 0
        for (artifact in LinuxRootfsCatalog.artifacts) for (mode in LinuxExecutionMode.entries) {
            val configuration = if (mode == LinuxExecutionMode.PROOT) {
                f.installation.copy(distribution = artifact.distribution)
            } else {
                f.installation.copy(distribution = artifact.distribution, proot = null, prootSha256 = "",
                    loader = null, loaderSha256 = "", executionMode = mode)
            }
            val prepared = f.installer(mode = mode).prepare(artifact, "primary", configuration)
            assertSame(artifact, prepared.artifact)
            assertEquals(artifact.mirrors.first(), prepared.mirror)
            assertEquals(mode, prepared.installation.executionMode)
            assertEquals(f.target, prepared.targetRootfs)
            preparedCount++
        }
        assertEquals(8, preparedCount)
        assertEquals(0, f.requests)
        assertEquals(0, f.extractions)
        assertEquals(0, f.probes)
        assertFalse(f.privateRoot.exists())
    }

    fun testPreparationAndApprovalHaveZeroRequestsAndNoFilesystemEffects() = fixture { f ->
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        installer.approveDownloadAndInstall(prepared)
        assertEquals(0, f.requests)
        assertEquals(0, f.extractions)
        assertEquals(0, f.probes)
        assertFalse(f.privateRoot.exists())
    }

    fun testSuccessfulFilesOnlyInstallIsOneShotAndHasReadableMarker() = fixture { f ->
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        val approval = installer.approveDownloadAndInstall(prepared)
        val receipt = installer.install(approval, f.cancellation)
        assertTrue(receipt.directorySyncConfirmed)
        assertEquals(f.installation, installer.inspectPublished(prepared))
        assertEquals(1, f.requests)
        assertEquals(1, f.moves)
        assertEquals(1, f.probes)
        val marker = File(f.target, ".daiyu-rootfs-install").readText()
        assertTrue(marker.contains("generation=generation\n"))
        assertTrue(marker.contains("mirror=primary\n"))
        rejected { installer.install(approval, f.cancellation) }
        assertEquals(1, f.requests)
        assertFalse(File(f.root, "selected-linux.json").exists())
        assertFalse(File(f.root, "trusted-linux-catalog.json").exists())
        assertEquals(listOf("rootfs"), f.target.parentFile.list()!!.toList())
    }

    fun testBothDistributionsRemoveArchiveStagingAndWriterLeaseAfterSuccessOrCancellation() {
        for (distribution in listOf("alpine", "debian")) {
            fixture(distribution) { f ->
                val installer = f.installer()
                val prepared = installer.prepare(f.artifact, "primary", f.installation)
                val receipt = installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation)
                assertTrue(receipt.directorySyncConfirmed)
                assertEquals(f.installation, installer.inspectPublished(prepared))
                assertEquals(listOf("rootfs"), f.target.parentFile.list()!!.toList())
            }
            fixture(distribution) { f ->
                val installer = f.installer()
                val prepared = installer.prepare(f.artifact, "primary", f.installation)
                f.afterExtract = { f.cancellation.cancel() }
                cancelled { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
                assertFalse(f.target.exists())
                assertTrue(f.target.parentFile.list()!!.isEmpty())
                assertEquals(0, f.probes)
            }
        }
    }

    fun testFailedRequestForceRemovesOnlyOwnedTemporaryFileAndAllowsRetry() = fixture { f ->
        var fail = true
        val installer = f.installer(force = {
            if (fail) { fail = false; throw IOException("request force failed") }
            it.force(true)
        })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        rejected { installer.rememberRequest(prepared) }
        assertFalse(File(f.privateRoot, ".install-request.tmp").exists())
        assertFalse(File(f.privateRoot, ".install-request").exists())
        installer.rememberRequest(prepared)
        assertTrue(File(f.privateRoot, ".install-request").isFile)
        assertFalse(File(f.privateRoot, ".install-request.tmp").exists())
        assertEquals(0, f.requests)
    }

    fun testPreexistingRequestTemporaryFileIsNeverDeletedOrReplaced() = fixture { f ->
        assertTrue(f.privateRoot.mkdirs())
        val temporary = File(f.privateRoot, ".install-request.tmp").apply { writeText("other owner") }
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        rejected { installer.rememberRequest(prepared) }
        assertEquals("other owner", temporary.readText())
        assertFalse(File(f.privateRoot, ".install-request").exists())
    }

    fun testRequestCleanupFailureRetainsOriginalErrorAndOwnedTemporaryFile() = fixture { f ->
        val original = IOException("request force failed")
        f.beforeDelete = { if (it.fileName.toString() == ".install-request.tmp") throw IOException("request cleanup failed") }
        val installer = f.installer(force = { throw original })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        try { installer.rememberRequest(prepared); fail("expected force failure") }
        catch (failure: IOException) {
            assertSame(original, failure)
            assertEquals("request cleanup failed", failure.suppressed.single().message)
        }
        assertTrue(File(f.privateRoot, ".install-request.tmp").isFile)
        assertFalse(File(f.privateRoot, ".install-request").exists())
    }

    fun testPublishedRequestSurvivesDirectorySyncFailureWithoutTemporaryFile() = fixture { f ->
        var fail = true
        val journal = File(f.privateRoot, ".install-request")
        val installer = f.installer(sync = {
            if (it == f.privateRoot && journal.exists() && fail) {
                fail = false
                throw IOException("request directory sync failed")
            }
        })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        rejected { installer.rememberRequest(prepared) }
        val saved = journal.readBytes()
        assertFalse(File(f.privateRoot, ".install-request.tmp").exists())
        installer.rememberRequest(prepared)
        assertTrue(saved.contentEquals(journal.readBytes()))
    }

    fun testArchiveCleanupFailurePreservesReceiptAndLeaseUntilExplicitRetry() = fixture { f ->
        f.beforeDelete = { if (it.fileName.toString().endsWith(".archive")) throw IOException("archive cleanup") }
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        val failure = cleanupPending { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertTrue(failure.receipt!!.directorySyncConfirmed)
        assertTrue(File(f.target.parentFile, ".install-lock").exists())
        assertTrue(f.target.exists())
        rejected { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertEquals(1, f.requests)
        f.beforeDelete = null
        assertEquals(failure.receipt, installer.retryCleanup(prepared))
        assertEquals(listOf("rootfs"), f.target.parentFile.list()!!.toList())
        assertEquals(1, f.probes)
    }

    fun testCleanupRetryRetainsFirstCleanupFailure() = fixture { f ->
        val first = IOException("first cleanup")
        val next = IOException("retry cleanup")
        f.beforeDelete = { if (it.fileName.toString().endsWith(".archive")) throw first }
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        val failure = cleanupPending { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertSame(first, failure.cause)
        f.beforeDelete = { if (it.fileName.toString().endsWith(".archive")) throw next }
        val retry = cleanupPending { installer.retryCleanup(prepared) }
        assertSame(first, retry.cause)
        assertSame(next, retry.suppressed.single())
        f.beforeDelete = null
        assertNotNull(installer.retryCleanup(prepared))
    }

    fun testFailedInstallCleanupKeepsOriginalFailureAndDoesNotReleaseLease() = fixture { f ->
        f.downloadBytes = ByteArray(f.payload.size)
        f.beforeDelete = { if (it.fileName.toString().startsWith(".staging-")) throw IOException("staging cleanup") }
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        val failure = cleanupPending { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertNull(failure.receipt)
        assertNotNull(failure.originalFailure)
        assertSame(failure.originalFailure, failure.cause)
        assertEquals("staging cleanup", failure.suppressed.single().message)
        assertTrue(File(f.target.parentFile, ".install-lock").exists())
        assertFalse(f.target.exists())
        f.beforeDelete = null
        assertNull(installer.retryCleanup(prepared))
        assertTrue(f.target.parentFile.list()!!.isEmpty())
        assertEquals(0, f.probes)
    }

    fun testLockReleaseSyncRetryNeverDeletesReplacementOwnerLock() = fixture { f ->
        var fail = true
        val lock = File(f.target.parentFile, ".install-lock")
        val installer = f.installer(sync = { directory ->
            if (directory == f.target.parentFile && f.target.exists() && !lock.exists() && fail) {
                fail = false
                assertTrue(lock.mkdir())
                throw IOException("released but not synced")
            }
        })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        val failure = cleanupPending { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertTrue(failure.receipt!!.directorySyncConfirmed)
        assertNotNull(installer.retryCleanup(prepared))
        assertTrue("replacement owner's lease must remain", lock.exists())
        assertEquals(1, f.requests)
    }

    fun testDurabilityConfirmationReportsLeaseCleanupFailureWithoutReplayingProbe() = fixture { f ->
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation)
        f.beforeDelete = { if (it.fileName.toString() == ".install-lock") throw IOException("lock cleanup") }
        val failure = cleanupPending { installer.confirmPublishedDurability(prepared) }
        assertTrue(failure.receipt!!.directorySyncConfirmed)
        assertTrue(File(f.target.parentFile, ".install-lock").exists())
        f.beforeDelete = null
        assertNotNull(installer.retryCleanup(prepared))
        assertEquals(1, f.requests)
        assertEquals(1, f.probes)
    }

    private fun cleanupPending(block: () -> Unit): LinuxInstallCleanupPending {
        try { block() } catch (failure: LinuxInstallCleanupPending) { return failure }
        error("expected cleanup pending")
    }

    fun testBadHashNeverExtractsProbesOrPublishes() = fixture { f ->
        f.downloadBytes = ByteArray(f.payload.size) { 'x'.code.toByte() }
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        rejected { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertEquals(1, f.requests)
        assertEquals(0, f.extractions)
        assertEquals(0, f.probes)
        assertEquals(0, f.moves)
        assertFalse(f.target.exists())
    }

    fun testCancelledBeforeStartMakesZeroRequests() = fixture { f ->
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        f.cancellation.cancel()
        cancelled { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertEquals(0, f.requests)
        assertFalse(f.privateRoot.exists())
    }

    fun testCancellationAtLastSyncNeverRenames() = fixture { f ->
        val installer = f.installer(sync = { directory ->
            if (directory.name.startsWith(".staging-")) f.cancellation.cancel()
        })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        cancelled { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertEquals(1, f.requests)
        assertEquals(1, f.probes)
        assertEquals(0, f.moves)
        assertFalse(f.target.exists())
    }

    fun testCancellationDuringExtractionDoesNotProbeOrPublish() = fixture { f ->
        f.afterExtract = { f.cancellation.cancel() }
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        cancelled { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertEquals(0, f.probes)
        assertEquals(0, f.moves)
        assertFalse(f.target.exists())
    }

    fun testExistingEmptyOrPopulatedRootfsIsNeverReplaced() = fixture { f ->
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        assertTrue(f.target.mkdirs())
        rejected { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertTrue(f.target.isDirectory)
        assertEquals(0, f.target.list()!!.size)
        val sentinel = File(f.target, "keep").apply { writeText("untouched") }
        rejected { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertEquals("untouched", sentinel.readText())
        assertEquals(0, f.requests)
        assertEquals(0, f.moves)
    }

    fun testEveryManagedDirectoryNameSyncsItsParentBeforeDownloadEvenWhenExisting() {
        for (existing in listOf(false, true)) fixture { f ->
            if (existing) assertTrue(f.target.parentFile.mkdirs())
            val synced = mutableListOf<File>()
            val expected = listOf(f.root, f.privateRoot, File(f.privateRoot, "environment"))
            val installer = f.installer(sync = { synced.add(it) })
            f.beforeDownload = { assertEquals(expected, synced) }
            val prepared = installer.prepare(f.artifact, "primary", f.installation)
            val receipt = installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation)
            assertTrue(receipt.directorySyncConfirmed)
            assertEquals(expected, synced.take(3))
            assertEquals(1, f.requests)
        }
    }

    fun testDirectoryConfirmationDoesNotChangeExternalRootPermissionsOrSyncItsAncestors() = fixture { f ->
        if ((Files.getFileAttributeView(f.root.toPath(), java.nio.file.attribute.PosixFileAttributeView::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS) != null))
            Files.setPosixFilePermissions(f.root.toPath(), PosixFilePermissions.fromString("rwxr-x---"))
        val permissions = HostInstallFileOps.permissions(f.root)
        val synced = mutableListOf<File>()
        val installer = f.installer(sync = { synced.add(it) })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        val receipt = installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation)
        assertTrue(receipt.directorySyncConfirmed)
        assertEquals(permissions, HostInstallFileOps.permissions(f.root))
        assertTrue(synced.contains(f.root))
        assertTrue(synced.all { it.toPath().startsWith(f.root.toPath()) })
    }

    fun testMissingExternalRootIsNotCreated() = fixture { f ->
        val externalRoot = File(f.root, "missing-external-root")
        val managedRoot = File(externalRoot, "linux-installations")
        val installation = f.installation.copy(rootfs = File(managedRoot, "environment/generation/rootfs"))
        val synced = mutableListOf<File>()
        val installer = f.installer(sync = { synced.add(it) }, managedRoot = managedRoot)
        val prepared = installer.prepare(f.artifact, "primary", installation)
        rejected { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertFalse(externalRoot.exists())
        assertTrue(synced.isEmpty())
        assertEquals(0, f.requests)
        assertEquals(0, f.extractions)
        assertEquals(0, f.probes)
        assertEquals(0, f.moves)
    }

    fun testFailedAncestorSyncIsRetriedWithNewApproval() = assertFailedAncestorSyncIsRetried()

    fun testFailedAncestorSyncIsRetriedForDifferentGeneration() =
        assertFailedAncestorSyncIsRetried(newGeneration = true)

    fun testFailedAncestorSyncIsRetriedByNewInstaller() =
        assertFailedAncestorSyncIsRetried(newInstaller = true)

    fun testFailedAncestorSyncIsRetriedByNewInstallerForDifferentGeneration() =
        assertFailedAncestorSyncIsRetried(newGeneration = true, newInstaller = true)

    private fun assertFailedAncestorSyncIsRetried(newGeneration: Boolean = false,
                                                 newInstaller: Boolean = false) {
        for (level in 0..2) fixture { f ->
            val expected = listOf(f.root, f.privateRoot, File(f.privateRoot, "environment"))
            val synced = mutableListOf<File>()
            var failSync = true
            val sync: (File) -> Unit = { directory ->
                synced.add(directory)
                if (failSync && directory == expected[level]) {
                    throw IOException("injected persistent ancestor sync failure")
                }
            }
            val original = f.installer(sync = sync)
            val originalPrepared = original.prepare(f.artifact, "primary", f.installation)
            rejected { original.install(original.approveDownloadAndInstall(originalPrepared), f.cancellation) }
            assertEquals(expected.take(level + 1), synced)
            // The failed parent fsync leaves precisely this managed name on disk.
            assertTrue(listOf(f.privateRoot, File(f.privateRoot, "environment"), f.target.parentFile)[level].isDirectory)

            val retryInstaller = if (newInstaller) f.installer(sync = sync) else original
            val retryInstallation = if (newGeneration) f.installation.copy(
                generation = "generation-retry",
                rootfs = File(f.privateRoot, "environment/generation-retry/rootfs")
            ) else f.installation
            val retryPrepared = if (newInstaller || newGeneration) {
                retryInstaller.prepare(f.artifact, "primary", retryInstallation)
            } else originalPrepared
            synced.clear()
            rejected {
                retryInstaller.install(retryInstaller.approveDownloadAndInstall(retryPrepared), f.cancellation)
            }
            assertEquals(expected.take(level + 1), synced)
            assertEquals(0, f.requests)
            assertEquals(0, f.extractions)
            assertEquals(0, f.probes)
            assertEquals(0, f.moves)
            assertFalse(f.target.exists())
            assertFalse(retryInstallation.rootfs.exists())
            assertFalse(File(retryInstallation.rootfs.parentFile, ".install-lock").exists())

            failSync = false
            synced.clear()
            // No transport may run until ALL managed names have been reconfirmed, including
            // existing ancestors shared with the failed generation/installer instance.
            f.beforeDownload = { assertEquals(expected, synced) }
            val receipt = retryInstaller.install(
                retryInstaller.approveDownloadAndInstall(retryPrepared), f.cancellation)
            assertTrue(receipt.directorySyncConfirmed)
            assertEquals(retryInstallation, retryInstaller.inspectPublished(retryPrepared))
            assertEquals(expected, synced.take(3))
            assertEquals(1, f.requests)
            assertEquals(1, f.extractions)
            assertEquals(1, f.probes)
            assertEquals(1, f.moves)
        }
    }

    fun testFailureSyncingAnyNewAncestorAbortsBeforeDownloadAndPublication() {
        for (level in 0..2) fixture { f ->
            val failedParent = listOf(f.root, f.privateRoot, File(f.privateRoot, "environment"))[level]
            val installer = f.installer(sync = { directory ->
                if (directory == failedParent) throw IOException("injected ancestor sync failure")
            })
            val prepared = installer.prepare(f.artifact, "primary", f.installation)
            rejected { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
            assertEquals(0, f.requests)
            assertEquals(0, f.probes)
            assertEquals(0, f.moves)
            assertFalse(f.target.exists())
            assertNull(installer.inspectPublished(prepared))
        }
    }

    fun testRenameThenDirectorySyncFailureReconcilesVisibleFiles() = fixture { f ->
        var failPublicationSync = true
        val installer = f.installer(sync = { dir ->
            if (dir == f.target.parentFile && f.target.exists() && failPublicationSync) {
                failPublicationSync = false
                throw IOException("injected directory sync")
            }
        })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        val receipt = installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation)
        assertFalse(receipt.directorySyncConfirmed)
        assertEquals(f.installation, installer.inspectPublished(prepared))
        assertEquals(1, f.moves)
        assertTrue(f.target.isDirectory)
    }

    fun testRenameThenCancellationStillReportsDiskResult() = fixture { f ->
        val installer = f.installer(move = { from, to ->
            Files.move(from.toPath(), to.toPath())
            f.cancellation.cancel()
            throw CancellationException("after rename")
        })
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        val receipt = installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation)
        assertFalse(receipt.directorySyncConfirmed)
        assertEquals(f.installation, installer.inspectPublished(prepared))
        assertTrue(f.target.exists())
    }

    fun testMarkerMismatchAndShellEscapeNeverCountAsInstalled() = fixture { f ->
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation)
        val marker = File(f.target, ".daiyu-rootfs-install")
        val saved = marker.readBytes()
        marker.appendText("tampered")
        rejected { installer.inspectPublished(prepared) }
        marker.writeBytes(saved)
        File(f.target, "bin/sh").delete()
        f.fileOps.createSymbolicLink(File(f.target, "bin/sh").toPath(), f.proot.toPath())
        rejected { installer.inspectPublished(prepared) }
    }

    fun testApprovalCannotBeTransferredToAnotherInstallerOrTarget() = fixture { f ->
        val first = f.installer()
        val second = f.installer()
        val prepared = first.prepare(f.artifact, "primary", f.installation)
        rejected { second.approveDownloadAndInstall(prepared) }
        val approved = first.approveDownloadAndInstall(prepared)
        rejected { second.install(approved, f.cancellation) }
        assertEquals(0, f.requests)
        rejected { first.prepare(f.artifact, "primary", f.installation.copy(rootfs = File(f.root, "elsewhere"))) }
        rejected { first.prepare(f.artifact, "unknown", f.installation) }
    }

    fun testSymlinkParentAndStaleLockFailClosed() = fixture { f ->
        val installer = f.installer()
        val prepared = installer.prepare(f.artifact, "primary", f.installation)
        f.target.parentFile.mkdirs()
        File(f.target.parentFile, ".install-lock").mkdir()
        rejected { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation) }
        assertEquals(0, f.requests)
        File(f.target.parentFile, ".install-lock").delete()
        f.privateRoot.deleteRecursively()
        f.fileOps.createSymbolicLink(f.privateRoot.toPath(), f.root.toPath())
        try { installer.install(installer.approveDownloadAndInstall(prepared), f.cancellation); fail("must reject parent link") }
        catch (failure: IllegalStateException) { assertEquals("linux_install_symlink_path", failure.message) }
        assertEquals(0, f.requests)
        // Avoid a recursive test-cleanup traversal of the intentionally hostile symlink.
        f.fileOps.delete(f.privateRoot.toPath())
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalStateException) {
        } catch (_: IllegalArgumentException) { } catch (_: IOException) { }
    }

    private fun cancelled(block: () -> Unit) {
        try { block(); fail("expected cancellation") } catch (_: CancellationException) { }
    }

    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
