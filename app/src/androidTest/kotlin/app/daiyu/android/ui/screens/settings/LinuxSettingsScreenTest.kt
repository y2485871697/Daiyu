package app.daiyu.android.ui.screens.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import app.daiyu.android.runningmode.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.terminal.*
import app.daiyu.android.ui.app.AgentAppTheme
import java.util.concurrent.atomic.AtomicInteger

/** Layout/selection fixtures never install or open a terminal. */
class LinuxSettingsScreenTest : WiredActivityTestCase() {
    private val writes = AtomicInteger()
    private var settings: LinuxSettingsController? = null
    private val modes = RunningModeStore(object : RunningModePersistence {
        private var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    })
    private var owner: LinuxSettingsPageOwner? = null
    private val loads = AtomicInteger()
    private val selects = AtomicInteger()
    private var currentSelection: LinuxGenerationRef? = null
    private var failSwitch = false
    private var pageTitle = "Linux 工具环境"
    private val choicePreferences by lazy {
        instrumentation.targetContext.getSharedPreferences("linux-choice-test-${java.util.UUID.randomUUID()}", android.content.Context.MODE_PRIVATE)
    }
    private fun show(entries: List<LinuxInstallation> = emptyList(), filesOnly: Boolean = false,
        onOpenTerminal: (() -> Unit)? = null,
        preferenceOverride: android.content.SharedPreferences? = null,
        selectedReference: LinuxGenerationRef? = null) {
        settings?.close()
        currentSelection = selectedReference
        pageTitle = if (filesOnly) "工作区" else "Linux 工具环境"
        val catalog = object : LinuxSettingsCatalogAccess {
            override fun freeze(configuration: LinuxInstallation): FrozenLinuxApproval { writes.incrementAndGet(); error("no configuration writes") }
            override suspend fun load(): List<LinuxInstallation> { loads.incrementAndGet(); return entries }
            override suspend fun approve(approval: FrozenLinuxApproval): List<LinuxInstallation> { writes.incrementAndGet(); error("no approval") }
            override suspend fun approveInstallation(approval: FrozenLinuxApproval, admit: () -> Boolean): List<LinuxInstallation> { writes.incrementAndGet(); error("no installation") }
            override suspend fun registrationRecorded(approval: FrozenLinuxApproval) = false
            override suspend fun selected() = currentSelection
            override suspend fun select(installation: LinuxInstallation, admit: () -> Boolean) {
                check(admit())
                selects.incrementAndGet()
                check(!failSwitch) { "fixture_selection_failed" }
                currentSelection = LinuxGenerationRef(installation.environmentId, installation.generation)
            }
            override suspend fun remove(reference: LinuxGenerationRef): List<LinuxInstallation> { writes.incrementAndGet(); error("no removal") }
        }
        val preferences = LinuxInstallChoicePreferences(preferenceOverride ?: choicePreferences)
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()))
        settings = controller
        val page = controller.enterPage()
        owner = page
        ui.waitUntil(5_000) { page.state.value.listConfirmed }
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    LinuxSettingsScreen(controller, page, onBack = {}, runningModeStore = modes, filesOnly = filesOnly, onOpenTerminal = onOpenTerminal,
                        suppliedChoicePreferences = preferences)
                }
            }
        }
    }
    private fun revealLinux(tag: String): SemanticsNodeInteraction {
        ui.onNode(hasTestTag("page:工作区") or hasTestTag("page:$pageTitle")).scrollToDescendant(hasTestTag(tag))
        return node(tag)
    }
    fun testOriginalGroupsHaveNoTechnicalPreviewAndUnavailableToolsAreNotMarkedInstalled() {
        show()
        node("linux-distribution-choice").assertIsDisplayed()
        revealLinux("linux-execution-choice").assertIsDisplayed()
        node("linux-tab:basic").assertIsSelected()
        node("linux-tab:files").assertHasClickAction()
        for (tag in listOf("linux-workspace", "linux-shared-folders", "linux-file-browser")) node(tag).assertDoesNotExist()
        revealLinux("linux-tool-install:0").assertIsNotEnabled()
        for (text in listOf("预览安装（不下载）", "安装说明与诊断", "手工登记环境（高级）", "固定发行版 proot 安装（不自动注册）", "已安装")) {
            ui.onAllNodesWithText(text).assertCountEquals(0)
        }
        assertEquals(0, writes.get())
        assertEquals(0, fixture.opens.get())
    }
    fun testFileTabsEmbedAllThreeScopesWithoutRevokingLinuxOwnerOrStartingAnything() {
        show()
        val reads = loads.get()
        node("linux-distribution-choice").performClick()
        node("linux-choice:linux-distribution-choice:1").performClick()
        node("linux-tab:files").performClick().assertIsSelected()
        node("linux-files:workspace").assertIsSelected()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("workspace-private-import").fetchSemanticsNodes().singleOrNull()?.let {
                it.config.contains(SemanticsActions.OnClick) &&
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            } == true
        }
        node("workspace-private-import").assertHasClickAction().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        node("workspace-private-refresh").assertHasClickAction().assertIsEnabled()
        ui.onNodeWithText("正在处理私有文件").assertDoesNotExist()
        ui.onNodeWithText("私有工作区").assertDoesNotExist()
        ui.onNodeWithText("应用专属目录，与只读共享和 Linux 挂载分开。导入单文件上限 256 MiB；删除仅支持文件和空目录。").assertDoesNotExist()
        revealLinux("linux-files:rootfs").performClick().assertIsSelected()
        ui.onNodeWithText("当前环境暂无可浏览的 Rootfs").assertExists()
        ui.onNodeWithText("正在读取 Rootfs").assertDoesNotExist()
        node("workspace-private-import").assertDoesNotExist()
        revealLinux("linux-files:shared").performClick().assertIsSelected()
        revealLinux("workspace-pick-directory").assertHasClickAction()
        revealLinux("workspace-pick-file").assertHasClickAction()
        ui.onNodeWithText("正在读取共享状态").assertDoesNotExist()
        ui.onNodeWithText("正在读取目录").assertDoesNotExist()
        ui.onNodeWithText("选择要共享的目录或文件，访问只读。Linux 目录挂载在 Linux 工具环境中单独配置。").assertDoesNotExist()
        revealLinux("linux-files:workspace").performClick().assertIsSelected()
        ui.onNodeWithText("正在处理私有文件").assertDoesNotExist()
        node("linux-tab:basic").performClick().assertIsSelected()
        node("linux-distribution-choice").assertTextContains("Debian")
        assertFalse(checkNotNull(owner).state.value.revoked)
        assertEquals(reads, loads.get())
        assertEquals(0, writes.get())
        assertEquals(0, fixture.opens.get())
    }
    fun testOnlyWorkspaceSelectionChangesTheTopTitleAndOtherTabsKeepTheirTitles() {
        show()
        node("page:Linux 工具环境").assertExists()
        node("page:工作区").assertDoesNotExist()
        node("linux-tab:files").performClick().assertIsSelected()
        node("linux-files:workspace").assertIsSelected().assertTextContains("工作区")
        node("page:工作区").assertExists()
        node("page:Linux 工具环境").assertDoesNotExist()
        for ((location, label) in listOf("rootfs" to "Rootfs", "shared" to "共享文件夹")) {
            revealLinux("linux-files:$location").performClick().assertIsSelected().assertTextContains(label)
            node("page:Linux 工具环境").assertExists()
            node("page:工作区").assertDoesNotExist()
        }
        revealLinux("linux-files:workspace").performClick().assertIsSelected()
        node("page:工作区").assertExists()
        node("linux-tab:basic").performClick().assertIsSelected().assertTextContains("基本")
        node("page:Linux 工具环境").assertExists()
        node("page:工作区").assertDoesNotExist()
        node("linux-tab:files").performClick().assertIsSelected().assertTextContains("文件")
        node("linux-files:workspace").assertIsSelected()
        node("page:工作区").assertExists()
        assertFalse(checkNotNull(owner).state.value.revoked)
        assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
    }

    fun testFilesOnlyOpensNewWorkspaceWithoutBottomNavigationOrBasicActions() {
        show(filesOnly = true)
        node("page:工作区").assertExists()
        node("linux-files:workspace").assertIsSelected()
        node("linux-bottom-navigation").assertDoesNotExist()
        node("linux-tab:basic").assertDoesNotExist()
        node("linux-tab:files").assertDoesNotExist()
        node("linux-distribution-choice").assertDoesNotExist()
        node("linux-execution-choice").assertDoesNotExist()
        ui.onNodeWithText("私有工作区").assertDoesNotExist()
        revealLinux("linux-files:rootfs").performClick().assertIsSelected()
        node("page:工作区").assertExists()
        ui.onNodeWithText("当前环境暂无可浏览的 Rootfs").assertExists()
        revealLinux("linux-files:shared").performClick().assertIsSelected()
        node("page:工作区").assertExists()
        revealLinux("workspace-pick-directory").assertHasClickAction()
        revealLinux("workspace-pick-file").assertHasClickAction()
        revealLinux("linux-files:workspace").performClick().assertIsSelected()
        node("workspace-private-import").assertExists()
        node("linux-bottom-navigation").assertDoesNotExist()
        assertFalse(checkNotNull(owner).state.value.revoked)
        assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
    }

    fun testBasicActionsRetainedAcrossTabsCannotChangeSelectionOrOpenTerminal() {
        val terminalNavigations = AtomicInteger()
        assertTrue(LinuxInstallChoicePreferences(choicePreferences).save(
            LinuxInstallChoice("alpine", LinuxExecutionMode.PROOT)))
        show(onOpenTerminal = { terminalNavigations.incrementAndGet() })
        val oldTerminal = checkNotNull(node("linux-open-terminal").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        node("linux-distribution-choice").performClick()
        val oldDebian = checkNotNull(node("linux-choice:linux-distribution-choice:1")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        // Use the retained bottom-tab action while the old popup is still expanded.
        val filesTab = checkNotNull(node("linux-tab:files").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        mainThread { filesTab() }
        mainThread { oldDebian(); oldTerminal() }
        assertEquals(0, terminalNavigations.get())
        node("linux-tab:basic").performClick()
        mainThread { oldDebian(); oldTerminal() }
        node("linux-distribution-choice").assertTextContains("Alpine")
        assertEquals(LinuxInstallChoice("alpine", LinuxExecutionMode.PROOT),
            LinuxInstallChoicePreferences(choicePreferences).read())
        assertEquals(0, terminalNavigations.get())
        node("linux-open-terminal").performClick()
        assertEquals(1, terminalNavigations.get())
        assertFalse(checkNotNull(owner).state.value.revoked)
        assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
    }
    fun testApprovedRootfsListsRealDirectoriesReadOnlyAndReturnsToParent() {
        assertApprovedRootfsReadOnly(filesOnly = false)
    }
    fun testFilesOnlyUsesApprovedRootfsReadOnlyWithoutBottomNavigation() {
        assertApprovedRootfsReadOnly(filesOnly = true)
    }
    private fun assertApprovedRootfsReadOnly(filesOnly: Boolean) {
        val root = java.io.File(instrumentation.targetContext.filesDir.canonicalFile, "rootfs-layout-${java.util.UUID.randomUUID()}").apply { mkdir() }
        val child = java.io.File(root, "etc").apply { mkdir() }
        java.io.File(child, "config").writeText("keep")
        try {
            show(listOf(LinuxInstallation("alpine", "fixture", "one", root)), filesOnly = filesOnly)
            if (filesOnly) node("linux-bottom-navigation").assertDoesNotExist()
            else node("linux-tab:files").performClick()
            revealLinux("linux-files:rootfs").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("linux-rootfs-entry:etc").fetchSemanticsNodes().size == 1 }
            revealLinux("linux-rootfs-entry:etc").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("linux-rootfs-entry:etc/config").fetchSemanticsNodes().size == 1 }
            node("workspace-private-import").assertDoesNotExist()
            revealLinux("linux-rootfs-entry:etc/config").performClick()
            node("linux-rootfs-entry:etc/config").assertExists()
            node("workspace-entry-action-sheet").assertDoesNotExist()
            assertEquals("keep", java.io.File(child, "config").readText())
            revealLinux("linux-rootfs-entry:etc/config").performTouchInput { longClick() }
            node("workspace-entry-action:rename").assertDoesNotExist()
            node("workspace-entry-action:delete").assertDoesNotExist()
            ui.onNodeWithText("Rootfs 系统目录和符号链接不允许重命名或删除").assertExists()
            revealLinux("linux-rootfs-parent").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("linux-rootfs-entry:etc").fetchSemanticsNodes().size == 1 }
            assertEquals("keep", java.io.File(child, "config").readText())
            assertFalse(checkNotNull(owner).state.value.revoked)
            assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
        } finally {
            mainThread { activity.setContent {} }
            ui.waitForIdle()
            root.deleteRecursively()
        }
    }
    fun testRetainedFileToolbarCallbacksExpireAfterSourceSwitchAndDoNotOpenPickers() {
        val pickerLaunches = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT && intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                pickerLaunches.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            show()
            node("linux-tab:files").performClick()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithTag("workspace-private-import").fetchSemanticsNodes().singleOrNull()?.let {
                    it.config.contains(SemanticsActions.OnClick) &&
                        !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                } == true
            }
            val oldImport = checkNotNull(node("workspace-private-import").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            val oldRefresh = checkNotNull(node("workspace-private-refresh").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            revealLinux("linux-files:rootfs").performClick()
            ui.onNodeWithText("Rootfs 仅供只读浏览，不跟随符号链接，不导入或删除环境文件。").assertDoesNotExist()
            mainThread { oldImport(); oldRefresh() }
            assertEquals(0, pickerLaunches.get())
            ui.onNodeWithText("当前环境暂无可浏览的 Rootfs").assertIsDisplayed()
            node("workspace-private-import").assertDoesNotExist()
            revealLinux("linux-files:workspace").performClick()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithTag("workspace-private-import").fetchSemanticsNodes().singleOrNull()?.let {
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                } == true
            }
            mainThread { oldImport(); oldRefresh() }
            assertEquals(0, pickerLaunches.get())
            node("workspace-private-import").assertIsEnabled().performClick()
            ui.waitUntil(5_000) { pickerLaunches.get() == 1 }
            node("linux-tab:files").assertIsDisplayed()
            assertFalse(checkNotNull(owner).state.value.revoked)
            assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun setRunningMode(mode: RunningMode) {
        mainThread { assertTrue(modes.save(mode)) }
    }

    fun testDistributionAndModePopupsOnlyChangeTheInstallChoice() {
        show()
        node("linux-distribution-choice").performClick()
        node("linux-choice:linux-distribution-choice:1").performClick()
        node("linux-distribution-choice").assertTextContains("Debian")
        setRunningMode(RunningMode.ROOT)
        revealLinux("linux-execution-choice").performClick()
        node("linux-choice:linux-execution-choice:1").performClick()
        revealLinux("linux-execution-choice").assertTextContains("Root 模式 · chroot")
        assertEquals(0, writes.get())
        assertEquals(0, fixture.opens.get())
    }
    fun testInstallChoicesPersistAcrossNewPageAndPreferenceInstancesWithoutInstalling() {
        setRunningMode(RunningMode.ROOT)
        show()
        node("linux-distribution-choice").performClick()
        node("linux-choice:linux-distribution-choice:1").performClick()
        revealLinux("linux-execution-choice").performClick()
        node("linux-choice:linux-execution-choice:1").performClick()
        val previousOwner = checkNotNull(owner)
        show()
        assertTrue(previousOwner.state.value.revoked)
        node("linux-distribution-choice").assertTextContains("Debian")
        revealLinux("linux-execution-choice").assertTextContains("Root 模式 · chroot")
        assertEquals(LinuxInstallChoice("debian", LinuxExecutionMode.CHROOT),
            LinuxInstallChoicePreferences(choicePreferences).read())
        setRunningMode(RunningMode.NORMAL)
        revealLinux("linux-execution-choice").assertTextContains("普通模式 · PRoot")
        setRunningMode(RunningMode.ROOT)
        revealLinux("linux-execution-choice").assertTextContains("Root 模式 · chroot")
        assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
    }

    private fun installedPair(mode: LinuxExecutionMode = LinuxExecutionMode.PROOT) = listOf(
        LinuxInstallation("alpine", "alpine", "alpine-gen", java.io.File("/fixture/alpine"),
            java.io.File("/fixture/proot"), "a".repeat(64), java.io.File("/fixture/loader"), "b".repeat(64), executionMode = mode),
        LinuxInstallation("debian", "debian", "debian-gen", java.io.File("/fixture/debian"),
            java.io.File("/fixture/proot"), "a".repeat(64), java.io.File("/fixture/loader"), "b".repeat(64), executionMode = mode))

    fun testBrowsingInstalledEnvironmentDoesNotSwitchUntilOriginalConfigurationButtonIsClicked() {
        val entries = installedPair()
        val alpine = LinuxGenerationRef("alpine", "alpine-gen")
        val debian = LinuxGenerationRef("debian", "debian-gen")
        assertTrue(LinuxInstallChoicePreferences(choicePreferences).save(LinuxInstallChoice("alpine", LinuxExecutionMode.PROOT)))
        show(entries = entries, selectedReference = alpine)
        node("linux-switch-environment").assertDoesNotExist()
        node("linux-distribution-choice").performClick()
        node("linux-choice:linux-distribution-choice:1").performClick()
        assertEquals(0, selects.get())
        assertEquals(alpine, currentSelection)
        revealLinux("linux-switch-environment").assertIsEnabled().assertTextContains("切换环境").performClick()
        ui.waitUntil(5_000) { checkNotNull(owner).state.value.selected == debian && !checkNotNull(owner).state.value.busy }
        assertEquals(1, selects.get())
        assertEquals(debian, currentSelection)
        node("linux-switch-environment").assertDoesNotExist()
        node("linux-install-environment").assertDoesNotExist()
        assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
    }

    fun testSwitchFailureIsExplicitlyRetriedWithoutLosingBrowserChoice() {
        val entries = installedPair()
        val alpine = LinuxGenerationRef("alpine", "alpine-gen")
        assertTrue(LinuxInstallChoicePreferences(choicePreferences).save(LinuxInstallChoice("debian", LinuxExecutionMode.PROOT)))
        failSwitch = true
        show(entries = entries, selectedReference = alpine)
        assertEquals(0, selects.get())
        revealLinux("linux-switch-environment").performClick()
        ui.waitUntil(5_000) { checkNotNull(owner).state.value.error && !checkNotNull(owner).state.value.busy }
        assertEquals(alpine, currentSelection)
        node("linux-distribution-choice").assertTextContains("Debian")
        failSwitch = false
        revealLinux("linux-switch-environment").assertIsEnabled().performClick()
        ui.waitUntil(5_000) { checkNotNull(owner).state.value.selected == LinuxGenerationRef("debian", "debian-gen") }
        assertEquals(2, selects.get())
        assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
    }

    fun testRetainedSwitchCallbackExpiresAfterChoiceChangeAndFilesRoundTrip() {
        val entries = installedPair()
        val alpine = LinuxGenerationRef("alpine", "alpine-gen")
        assertTrue(LinuxInstallChoicePreferences(choicePreferences).save(LinuxInstallChoice("debian", LinuxExecutionMode.PROOT)))
        show(entries = entries, selectedReference = alpine)
        val oldSwitch = retainedClickCallback(revealLinux("linux-switch-environment").assertIsEnabled())
        node("linux-distribution-choice").performClick()
        node("linux-choice:linux-distribution-choice:0").performClick()
        awaitVisualIdle()
        node("linux-distribution-choice").assertTextContains("Alpine")
        mainThread { oldSwitch() }
        assertEquals(0, selects.get())
        node("linux-distribution-choice").performClick()
        node("linux-choice:linux-distribution-choice:1").performClick()
        awaitVisualIdle()
        node("linux-distribution-choice").assertTextContains("Debian")
        mainThread { oldSwitch() }
        assertEquals(0, selects.get())
        val oldBeforeFiles = retainedClickCallback(revealLinux("linux-switch-environment").assertIsEnabled())
        node("linux-tab:files").performClick(); node("linux-tab:basic").performClick()
        awaitVisualIdle()
        mainThread { oldBeforeFiles() }
        assertEquals(alpine, currentSelection)
        assertEquals(0, selects.get()); assertEquals(0, fixture.opens.get())
    }

    fun testPreferenceWriteFailureKeepsConfirmedChoiceAndShowsError() {
        val original = LinuxInstallChoice("alpine", LinuxExecutionMode.PROOT)
        assertTrue(LinuxInstallChoicePreferences(choicePreferences).save(original))
        val failing = object : android.content.SharedPreferences by choicePreferences {
            override fun edit(): android.content.SharedPreferences.Editor {
                val real = choicePreferences.edit()
                return object : android.content.SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                        real.putString(key, value); return this
                    }
                    override fun commit(): Boolean { real.commit(); return false }
                }
            }
        }
        show(preferenceOverride = failing)
        node("linux-distribution-choice").performClick()
        node("linux-choice:linux-distribution-choice:1").performClick()
        node("linux-distribution-choice").assertTextContains("Alpine")
        ui.onNodeWithText("选项未保存，请重试。").assertExists()
        assertEquals(original, LinuxInstallChoicePreferences(choicePreferences).read())
        show()
        node("linux-distribution-choice").assertTextContains("Alpine")
        assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
    }

    fun testRetainedDistributionCallbackCannotOverwritePreferenceAfterReentry() {
        assertTrue(LinuxInstallChoicePreferences(choicePreferences).save(
            LinuxInstallChoice("alpine", LinuxExecutionMode.PROOT)))
        show()
        node("linux-distribution-choice").performClick()
        val oldDebian = checkNotNull(node("linux-choice:linux-distribution-choice:1")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        // Leave with the old popup still open so its expanded flag cannot mask the page guard.
        show()
        mainThread { oldDebian() }
        node("linux-distribution-choice").assertTextContains("Alpine")
        assertEquals(LinuxInstallChoice("alpine", LinuxExecutionMode.PROOT),
            LinuxInstallChoicePreferences(choicePreferences).read())
        assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
    }

    fun testChrootChoiceIsOnlyVisibleInRootModeAndOldPopupCannotSelectIt() {
        show()
        for (mode in listOf(RunningMode.NORMAL, RunningMode.SHIZUKU)) {
            setRunningMode(mode)
            revealLinux("linux-execution-choice").performClick()
            node("linux-choice:linux-execution-choice:1").assertDoesNotExist()
            node("linux-choice:linux-execution-choice:0").performClick()
        }
        setRunningMode(RunningMode.ROOT)
        revealLinux("linux-execution-choice").performClick()
        node("linux-choice:linux-execution-choice:1").assertExists()
        val oldChroot = checkNotNull(node("linux-choice:linux-execution-choice:1")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        setRunningMode(RunningMode.NORMAL)
        node("linux-choice:linux-execution-choice:1").assertDoesNotExist()
        node("linux-choice:linux-execution-choice:0").performClick()
        revealLinux("linux-execution-choice").assertTextContains("普通模式 · PRoot")
        mainThread { oldChroot() }
        revealLinux("linux-execution-choice").assertTextContains("普通模式 · PRoot")
        setRunningMode(RunningMode.ROOT)
        mainThread { oldChroot() }
        revealLinux("linux-execution-choice").assertTextContains("普通模式 · PRoot")
        assertEquals(0, writes.get())
        assertEquals(0, fixture.opens.get())
    }


    fun testOpenModePopupRebuildsAcrossRunningModesWithoutSelectingAnything() {
        show()
        setRunningMode(RunningMode.ROOT)
        revealLinux("linux-execution-choice").performClick()
        val oldChroot = checkNotNull(node("linux-choice:linux-execution-choice:1")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        setRunningMode(RunningMode.SHIZUKU)
        node("linux-choice:linux-execution-choice:1").assertDoesNotExist()
        node("linux-choice:linux-execution-choice:0").assertIsDisplayed()
        mainThread { oldChroot() }
        setRunningMode(RunningMode.ROOT)
        node("linux-choice:linux-execution-choice:1").assertIsDisplayed()
        mainThread { oldChroot() }
        node("linux-choice:linux-execution-choice:0").performClick()
        revealLinux("linux-execution-choice").assertTextContains("普通模式 · PRoot")
        assertFalse(checkNotNull(owner).state.value.revoked)
        assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
    }

    override fun tearDown() {
        try { settings?.close(); choicePreferences.edit().clear().commit() } finally { super.tearDown() }
    }
}
