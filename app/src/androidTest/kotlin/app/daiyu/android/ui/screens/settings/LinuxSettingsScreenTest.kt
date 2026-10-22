package app.daiyu.android.ui.screens.settings

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

/** Layout/selection checks use an empty catalog and never install or open a terminal. */
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
    private fun show(entries: List<LinuxInstallation> = emptyList(), onOpenTerminal: (() -> Unit)? = null) {
        val catalog = object : LinuxSettingsCatalogAccess {
            override fun freeze(configuration: LinuxInstallation): FrozenLinuxApproval { writes.incrementAndGet(); error("no configuration writes") }
            override suspend fun load(): List<LinuxInstallation> { loads.incrementAndGet(); return entries }
            override suspend fun approve(approval: FrozenLinuxApproval): List<LinuxInstallation> { writes.incrementAndGet(); error("no approval") }
            override suspend fun approveInstallation(approval: FrozenLinuxApproval, admit: () -> Boolean): List<LinuxInstallation> { writes.incrementAndGet(); error("no installation") }
            override suspend fun registrationRecorded(approval: FrozenLinuxApproval) = false
            override suspend fun remove(reference: LinuxGenerationRef): List<LinuxInstallation> { writes.incrementAndGet(); error("no removal") }
        }
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()))
        settings = controller
        val page = controller.enterPage()
        owner = page
        ui.waitUntil(5_000) { page.state.value.listConfirmed }
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    LinuxSettingsScreen(controller, page, onBack = {}, runningModeStore = modes, onOpenTerminal = onOpenTerminal)
                }
            }
        }
    }
    private fun revealLinux(tag: String): SemanticsNodeInteraction {
        node("page:Linux 工具环境").scrollToDescendant(hasTestTag(tag))
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
        node("workspace-private-import").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        node("workspace-private-refresh").assertHasClickAction()
        ui.onNodeWithText("私有工作区").assertDoesNotExist()
        ui.onNodeWithText("应用专属目录，与只读共享和 Linux 挂载分开。导入单文件上限 256 MiB；删除仅支持文件和空目录。").assertDoesNotExist()
        revealLinux("linux-files:rootfs").performClick().assertIsSelected()
        ui.onNodeWithText("当前环境暂无可浏览的 Rootfs").assertExists()
        node("workspace-private-import").assertDoesNotExist()
        revealLinux("linux-files:shared").performClick().assertIsSelected()
        revealLinux("workspace-pick-directory").assertHasClickAction()
        revealLinux("workspace-pick-file").assertHasClickAction()
        ui.onNodeWithText("选择要共享的目录或文件，访问只读。Linux 目录挂载在 Linux 工具环境中单独配置。").assertDoesNotExist()
        node("linux-tab:basic").performClick().assertIsSelected()
        node("linux-distribution-choice").assertTextContains("Debian")
        assertFalse(checkNotNull(owner).state.value.revoked)
        assertEquals(reads, loads.get())
        assertEquals(0, writes.get())
        assertEquals(0, fixture.opens.get())
    }
    fun testBasicActionsRetainedAcrossTabsCannotChangeSelectionOrOpenTerminal() {
        val terminalNavigations = AtomicInteger()
        show(onOpenTerminal = { terminalNavigations.incrementAndGet() })
        val oldTerminal = checkNotNull(node("linux-open-terminal").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        node("linux-distribution-choice").performClick()
        val oldDebian = checkNotNull(node("linux-choice:linux-distribution-choice:1")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        node("linux-choice:linux-distribution-choice:0").performClick()
        node("linux-tab:files").performClick()
        mainThread { oldDebian(); oldTerminal() }
        assertEquals(0, terminalNavigations.get())
        node("linux-tab:basic").performClick()
        mainThread { oldDebian(); oldTerminal() }
        node("linux-distribution-choice").assertTextContains("Alpine")
        assertEquals(0, terminalNavigations.get())
        node("linux-open-terminal").performClick()
        assertEquals(1, terminalNavigations.get())
        assertFalse(checkNotNull(owner).state.value.revoked)
        assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
    }
    fun testApprovedRootfsListsRealDirectoriesReadOnlyAndReturnsToParent() {
        val root = java.io.File(instrumentation.targetContext.filesDir.canonicalFile, "rootfs-layout-${java.util.UUID.randomUUID()}").apply { mkdir() }
        val child = java.io.File(root, "etc").apply { mkdir() }
        java.io.File(child, "config").writeText("keep")
        try {
            show(listOf(LinuxInstallation("alpine", "fixture", "one", root)))
            node("linux-tab:files").performClick()
            revealLinux("linux-files:rootfs").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("linux-rootfs-entry:etc").fetchSemanticsNodes().size == 1 }
            revealLinux("linux-rootfs-entry:etc").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("linux-rootfs-entry:etc/config").fetchSemanticsNodes().size == 1 }
            node("workspace-private-import").assertDoesNotExist()
            revealLinux("linux-rootfs-entry:etc/config").assertHasNoClickAction()
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
        show()
        node("linux-tab:files").performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("workspace-private-import").fetchSemanticsNodes()
                .singleOrNull()?.config?.contains(SemanticsActions.OnClick) == true &&
                ui.onAllNodesWithTag("workspace-private-import").fetchSemanticsNodes()
                    .none { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        }
        val oldImport = checkNotNull(node("workspace-private-import").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val oldRefresh = checkNotNull(node("workspace-private-refresh").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        revealLinux("linux-files:rootfs").performClick()
        ui.onNodeWithText("Rootfs 仅供只读浏览，不跟随符号链接，不导入或删除环境文件。").assertDoesNotExist()
        mainThread { oldImport(); oldRefresh() }
        ui.onNodeWithText("当前环境暂无可浏览的 Rootfs").assertIsDisplayed()
        node("workspace-private-import").assertDoesNotExist()
        revealLinux("linux-files:workspace").performClick()
        mainThread { oldImport(); oldRefresh() }
        node("workspace-private-import").assertIsDisplayed()
        node("linux-tab:files").assertIsDisplayed()
        assertFalse(checkNotNull(owner).state.value.revoked)
        assertEquals(0, writes.get()); assertEquals(0, fixture.opens.get())
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
        try { settings?.close() } finally { super.tearDown() }
    }
}
