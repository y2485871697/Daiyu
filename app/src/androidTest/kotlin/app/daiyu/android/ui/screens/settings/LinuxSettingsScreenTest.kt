package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import app.daiyu.android.runningmode.*
import androidx.compose.ui.test.*
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
    private fun show() {
        val catalog = object : LinuxSettingsCatalogAccess {
            override fun freeze(configuration: LinuxInstallation): FrozenLinuxApproval { writes.incrementAndGet(); error("no configuration writes") }
            override suspend fun load() = emptyList<LinuxInstallation>()
            override suspend fun approve(approval: FrozenLinuxApproval): List<LinuxInstallation> { writes.incrementAndGet(); error("no approval") }
            override suspend fun approveInstallation(approval: FrozenLinuxApproval, admit: () -> Boolean): List<LinuxInstallation> { writes.incrementAndGet(); error("no installation") }
            override suspend fun registrationRecorded(approval: FrozenLinuxApproval) = false
            override suspend fun remove(reference: LinuxGenerationRef): List<LinuxInstallation> { writes.incrementAndGet(); error("no removal") }
        }
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()))
        settings = controller
        val page = controller.enterPage()
        ui.waitUntil(5_000) { page.state.value.listConfirmed }
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    LinuxSettingsScreen(controller, page, onBack = {}, runningModeStore = modes)
                }
            }
        }
    }
    private fun revealLinux(tag: String): SemanticsNodeInteraction {
        node("page:Linux 工具环境").performScrollToNode(hasTestTag(tag))
        return node(tag)
    }
    fun testOriginalGroupsHaveNoTechnicalPreviewAndUnavailableToolsAreNotMarkedInstalled() {
        show()
        node("linux-distribution-choice").assertIsDisplayed()
        revealLinux("linux-execution-choice").assertIsDisplayed()
        revealLinux("linux-workspace").assertHasClickAction()
        revealLinux("linux-shared-folders").assertHasClickAction()
        revealLinux("linux-file-browser").assertHasClickAction()
        revealLinux("linux-tool-install:0").assertIsNotEnabled()
        for (text in listOf("预览安装（不下载）", "安装说明与诊断", "手工登记环境（高级）", "固定发行版 proot 安装（不自动注册）", "已安装")) {
            ui.onAllNodesWithText(text).assertCountEquals(0)
        }
        assertEquals(0, writes.get())
        assertEquals(0, fixture.opens.get())
    }
    fun testDistributionAndModePopupsOnlyChangeTheInstallChoice() {
        show()
        node("linux-distribution-choice").performClick()
        ui.onNodeWithText("软件兼容性更好的 glibc 环境").performClick()
        node("linux-distribution-choice").assertTextContains("Debian")
        modes.save(RunningMode.ROOT)
        ui.waitForIdle()
        revealLinux("linux-execution-choice").performClick()
        ui.onNodeWithText("使用 Root 与独立挂载空间，保留已有环境").performClick()
        revealLinux("linux-execution-choice").assertTextContains("Root 模式 · chroot")
        assertEquals(0, writes.get())
        assertEquals(0, fixture.opens.get())
    }
    fun testChrootChoiceIsOnlyVisibleInRootModeAndOldPopupCannotSelectIt() {
        show()
        for (mode in listOf(RunningMode.NORMAL, RunningMode.SHIZUKU)) {
            modes.save(mode); ui.waitForIdle()
            revealLinux("linux-execution-choice").performClick()
            ui.onNodeWithText("使用 Root 与独立挂载空间，保留已有环境").assertDoesNotExist()
            ui.onNodeWithText("无需 Root，使用独立的私有 Linux 环境").performClick()
        }
        modes.save(RunningMode.ROOT); ui.waitForIdle()
        revealLinux("linux-execution-choice").performClick()
        ui.onNodeWithText("使用 Root 与独立挂载空间，保留已有环境").assertExists()
        modes.save(RunningMode.NORMAL); ui.waitForIdle()
        ui.onNodeWithText("使用 Root 与独立挂载空间，保留已有环境").assertDoesNotExist()
        ui.onNodeWithText("无需 Root，使用独立的私有 Linux 环境").performClick()
        revealLinux("linux-execution-choice").assertTextContains("普通模式 · PRoot")
        assertEquals(0, writes.get())
        assertEquals(0, fixture.opens.get())
    }

    override fun tearDown() {
        try { settings?.close() } finally { super.tearDown() }
    }
}
