package app.daiyu.android.terminal.install

import app.daiyu.android.terminal.hostLinuxCatalog
import app.daiyu.android.terminal.hostDirectorySync
import app.daiyu.android.terminal.hostLinuxStore
import app.daiyu.android.terminal.FrozenLinuxApproval
import app.daiyu.android.terminal.LinuxGenerationRef
import app.daiyu.android.terminal.LinuxExecutionMode
import app.daiyu.android.terminal.LinuxInstallation
import app.daiyu.android.terminal.LinuxSettingsAdmission
import app.daiyu.android.terminal.LinuxSettingsCatalogAccess
import app.daiyu.android.terminal.LinuxSettingsController
import app.daiyu.android.terminal.TerminalConfiguration
import java.io.File
import java.io.IOException
import junit.framework.TestCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Composition-only tests: no Android binary, network or real PRoot execution is claimed. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinuxProductionCompositionTest : TestCase() {
    private class Transactions : LinuxInstallTransactions {
        val owner = Any()
        var completions = 0
        override fun completeRegistration(prepared: PreparedLinuxInstall, approval: FrozenLinuxApproval) { completions++ }
        override fun remember(prepared: PreparedLinuxInstall) {}
        override fun discard(prepared: PreparedLinuxInstall) {}
        var installs = 0
        var cleanupRetries = 0
        var cleanupReceipt: LinuxInstallReceipt? = null
        override fun retryCleanup(prepared: PreparedLinuxInstall): LinuxInstallReceipt? {
            cleanupRetries++
            return cleanupReceipt
        }
        var confirmations = 0
        var confirmationFailure: Exception? = null
        var durable = false
        var recovered: RecoveredLinuxInstall? = null
        var recoveryFailure: Exception? = null
        override fun recover(helpers: PackagedProotHelpers): RecoveredLinuxInstall? {
            recoveryFailure?.let { throw it }
            return recovered
        }
        var chrootRecoveries = 0
        override fun recoverChroot(abi: String): RecoveredLinuxInstall? {
            check(abi == "arm64-v8a")
            chrootRecoveries++
            return recovered
        }
        var beforeRememberRegistration: (() -> Unit)? = null
        override fun rememberRegistration(prepared: PreparedLinuxInstall, approval: FrozenLinuxApproval) {
            beforeRememberRegistration?.invoke()
        }
        var beforeInstall: ((LinuxInstallCancellation) -> Unit)? = null
        override fun prepare(artifact: LinuxRootfsArtifact, mirrorId: String, installation: LinuxInstallation) =
            PreparedLinuxInstall(owner, artifact, artifact.mirrors.single { it.id == mirrorId }, installation)
        override fun install(prepared: PreparedLinuxInstall, cancellation: LinuxInstallCancellation): LinuxInstallReceipt {
            installs++
            beforeInstall?.invoke(cancellation)
            cancellation.check()
            return LinuxInstallReceipt(prepared.installation, durable)
        }
        override fun confirmPublishedDurability(prepared: PreparedLinuxInstall): LinuxInstallReceipt {
            confirmations++
            confirmationFailure?.let { throw it }
            return LinuxInstallReceipt(prepared.installation, true)
        }
    }
    private class Catalog : LinuxSettingsCatalogAccess {
        override suspend fun registrationRecorded(approval: FrozenLinuxApproval) = false
        override suspend fun approveInstallation(approval: FrozenLinuxApproval, admit: () -> Boolean): List<LinuxInstallation> {
            check(admit()) { "linux_catalog_admission_revoked" }
            return approve(approval)
        }
        var freezes = 0
        val approvals = mutableListOf<FrozenLinuxApproval>()
        var fail = true
        var beforeApprove: (suspend () -> Unit)? = null
        override fun freeze(configuration: LinuxInstallation): FrozenLinuxApproval {
            freezes++
            return FrozenLinuxApproval(configuration.copy(generation = "catalog-generation"))
        }
        override suspend fun load() = emptyList<LinuxInstallation>()
        override suspend fun approve(approval: FrozenLinuxApproval): List<LinuxInstallation> {
            approvals.add(approval)
            beforeApprove?.invoke()
            if (fail) throw IOException("after-rename directory sync failed")
            return listOf(approval.installation)
        }
        override suspend fun remove(reference: LinuxGenerationRef) = emptyList<LinuxInstallation>()
    }
    private fun helpers() = PackagedProotHelpers("arm64-v8a", File("/apk/libproot_exec.so"), "a".repeat(64),
        File("/apk/libproot_loader.so"), "b".repeat(64))

    fun testChrootCoordinatorNeverCallsProotProviderAndBothRecoveredRequestsCanBeDiscarded() = runTest {
        val proot = Transactions()
        val chroot = Transactions()
        val catalog = Catalog()
        val first = LinuxInstallCoordinator(proot, ::helpers, File("/private/proot"), catalog, this)
        val second = LinuxInstallCoordinator(chroot, { error("must_not_resolve_proot_helpers") },
            File("/private/chroot"), catalog, this, executionMode = LinuxExecutionMode.CHROOT, chrootAbi = { "arm64-v8a" })
        runCurrent()
        assertEquals(1, chroot.chrootRecoveries)
        // Simulate two independently restored journals; cleanup must not deadlock across modes.
        assertTrue(first.prepare("alpine", "primary")); assertTrue(second.prepare("alpine", "primary"))
        runCurrent()
        val installed = second.state.value.prepared!!.installation
        assertEquals(LinuxExecutionMode.CHROOT, installed.executionMode)
        assertNull(installed.proot); assertNull(installed.loader)
        assertTrue(installed.rootfs.toPath().startsWith(File("/private/chroot").toPath()))
        val settingsWorker = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), settingsWorker,
            installation = first, chrootInstallation = second, allowChroot = { true })
        val page = controller.enterPage(); runCurrent()
        assertFalse(page.prepareInstall("debian", "primary", LinuxExecutionMode.CHROOT))
        assertTrue(page.discardInstall(LinuxExecutionMode.CHROOT)); runCurrent()
        assertNull(second.state.value.prepared); assertNotNull(first.state.value.prepared)
        assertTrue(page.discardInstall()); runCurrent()
        assertFalse(controller.installationBlocksManual)
        page.close()
        assertFalse(page.prepareInstall("alpine", "primary", LinuxExecutionMode.CHROOT))
        controller.close()
    }
    fun testRootLeaderExitWaitsForDrainWithoutMaskingRealProtocolFailure() {
        val value = org.json.JSONObject().put("ok", true).put("state", "unknown")
            .put("leader_exited", true).put("wait_status", 0).put("error", org.json.JSONObject.NULL)
        assertFalse(installProbeUnknown(value, true))
        assertTrue(installProbeUnknown(value, false))
        value.put("error", "pty_root_scope_drain_unknown")
        assertTrue(installProbeUnknown(value, true))
        value.put("error", org.json.JSONObject.NULL).put("ok", false)
        assertTrue(installProbeUnknown(value, true))
    }

    fun testTwoConfirmationsDurabilityAndOriginalFrozenRetryAcrossPageRecreation() = runTest {
        val transactions = Transactions()
        val catalog = Catalog()
        val install = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog, this)
        val admission = LinuxSettingsAdmission(emptySet())
        val controller = LinuxSettingsController(catalog, admission, this, installation = install)
        val first = controller.enterPage()
        runCurrent()
        assertTrue(first.prepareInstall("alpine", "primary"))
        runCurrent()
        val prepared = install.state.value.prepared!!
        assertEquals(0, transactions.installs)
        assertEquals(0, catalog.freezes)
        assertFalse(first.prepareInstall("debian", "primary"))
        assertTrue(first.install(prepared))
        runCurrent()
        assertFalse(install.state.value.receipt!!.directorySyncConfirmed)
        assertNull(install.state.value.registration)
        assertEquals(0, catalog.approvals.size)
        assertTrue(first.confirmPublishedDurability(prepared))
        runCurrent()
        val frozen = install.state.value.registration!!
        assertEquals(1, catalog.freezes)
        assertEquals(0, catalog.approvals.size)
        assertTrue(first.approveInstalled(frozen))
        runCurrent()
        assertTrue(install.state.value.registrationPending)
        first.close()
        val second = controller.enterPage()
        runCurrent()
        assertFalse(first.approveInstalled(frozen))
        assertSame(frozen, install.state.value.registration)
        catalog.fail = false
        assertTrue(second.approveInstalled(frozen))
        runCurrent()
        assertTrue(install.state.value.registered)
        assertSame(catalog.approvals[0], catalog.approvals[1])
        assertEquals(1, transactions.completions)
        assertEquals(1, catalog.freezes)
        assertTrue(admission.current().isEmpty()) // no new live terminal admission/hot swap
        assertEquals(1, transactions.installs)
        assertEquals(1, transactions.confirmations)
    }

    fun testPageRevocationCancelsActualInstallTokenBeforeQueuedIo() = runTest {
        val transactions = Transactions()
        val catalog = Catalog()
        val install = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog, this)
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), this, installation = install)
        val page = controller.enterPage()
        runCurrent()
        page.prepareInstall("alpine", "primary")
        runCurrent()
        var cancelled = false
        transactions.beforeInstall = { token ->
            cancelled = runCatching { token.check() }.isFailure
        }
        assertTrue(page.install(install.state.value.prepared!!))
        page.close()
        runCurrent()
        assertTrue(cancelled)
        assertNull(install.state.value.receipt)
        assertEquals(0, catalog.freezes)
    }

    fun testQueuedRegistrationIsFencedOnPageLeaveAndKeepsOriginalForExplicitRetry() = runTest {
        val transactions = Transactions().apply { durable = true }
        val catalog = Catalog().apply { fail = false }
        val install = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog, this)
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), this, installation = install)
        val first = controller.enterPage()
        runCurrent()
        first.prepareInstall("alpine", "primary")
        runCurrent()
        first.install(install.state.value.prepared!!)
        runCurrent()
        val frozen = install.state.value.registration!!
        assertTrue(first.approveInstalled(frozen))
        first.close() // before StandardTestDispatcher can admit catalog IO
        val second = controller.enterPage()
        runCurrent()
        assertEquals(0, catalog.approvals.size)
        assertFalse(install.state.value.busy)
        assertTrue(install.state.value.registrationPending)
        assertSame(frozen, install.state.value.registration)
        assertFalse(first.approveInstalled(frozen))
        assertTrue(second.approveInstalled(frozen))
        runCurrent()
        assertTrue(install.state.value.registered)
        assertSame(frozen, catalog.approvals.single())
        assertEquals(1, catalog.freezes)
    }

    fun testLeavingDuringRequestSnapshotStillFencesFirstCatalogIo() = runTest {
        val transactions = Transactions().apply { durable = true }
        val catalog = Catalog().apply { fail = false }
        val install = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog, this)
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), this, installation = install)
        val page = controller.enterPage()
        runCurrent()
        page.prepareInstall("alpine", "primary")
        runCurrent()
        page.install(install.state.value.prepared!!)
        runCurrent()
        val frozen = install.state.value.registration!!
        transactions.beforeRememberRegistration = { page.close() }
        page.approveInstalled(frozen)
        runCurrent()
        assertEquals(0, catalog.approvals.size)
        assertTrue(install.state.value.registrationPending)
        assertSame(frozen, install.state.value.registration)
        assertFalse(install.state.value.busy)
    }

    fun testControllerCloseFencesQueuedRegistrationWithoutDroppingIntent() = runTest {
        val transactions = Transactions().apply { durable = true }
        val catalog = Catalog().apply { fail = false }
        val install = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog, this)
        val settingsWorker = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), settingsWorker, installation = install)
        val page = controller.enterPage()
        runCurrent()
        page.prepareInstall("alpine", "primary")
        runCurrent()
        page.install(install.state.value.prepared!!)
        runCurrent()
        val frozen = install.state.value.registration!!
        assertTrue(page.approveInstalled(frozen))
        controller.close() // Cancels settings worker, not the installation receipt owner.
        runCurrent()
        assertEquals(0, catalog.approvals.size)
        assertSame(frozen, install.state.value.registration)
        assertFalse(install.state.value.busy)
        assertFalse(install.approveRegistration(frozen))
    }

    fun testStartedRegistrationReceiptSurvivesPageRevocationAndCoordinatorClose() = runTest {
        val transactions = Transactions().apply { durable = true }
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val catalog = Catalog().apply {
            fail = false
            beforeApprove = { entered.complete(Unit); release.await() }
        }
        val install = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog, this)
        val admission = LinuxSettingsAdmission(emptySet())
        val settingsWorker = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val controller = LinuxSettingsController(catalog, admission, settingsWorker, installation = install)
        val page = controller.enterPage()
        runCurrent()
        page.prepareInstall("alpine", "primary")
        runCurrent()
        page.install(install.state.value.prepared!!)
        runCurrent()
        val frozen = install.state.value.registration!!
        page.approveInstalled(frozen)
        runCurrent()
        assertTrue(entered.isCompleted)
        page.close()
        controller.close()
        release.complete(Unit)
        runCurrent()
        assertTrue(install.state.value.registered)
        assertFalse(install.state.value.registrationPending)
        assertSame(frozen, catalog.approvals.single())
        assertTrue(admission.current().isEmpty())
    }

    fun testProductionCatalogGateCancellationRejectsBeforeFirstIoAndRetainsOriginal() = runTest {
        val root = java.nio.file.Files.createTempDirectory("install-catalog-gate").toFile()
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val queued = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        try {
            val guest = File(root, "guest").apply { mkdirs() }
            File(guest, "bin").mkdirs()
            File(guest, "bin/sh").writeText("inert shell")
            val proot = File(root, "proot").apply { writeText("inert proot"); setExecutable(true) }
            val loader = File(root, "loader").apply { writeText("inert loader") }
            fun hash(file: File) = java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val configuration = LinuxInstallation("alpine", "env", "original", guest,
                proot, hash(proot), loader, hash(loader))
            val directory = File(root, "catalog")
            val catalog = app.daiyu.android.terminal.hostLinuxCatalog(directory, { dir ->
                if (dir == directory && File(directory, "trusted-linux-catalog.json").isFile && holding.count > 0) {
                    holding.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                app.daiyu.android.terminal.hostDirectorySync(dir)
            }, { if (calls.incrementAndGet() == 2) queued.countDown() })
            val adapter = app.daiyu.android.terminal.LinuxSettingsCatalogAdapter(catalog)
            val transactions = Transactions()
            val prepared = transactions.prepare(LinuxRootfsCatalog.find("alpine", "arm64-v8a")!!,
                "primary", configuration)
            val frozen = catalog.freezeForApproval(configuration)
            transactions.recovered = RecoveredLinuxInstall(prepared, true, false, frozen)
            val install = LinuxInstallCoordinator(transactions, ::helpers, root, adapter, this)
            suspend fun awaitIdle() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5000) { install.state.first { !it.busy } }
            }
            awaitIdle()
            assertTrue(install.confirmPublishedDurability(prepared))
            runCurrent()
            val blockerApproval = catalog.freezeForApproval(configuration.copy(environmentId = "blocker"))
            val blocker = launch(kotlinx.coroutines.Dispatchers.IO) { catalog.approve(blockerApproval) }
            assertTrue(holding.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(install.approveRegistration(frozen))
            runCurrent()
            assertTrue(queued.await(5, java.util.concurrent.TimeUnit.SECONDS))
            install.cancel() // after withContext(IO), while the actual production gate is held
            release.countDown()
            blocker.join()
            awaitIdle()
            assertEquals(listOf(blockerApproval.installation), catalog.load())
            assertTrue(install.state.value.registrationPending)
            assertSame(frozen, install.state.value.registration)
            assertFalse(install.state.value.registered)
            assertTrue(install.approveRegistration(frozen))
            awaitIdle()
            assertTrue(install.state.value.registered)
            assertEquals(2, catalog.load().size)
        } finally { release.countDown(); root.deleteRecursively() }
    }

    fun testUnavailableAndFixedCatalogRejectArbitraryUrlBeforeDownload() = runTest {
        val transactions = Transactions()
        val catalog = Catalog()
        val install = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog, this,
            unavailableReason = "linux_helpers_unavailable_api_below_34")
        assertFalse(install.prepare("alpine", "primary"))
        assertNotNull(install.state.value.unavailableReason)
        val available = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog, this)
        runCurrent() // read-only startup recovery must finish before a new request
        assertTrue(available.prepare("https://attacker.invalid/rootfs", "primary"))
        runCurrent()
        assertNull(available.state.value.prepared)
        assertEquals(0, transactions.installs)
    }

    fun testRestartMetadataDoesNotAuthorizeReplayAndRetainsSavedCatalogIdentity() = runTest {
        val transactions = Transactions()
        val configuration = app.daiyu.android.terminal.LinuxInstallation("alpine", "alpine", "original-generation",
            File("/private/linux/alpine/original-generation/rootfs"), helpers().proot, helpers().prootSha256,
            helpers().loader, helpers().loaderSha256)
        val prepared = transactions.prepare(LinuxRootfsCatalog.find("alpine", "arm64-v8a")!!, "primary", configuration)
        val originalApproval = FrozenLinuxApproval(configuration.copy(generation = "original-registration"))
        transactions.recovered = RecoveredLinuxInstall(prepared, published = true, unknown = false, registration = originalApproval)
        val catalog = Catalog().apply { fail = false }
        val install = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog, this)
        assertFalse(install.prepare("debian", "primary")) // startup read is not optional
        runCurrent()
        assertSame(prepared, install.state.value.prepared)
        assertFalse(install.state.value.receipt!!.directorySyncConfirmed)
        assertEquals(0, transactions.installs)
        assertEquals(0, transactions.confirmations)
        assertEquals(0, catalog.freezes)
        assertEquals(0, catalog.approvals.size)
        assertFalse(install.downloadAndInstall(prepared))
        assertFalse(install.approveRegistration(originalApproval))
        assertTrue(install.confirmPublishedDurability(prepared))
        runCurrent()
        assertSame(originalApproval, install.state.value.registration)
        assertEquals(0, catalog.freezes) // no replacement registry generation on restart
        assertEquals(0, catalog.approvals.size)
        assertTrue(install.approveRegistration(originalApproval))
        runCurrent()
        assertTrue(install.state.value.registered)
        assertSame(originalApproval, catalog.approvals.single())
        assertEquals(0, transactions.installs)
        assertEquals(1, transactions.confirmations)
    }

    fun testDescriptionPersistenceFailureRetainsPublishedReceiptAndExactFrozenPreview() = runTest {
        val transactions = Transactions().apply {
            durable = true
            beforeRememberRegistration = { throw IOException("description fsync failed") }
        }
        val catalog = Catalog().apply { fail = false }
        val install = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog, this)
        runCurrent()
        install.prepare("alpine", "primary")
        runCurrent()
        val prepared = install.state.value.prepared!!
        install.downloadAndInstall(prepared)
        runCurrent()
        assertTrue(install.state.value.receipt!!.directorySyncConfirmed)
        val original = install.state.value.registration!!
        assertEquals(1, catalog.freezes)
        assertFalse(install.downloadAndInstall(prepared))
        transactions.beforeRememberRegistration = null
        install.approveRegistration(original)
        runCurrent()
        assertTrue(install.state.value.registered)
        assertSame(original, catalog.approvals.single())
        assertEquals(1, catalog.freezes)
        assertEquals(1, transactions.installs)
    }

    fun testRecoveryValidationFailureBlocksFreshUuidAndManualBypass() = runTest {
        val transactions = Transactions().apply { recoveryFailure = IOException("journal missing or corrupt") }
        val install = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), Catalog(), this)
        runCurrent()
        assertTrue(install.state.value.recoveryBlocked)
        assertTrue(install.blocksManual)
        assertFalse(install.prepare("alpine", "primary"))
        assertFalse(install.discard())
        assertNull(install.state.value.prepared)
        assertEquals(0, transactions.installs)
    }

    fun testUnknownExplicitReinspectionNeverClearsRetainedOwnerOrReplaysWork() = runTest {
        for (mode in LinuxExecutionMode.entries) {
            val transactions = Transactions()
            val configuration = app.daiyu.android.terminal.LinuxInstallation("alpine", "alpine", "retained",
                File("/private/linux/alpine/retained/rootfs"),
                if (mode == LinuxExecutionMode.PROOT) helpers().proot else null,
                if (mode == LinuxExecutionMode.PROOT) helpers().prootSha256 else "",
                if (mode == LinuxExecutionMode.PROOT) helpers().loader else null,
                if (mode == LinuxExecutionMode.PROOT) helpers().loaderSha256 else "", executionMode = mode)
            val prepared = transactions.prepare(LinuxRootfsCatalog.find("alpine", "arm64-v8a")!!, "primary", configuration)
            val approval = FrozenLinuxApproval(configuration.copy(generation = "registration"))
            transactions.recovered = RecoveredLinuxInstall(prepared, true, true, approval)
            val catalog = Catalog().apply { fail = false }
            val coordinator = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog,
                this, executionMode = mode, chrootAbi = { "arm64-v8a" })
            runCurrent()
            transactions.recovered = transactions.recovered!!.copy(unknown = false)
            assertTrue(coordinator.recover())
            runCurrent()
            assertTrue(coordinator.state.value.unknown)
            assertSame(prepared, coordinator.state.value.prepared)
            assertSame(approval, coordinator.state.value.registration)
            assertFalse(coordinator.downloadAndInstall(prepared))
            assertFalse(coordinator.confirmPublishedDurability(prepared))
            assertFalse(coordinator.approveRegistration(approval))
            assertFalse(coordinator.retryCleanup(prepared))
            assertEquals(0, transactions.installs)
            assertEquals(0, transactions.completions)
            assertEquals(0, catalog.freezes)
            transactions.recovered = null
            assertTrue(coordinator.recover())
            runCurrent()
            assertTrue(coordinator.state.value.unknown)
            assertTrue(coordinator.state.value.recoveryBlocked)
            assertSame(prepared, coordinator.state.value.prepared)
            assertTrue(coordinator.blocksManual)
            coordinator.close()
        }
    }

    fun testCleanupFailureBlocksRegistrationUntilExactTransactionCleanupReceipt() = runTest {
        val transactions = Transactions().apply {
            beforeInstall = { throw LinuxInstallCleanupPending(cleanupReceipt, null, IOException("delete failed")) }
        }
        val catalog = Catalog().apply { fail = false }
        val coordinator = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), catalog, this)
        runCurrent()
        coordinator.prepare("alpine", "primary")
        runCurrent()
        val prepared = coordinator.state.value.prepared!!
        transactions.cleanupReceipt = LinuxInstallReceipt(prepared.installation, true)
        assertTrue(coordinator.downloadAndInstall(prepared))
        runCurrent()
        assertTrue(coordinator.state.value.cleanupPending)
        assertSame(transactions.cleanupReceipt, coordinator.state.value.receipt)
        assertTrue(coordinator.blocksManual)
        assertFalse(coordinator.prepareRegistration())
        assertFalse(coordinator.downloadAndInstall(prepared))
        assertFalse(coordinator.recover())
        assertEquals(0, catalog.freezes)
        assertTrue(coordinator.retryCleanup(prepared))
        runCurrent()
        assertFalse(coordinator.state.value.cleanupPending)
        assertEquals(1, transactions.cleanupRetries)
        assertEquals(1, transactions.installs)
        assertEquals(1, catalog.freezes)
        assertEquals(0, catalog.approvals.size)
        coordinator.close()
    }

    fun testCleanupRetryPreservesPreviouslyPublishedReceiptAfterFailedConfirmation() = runTest {
        val transactions = Transactions()
        val coordinator = LinuxInstallCoordinator(transactions, ::helpers, File("/private/linux"), Catalog(), this)
        runCurrent()
        assertTrue(coordinator.prepare("alpine", "primary")); runCurrent()
        val prepared = checkNotNull(coordinator.state.value.prepared)
        assertTrue(coordinator.downloadAndInstall(prepared)); runCurrent()
        val published = checkNotNull(coordinator.state.value.receipt)
        assertFalse(published.directorySyncConfirmed)
        transactions.confirmationFailure = LinuxInstallCleanupPending(null, IOException("sync"), IOException("cleanup"))
        assertTrue(coordinator.confirmPublishedDurability(prepared)); runCurrent()
        assertTrue(coordinator.state.value.cleanupPending)
        assertSame(published, coordinator.state.value.receipt)
        assertTrue(coordinator.retryCleanup(prepared)); runCurrent()
        assertFalse(coordinator.state.value.cleanupPending)
        assertSame(published, coordinator.state.value.receipt)
        assertFalse(coordinator.downloadAndInstall(prepared))
        transactions.confirmationFailure = null
        assertTrue(coordinator.confirmPublishedDurability(prepared)); runCurrent()
        assertTrue(checkNotNull(coordinator.state.value.receipt).directorySyncConfirmed)
        assertEquals(1, transactions.installs)
        assertEquals(1, transactions.cleanupRetries)
        assertEquals(2, transactions.confirmations)
        coordinator.close()
    }

    fun testPlatformGateAndPinnedPairs() {
        for (sdk in 26..33) assertTrue(runCatching {
            PackagedProotHelperProvider.requirePlatform(sdk, true, "aarch64")
        }.isFailure)
        assertEquals("arm64-v8a", PackagedProotHelperProvider.requirePlatform(34, true, "aarch64"))
        assertEquals("x86_64", PackagedProotHelperProvider.requirePlatform(34, true, "x86_64"))
        assertTrue(runCatching { PackagedProotHelperProvider.requirePlatform(34, false, "aarch64") }.isFailure)
        assertTrue(runCatching { PackagedProotHelperProvider.requirePlatform(34, true, "riscv64") }.isFailure)
        assertEquals("a8ebc8a4b77a634f88dd8e270423fdbc761666eb4e26b391966657550e3d0f5b",
            PackagedProotHelperProvider.hashes.getValue("arm64-v8a").first)
        assertEquals("3a28ac6af36b5fbd8142ba62dd9780bbf308736cef8672518ddca0e685e9999f",
            PackagedProotHelperProvider.hashes.getValue("x86_64").second)
    }

    private class Probe(val snapshot: InstallProbeSnapshot, val terminated: Boolean) : InstallProbeSession {
        var closes = 0
        var awaits = 0
        override fun snapshot() = snapshot
        override fun awaitTermination(timeoutMillis: Long): Boolean { awaits++; return terminated }
        override fun close() { closes++ }
    }
    fun testProbeCancellationDirectlyClosesLiveHandleAndKeepsUnknown() {
        val token = LinuxInstallCancellation()
        var closed = 0
        val probe = object : InstallProbeSession {
            override fun snapshot(): InstallProbeSnapshot {
                token.cancel()
                return InstallProbeSnapshot(null, "0\\n", 2, false, false)
            }
            override fun awaitTermination(timeoutMillis: Long) = false
            override fun close() { closed++ }
        }
        val failure = runCatching { AndroidLinuxInstallProbeRunner({ probe })
            .run(TerminalConfiguration(backend = "linux"), "id -u", token) }.exceptionOrNull()
        assertTrue(failure is LinuxInstallProbeUnknown)
        assertTrue(closed > 0)
        assertSame(probe, (failure as LinuxInstallProbeUnknown).retainedResource)
    }

    fun testProbeNeedsRawWaitAndTerminationNotOutputAlone() {
        val config = TerminalConfiguration(backend = "linux")
        val outputOnly = Probe(InstallProbeSnapshot(null, "0\n", 2, false, false), true)
        assertTrue(runCatching { AndroidLinuxInstallProbeRunner({ outputOnly }, 1)
            .run(config, "id -u", LinuxInstallCancellation()) }.exceptionOrNull() is LinuxInstallProbeUnknown)
        val noTermination = Probe(InstallProbeSnapshot(0, "0\n", 2, false, false), false)
        assertTrue(runCatching { AndroidLinuxInstallProbeRunner({ noTermination }, 1)
            .run(config, "id -u", LinuxInstallCancellation()) }.exceptionOrNull() is LinuxInstallProbeUnknown)
        val done = Probe(InstallProbeSnapshot(0, "0\n", 2, false, false), true)
        assertEquals(LinuxProbeResult(0, "0\n"), AndroidLinuxInstallProbeRunner({ done })
            .run(config, "id -u", LinuxInstallCancellation()))
        assertTrue(done.awaits > 0)
        assertTrue(done.closes > 0)
        val overflow = Probe(InstallProbeSnapshot(0, "0\n", 4097, true, false), true)
        assertTrue(runCatching { AndroidLinuxInstallProbeRunner({ overflow })
            .run(config, "id -u", LinuxInstallCancellation()) }.isFailure)
    }
}
