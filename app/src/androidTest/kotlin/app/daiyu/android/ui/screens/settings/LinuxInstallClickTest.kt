package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.runningmode.*
import app.daiyu.android.terminal.*
import app.daiyu.android.terminal.install.*
import app.daiyu.android.ui.app.AgentAppTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Exercises real page/coordinator routing with queued, file-free install transactions. */
class LinuxInstallClickTest : WiredActivityTestCase() {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        val pending: Boolean get() = queue.isNotEmpty()
        fun runNext() { checkNotNull(queue.poll()).run() }
        fun drain() {
            repeat(100) { (queue.poll() ?: return).run() }
            error("install_test_queue_did_not_settle")
        }
    }

    private class Transactions : LinuxInstallTransactions {
        private val owner = Any()
        var prepares = 0
        var installs = 0
        var failPrepare = false
        var failInstall = false
        var failRemember = false
        val submitted = mutableListOf<PreparedLinuxInstall>()
        override fun prepare(artifact: LinuxRootfsArtifact, mirrorId: String, installation: LinuxInstallation): PreparedLinuxInstall {
            prepares++
            if (failPrepare) throw IOException("fixture_prepare_failed")
            return PreparedLinuxInstall(owner, artifact, artifact.mirrors.single { it.id == mirrorId }, installation)
        }
        override fun install(prepared: PreparedLinuxInstall, cancellation: LinuxInstallCancellation): LinuxInstallReceipt {
            installs++
            submitted.add(prepared)
            cancellation.check()
            if (failInstall) throw IOException("fixture_install_failed")
            return LinuxInstallReceipt(prepared.installation, true)
        }
        override fun remember(prepared: PreparedLinuxInstall) {
            if (failRemember) throw IOException("fixture_remember_failed")
        }
        override fun rememberRegistration(prepared: PreparedLinuxInstall, approval: FrozenLinuxApproval) = Unit
        override fun completeRegistration(prepared: PreparedLinuxInstall, approval: FrozenLinuxApproval) = Unit
        override fun discard(prepared: PreparedLinuxInstall) = Unit
        override fun recover(helpers: PackagedProotHelpers): RecoveredLinuxInstall? = null
        override fun recoverChroot(abi: String): RecoveredLinuxInstall? = null
        override fun confirmPublishedDurability(prepared: PreparedLinuxInstall) = error("unexpected_durability_retry")
    }

    private val dispatcher = QueuedDispatcher()
    private val transactions = Transactions()
    private var approvals = 0
    private var controller: LinuxSettingsController? = null
    private lateinit var coordinator: LinuxInstallCoordinator
    private lateinit var page: LinuxSettingsPageOwner
    private lateinit var preferences: LinuxInstallChoicePreferences
    private val modes = RunningModeStore(object : RunningModePersistence {
        override fun read(): String? = null
        override fun write(value: String) = Unit
    })
    private val catalog = object : LinuxSettingsCatalogAccess {
        override fun freeze(configuration: LinuxInstallation) = FrozenLinuxApproval(configuration)
        override suspend fun load() = emptyList<LinuxInstallation>()
        override suspend fun registrationRecorded(approval: FrozenLinuxApproval) = false
        override suspend fun approve(approval: FrozenLinuxApproval): List<LinuxInstallation> {
            approvals++
            error("unexpected_automatic_registration")
        }
        override suspend fun approveInstallation(approval: FrozenLinuxApproval, admit: () -> Boolean): List<LinuxInstallation> {
            approvals++
            error("unexpected_automatic_registration")
        }
        override suspend fun remove(reference: LinuxGenerationRef) = error("unexpected_removal")
    }

    private fun show(distribution: String = "debian", mode: LinuxExecutionMode = LinuxExecutionMode.PROOT,
        preparedBeforeEntry: Boolean = false) {
        controller?.close()
        assertTrue(modes.save(if (mode == LinuxExecutionMode.CHROOT) RunningMode.ROOT else RunningMode.NORMAL))
        val worker = CoroutineScope(SupervisorJob() + dispatcher)
        coordinator = LinuxInstallCoordinator(transactions,
            { PackagedProotHelpers("arm64-v8a", File("/fixture/proot"), "a".repeat(64), File("/fixture/loader"), "b".repeat(64)) },
            File(instrumentation.targetContext.cacheDir, "linux-click-fixture"), catalog, worker,
            executionMode = mode, chrootAbi = { "arm64-v8a" })
        controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), worker,
            installation = coordinator.takeIf { mode == LinuxExecutionMode.PROOT },
            chrootInstallation = coordinator.takeIf { mode == LinuxExecutionMode.CHROOT },
            allowChroot = { modes.mode.value == RunningMode.ROOT })
        preferences = LinuxInstallChoicePreferences(instrumentation.targetContext.getSharedPreferences(
            "linux-click-${java.util.UUID.randomUUID()}", android.content.Context.MODE_PRIVATE))
        assertTrue(preferences.save(LinuxInstallChoice(distribution, mode)))
        dispatcher.drain()
        if (preparedBeforeEntry) {
            assertTrue(coordinator.prepare(distribution, "primary"))
            dispatcher.drain()
        }
        renderNewPage()
    }

    private fun renderNewPage() {
        val settings = checkNotNull(controller)
        page = settings.enterPage()
        dispatcher.drain()
        val renderedPage = page
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    LinuxSettingsScreen(settings, renderedPage, onBack = {}, runningModeStore = modes,
                        suppliedChoicePreferences = preferences)
                }
            }
        }
        node("linux-settings-view").assertExists()
    }

    private fun clickInstall() {
        ui.onNodeWithTag("page:Linux 工具环境").scrollToDescendant(hasTestTag("linux-install-environment"))
        node("linux-install-environment").performClick()
        ui.onNodeWithText("下载并安装").assertDoesNotExist()
    }

    private fun finishInstall(expected: Int) {
        dispatcher.runNext()
        ui.waitUntil(5_000) { dispatcher.pending }
        dispatcher.runNext()
        ui.waitUntil(5_000) { !coordinator.state.value.busy }
        ui.waitForIdle()
        assertEquals(expected, transactions.installs)
        assertFalse(dispatcher.pending)
        assertEquals(0, approvals)
        assertEquals(0, fixture.opens.get())
        ui.onNodeWithText("下载并安装").assertDoesNotExist()
    }

    fun testOneClickInstallsAllDistributionsAndModesWithoutConfirmationOrRegistration() {
        for (distribution in listOf("alpine", "debian")) for (mode in LinuxExecutionMode.entries) {
            val before = transactions.installs
            show(distribution, mode)
            assertEquals(before, transactions.installs)
            clickInstall()
            finishInstall(before + 1)
            assertTrue(coordinator.state.value.receipt?.directorySyncConfirmed == true)
            ui.onNodeWithText("启用环境").assertExists()
            ui.onNodeWithText("允许使用").assertDoesNotExist()
        }
    }

    fun testExistingPreparedWaitsForExplicitContinueAndReusesOriginalRequest() {
        show(preparedBeforeEntry = true)
        val original = checkNotNull(coordinator.state.value.prepared)
        ui.waitForIdle()
        assertFalse(dispatcher.pending)
        assertEquals(0, transactions.installs)
        ui.onNodeWithText("继续安装").performClick()
        ui.waitUntil(5_000) { dispatcher.pending }
        dispatcher.drain()
        ui.waitForIdle()
        assertEquals(1, transactions.installs)
        assertSame(original, transactions.submitted.single())
        assertEquals(1, transactions.prepares)
        assertEquals(0, approvals)
        ui.onNodeWithText("下载并安装").assertDoesNotExist()
    }

    fun testCancelWhilePreparingDoesNotInstallAfterPreparationFinishes() {
        show()
        clickInstall()
        node("linux-install-cancel").performClick()
        dispatcher.drain()
        ui.waitForIdle()
        assertNotNull(coordinator.state.value.prepared)
        assertEquals(0, transactions.installs)
        assertFalse(dispatcher.pending)
    }

    fun testTabChangeAndNewPageDropPendingClick() {
        show()
        clickInstall()
        node("linux-tab:files").performClick()
        dispatcher.drain()
        node("linux-tab:basic").performClick()
        ui.waitForIdle()
        assertEquals(0, transactions.installs)
        assertFalse(dispatcher.pending)
        renderNewPage()
        ui.waitForIdle()
        assertEquals(0, transactions.installs)
        assertFalse(dispatcher.pending)
    }

    fun testPageRevocationBeforePreparedDoesNotAuthorizeNewPage() {
        show()
        clickInstall()
        renderNewPage()
        ui.waitForIdle()
        assertNotNull(coordinator.state.value.prepared)
        assertEquals(0, transactions.installs)
        assertFalse(dispatcher.pending)
    }

    fun testRootModeWithdrawalDropsChrootClickEvenWhenRootModeReturns() {
        show(mode = LinuxExecutionMode.CHROOT)
        clickInstall()
        mainThread { assertTrue(modes.save(RunningMode.NORMAL)) }
        ui.waitForIdle()
        dispatcher.drain()
        mainThread { assertTrue(modes.save(RunningMode.ROOT)) }
        ui.waitForIdle()
        assertEquals(0, transactions.installs)
        assertFalse(dispatcher.pending)
    }

    fun testFailedPreparationDoesNotAuthorizeLaterPreparedState() {
        transactions.failPrepare = true
        show()
        clickInstall()
        dispatcher.drain()
        ui.waitForIdle()
        assertNull(coordinator.state.value.prepared)
        transactions.failPrepare = false
        assertTrue(coordinator.prepare("debian", "primary"))
        dispatcher.drain()
        ui.waitForIdle()
        assertNotNull(coordinator.state.value.prepared)
        assertEquals(0, transactions.installs)
        assertFalse(dispatcher.pending)
    }

    fun testFailedDescriptionSaveRetainsPreparedWithoutStartingInstall() {
        transactions.failRemember = true
        show()
        clickInstall()
        dispatcher.drain()
        ui.waitForIdle()
        val original = checkNotNull(coordinator.state.value.prepared)
        assertTrue(coordinator.state.value.operationFailed)
        assertEquals(0, transactions.installs)
        assertFalse(dispatcher.pending)
        transactions.failRemember = false
        ui.onNodeWithText("继续安装").performClick()
        ui.waitUntil(5_000) { dispatcher.pending }
        dispatcher.drain()
        ui.waitForIdle()
        assertEquals(1, transactions.installs)
        assertSame(original, transactions.submitted.single())
        assertFalse(coordinator.state.value.operationFailed)
        assertEquals(0, approvals)
    }

    fun testFailedInstallDoesNotRetryUntilExplicitContinue() {
        transactions.failInstall = true
        show()
        clickInstall()
        finishInstall(1)
        val original = checkNotNull(coordinator.state.value.prepared)
        transactions.failInstall = false
        ui.onNodeWithText("继续安装").performClick()
        ui.waitUntil(5_000) { dispatcher.pending }
        dispatcher.drain()
        ui.waitForIdle()
        assertEquals(2, transactions.installs)
        assertTrue(transactions.submitted.all { it === original })
        assertEquals(1, transactions.prepares)
        assertEquals(0, approvals)
    }

    override fun tearDown() {
        controller?.close()
        dispatcher.drain()
        super.tearDown()
    }
}
