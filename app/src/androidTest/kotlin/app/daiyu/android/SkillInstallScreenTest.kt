package app.daiyu.android

import app.daiyu.android.ui.screens.settings.RetainedSkillRemovalCancel

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.icons.SkillIcons
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.skills.SkillInstallFixture
import app.daiyu.android.skills.SkillManagement
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.SkillManagementScreen
import app.daiyu.android.ui.screens.settings.SkillManagementPageState
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Compose screen, but isolated stores and injected GitHub responses only. */
@Suppress("DEPRECATION")
class SkillInstallScreenTest : WiredActivityTestCase() {
    private lateinit var skills: SkillInstallFixture
    private val visible = mutableStateOf(true)

    override fun setUp() {
        super.setUp()
        skills = SkillInstallFixture(instrumentation.targetContext)
    }

    override fun tearDown() {
        try { super.tearDown() } finally { skills.close() }
    }

    private fun show(engineeringUi: Boolean = true) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    if (visible.value) SkillManagementScreen(
                        engineeringUi = engineeringUi,
                        managementFactory = { skills.manager }, onBack = { visible.value = false })
                }
            }
        }
        ready(if (engineeringUi) "skills-global" else "skills-import-zip")
    }

    private fun ready(tag: String) {
        if (tag == "skills-global" || tag == "skills-import-zip") reveal(tag, "技能")
        ui.waitUntil(timeoutMillis = 10_000) {
            ui.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.config?.let {
                it.getOrNull(SemanticsProperties.Disabled) == null
            } == true
        }
    }

    private fun inspect() {
        reveal("skills-install-url", "技能").performTextReplacement("https://github.com/owner/repo")
        mainThread {
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        reveal("skills-inspect", "技能").performClick()
    }

    private fun preview() {
        inspect()
        ready("skills-directory:demo")
        node("skills-directory:demo").performClick()
        node("skills-preview").performClick()
        ready("skills-confirm-install")
    }

    fun testCreateOnlyInstallNeedsPreviewAndShowsCommittedResult() {
        show()
        preview()
        node("skills-compatibility:demo").assertTextContains("兼容性静态检查通过", substring = true)
        assertTrue(skills.user.list().isEmpty())
        node("skills-confirm-install").performClick()
        ui.waitUntil(timeoutMillis = 10_000) {
            ui.onAllNodesWithText("安装成功", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(skills.commit, skills.user.list().single().commit)
        ui.onNodeWithText("安装成功", substring = true).assertExists()
        node("skills-confirm-install").assertDoesNotExist()
    }

    fun testRemovalConfirmationCancelAndReopenPersistThroughRealScreen() {
        val old = skills.user.install(listOf(skills.oldBundle())).single()
        show()
        reveal("skill-row:user:demo", "技能").performClick()
        node("skill-remove:user:demo").performClick()
        ready("skills-confirm-remove")
        assertEquals(old, skills.user.list().single())
        ui.onNodeWithText("取消").performClick()
        node("skills-confirm-remove").assertDoesNotExist()
        assertEquals(old, skills.user.list().single())
        reveal("skill-row:user:demo", "技能").performClick()
        node("skill-remove:user:demo").performClick()
        ready("skills-confirm-remove")
        node("skills-confirm-remove").performClick()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithText("技能已删除", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(skills.user.list().isEmpty())
        node("skill-row:user:demo").assertDoesNotExist()
        mainThread { visible.value = false }
        ui.waitForIdle()
        mainThread { visible.value = true }
        ready("skills-global")
        node("skill-row:user:demo").assertDoesNotExist()
        assertTrue(skills.manager.snapshot().entries.isEmpty())
    }

    fun testProductMenuDescriptionAndRetainedRemovalCancel() {
        val old = skills.user.install(listOf(skills.oldBundle())).single()
        show(engineeringUi = false)
        node("skills-global").assertDoesNotExist()
        reveal("skill-menu:user:demo", "技能").performClick()
        ui.onNodeWithText("查看描述").performClick()
        node("skill-remove:user:demo").assertDoesNotExist()
        ui.onNodeWithText("关闭").performClick()
        fun openRemoval() {
            reveal("skill-menu:user:demo", "技能").performClick()
            ui.onNodeWithText("删除", useUnmergedTree = true).performClick()
            ready("skills-confirm-remove")
        }
        openRemoval()
        val oldCancel = ui.onNodeWithText("取消").fetchSemanticsNode().config[RetainedSkillRemovalCancel]
        ui.onNodeWithText("取消").performClick()
        openRemoval()
        mainThread { oldCancel() }
        node("skills-confirm-remove").assertExists()
        assertEquals(old, skills.user.list().single())
        ui.onNodeWithText("取消").performClick()
        node("skills-confirm-remove").assertDoesNotExist()
    }

    fun testProductImportCardWholeRowLaunchesPickerOnceAndCancelDoesNotInstall() {
        val picker = LocalZipPicker()
        mainThread { activity.setContent {
            CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides picker) {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    SkillManagementScreen(managementFactory = { skills.manager }, onBack = {})
                }
            }
        } }
        ready("skills-import-zip")
        node("skills-global").assertDoesNotExist()
        ui.onNodeWithText("从 ZIP 导入").assertExists()
        ui.onNodeWithText("选择包含 SKILL.md 的 Skill 包").assertExists()
        reveal("skills-import-zip", "技能").assertHasClickAction().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width - 4f, height / 2f))
        }
        ui.runOnIdle { assertEquals(1, picker.launchCount) }
        mainThread { picker.result(null) }
        ready("skills-import-zip")
        node("skills-confirm-local-install").assertDoesNotExist()
        assertTrue(skills.user.list().isEmpty())
        assertTrue(skills.calls.isEmpty())
    }

    fun testProductGroupedListAndMenuFitNarrowBothThemesAndLargeTextWithoutChangingInstalledEntries() {
        val description = "用于验证技能列表的长中文说明与完整阅读，查看和关闭都不应改变已安装内容。".repeat(8)
        val bundle = app.daiyu.android.skills.SkillBundle(mapOf("SKILL.md" to
            "---\nname: demo\ndescription: $description\n---\nRead-only layout fixture\n".toByteArray()))
        val original = skills.user.install(listOf(bundle)).single()
        val builtin = skills.builtin.install(listOf(bundle)).single()
        var appDensitySize = 1f
        var systemDensitySize = 1f
        fun rawBounds(tag: String): androidx.compose.ui.geometry.Rect {
            val semantics = node(tag).fetchSemanticsNode()
            val origin = semantics.positionInRoot
            val size = semantics.size
            return androidx.compose.ui.geometry.Rect(origin.x, origin.y,
                origin.x + size.width, origin.y + size.height)
        }
        fun assertUntruncated(tag: String) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertFalse(layouts.single().hasVisualOverflow)
        }
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            mainThread { activity.setContent {
                val density = LocalDensity.current
                systemDensitySize = density.density
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("skill-layout-host")) {
                        AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                            interfaceScale = scale), applyInterfaceScale = true) {
                            appDensitySize = LocalDensity.current.density
                            SkillManagementScreen(managementFactory = { skills.manager }, onBack = {})
                        }
                    }
                }
            } }
            ready("skills-import-zip")
            val host = rawBounds("skill-layout-host")
            assertEquals(320f * systemDensitySize, host.width, 1f)
            reveal("skill-row:builtin:demo", "技能").assertIsDisplayed()
            ui.onNodeWithText("内置").assertExists()
            node("skill-menu:builtin:demo").performClick()
            ui.onNodeWithText("删除", useUnmergedTree = true).assertDoesNotExist()
            ui.onNodeWithText("查看描述").performClick()
            node("skill-description:builtin:demo").assertTextEquals(description)
            assertUntruncated("skill-description:builtin:demo")
            node("skills-details-close").performClick()
            reveal("skill-row:user:demo", "技能").assertIsDisplayed()
            val row = rawBounds("skill-row:user:demo")
            ui.onNodeWithText("用户安装").assertExists()
            node("skill-menu:user:demo").assertIsDisplayed()
            val menu = rawBounds("skill-menu:user:demo")
            val icon = rawBounds("skill-icon:user:demo")
            assertTrue(row.left >= host.left && row.right <= host.right)
            // Touch targets remain 48 application dp under the user's existing interface scale.
            assertTrue(menu.width >= 48f * appDensitySize - 1f && menu.height >= 48f * appDensitySize - 1f)
            assertTrue(icon.right < menu.left && menu.right <= row.right)
            node("skill-menu:user:demo").performClick()
            ui.onNodeWithText("查看描述").performClick()
            node("skill-description:user:demo").assertTextEquals(original.description)
            assertUntruncated("skill-description:user:demo")
            node("skills-details-close").assertIsDisplayed()
            val close = rawBounds("skills-details-close")
            assertTrue("close target: dark=$dark scale=$scale density=$appDensitySize host=$host close=$close",
                close.height >= 48f * appDensitySize - 1f)
            node("skills-details-close").performClick()
            node("skill-description:user:demo").assertDoesNotExist()
            assertEquals(original, skills.user.list().single())
            assertEquals(builtin, skills.builtin.list().single())
            assertTrue(skills.calls.isEmpty())
        }
    }

    fun testSkillMarksUseDistinctOriginalOutlineFamily() {
        val marks = listOf(DaiyuIcons.Skills, SkillIcons.Improve, SkillIcons.Create, SkillIcons.Import)
        assertEquals(4, marks.map { it.name }.toSet().size)
        marks.forEach { mark ->
            assertTrue(mark.name.startsWith("Daiyu."))
            assertEquals(24f, mark.viewportWidth, 0f)
            assertEquals(24f, mark.viewportHeight, 0f)
        }
    }

    fun testBuiltinDetailsDoNotOfferUserRemoval() {
        val old = skills.builtin.install(listOf(skills.oldBundle())).single()
        show()
        reveal("skill-row:builtin:demo", "技能").performClick()
        node("skill-remove:builtin:demo").assertDoesNotExist()
        assertEquals(old, skills.builtin.list().single())
    }

    fun testIncompatiblePreviewShowsOriginalReasonAndDisablesConfirmation() {
        skills.content = "---\nname: demo\ndescription: Install test\nmetadata:\n  runtime: android-a11y-cli\n---\nPortable-looking body\n".toByteArray()
        show()
        inspect()
        ready("skills-directory:demo")
        node("skills-directory:demo").performClick()
        node("skills-preview").performClick()
        ui.waitUntil(timeoutMillis = 10_000) {
            ui.onAllNodesWithTag("skills-compatibility:demo").fetchSemanticsNodes().isNotEmpty()
        }
        node("skills-compatibility:demo").assertTextEquals("不兼容：依赖 MiniS 专属 Android CLI，代鱼无法运行")
        node("skills-confirm-install").assertIsNotEnabled()
        assertTrue(skills.user.list().isEmpty())
    }

    fun testReplacementRequiresExplicitCheckboxAndCancelDoesNotInstall() {
        val old = skills.user.install(listOf(skills.oldBundle())).single()
        show()
        inspect()
        ready("skills-directory:demo")
        node("skills-directory:demo").performClick()
        node("skills-preview").performClick()
        ui.waitUntil(timeoutMillis = 10_000) {
            ui.onAllNodesWithTag("skills-replace:demo").fetchSemanticsNodes().isNotEmpty()
        }
        node("skills-confirm-install").assertIsNotEnabled()
        node("skills-replace:demo").scrollToVisible().performClick()
        node("skills-confirm-install").assertIsEnabled()
        ui.onNodeWithText("取消").performClick()
        node("skills-confirm-install").assertDoesNotExist()
        assertEquals(old, skills.user.list().single())
    }

    fun testLeavingPageAfterPreviewDoesNotInstall() {
        show()
        preview()
        mainThread { visible.value = false }
        node("skills-confirm-install").assertDoesNotExist()
        assertTrue(skills.user.list().isEmpty())
    }

    // The same page controller as Compose, with explicit worker-start and UI-delivery
    // boundaries. No sleeps, network, app stores or executor scheduling assumptions.
    private fun useFixtureBuiltins() {
        skills.close()
        skills = SkillInstallFixture(instrumentation.targetContext, fixtureBuiltins = true)
    }

    fun testBuiltinReinstallNeedsConfirmationCancelDoesNotWriteAndKeepsGlobalOff() {
        useFixtureBuiltins()
        val original = skills.manager.snapshot().entries.single { it.id == "builtin:builtin-demo" }
        skills.manager.setEnabled(original.id, false, original.skill.revision)
        skills.manager.setGlobalEnabled(false)
        show()
        reveal("skill-row:${original.id}", "技能").performClick()
        node("skill-reinstall:${original.id}").performClick()
        node("skills-confirm-reinstall").assertIsEnabled()
        ui.onNodeWithText("取消").performClick()
        assertFalse(skills.builtin.list().single { it.name == "builtin-demo" }.enabled)
        reveal("skill-row:${original.id}", "技能").performClick()
        node("skill-reinstall:${original.id}").performClick()
        node("skills-confirm-reinstall").performClick()
        ui.waitUntil(10_000) { skills.builtin.list().single { it.name == "builtin-demo" }.enabled }
        ready("skills-global")
        node("skills-global").assertIsOff()
        assertFalse(skills.registry.enabled)
        assertTrue(skills.calls.isEmpty())
    }

    private fun ControlledPage.prepareBuiltinReinstall(): SkillManagementPageState.BuiltinReinstall {
        mainThread { controller.refresh() }
        finishNext()
        var entry: SkillManagement.Entry? = null
        mainThread {
            entry = controller.state!!.entries.single { it.id == "builtin:builtin-demo" }
            controller.prepareReinstall(checkNotNull(entry))
        }
        worker.drain(); deliver()
        var approved: SkillManagementPageState.BuiltinReinstall? = null
        mainThread { approved = controller.reinstall }
        return checkNotNull(approved)
    }

    fun testBuiltinReinstallCanceledConfirmationCannotBeReusedAndDuplicateWritesOnce() {
        useFixtureBuiltins()
        val page = ControlledPage()
        try {
            val canceled = page.prepareBuiltinReinstall()
            mainThread { page.controller.cancelReinstall(); page.controller.reinstall(canceled) }
            assertEquals(0, page.worker.pending)
            var writes = 0
            skills.builtin.transactionCheckpoint = { if (it == "before_journal") writes++ }
            val approved = page.prepareBuiltinReinstall()
            mainThread { page.controller.reinstall(approved); page.controller.reinstall(approved) }
            assertEquals(1, page.worker.pending)
            page.finishNext()
            assertEquals(1, writes)
            mainThread { page.controller.reinstall(approved); assertNotNull(page.controller.installMessage) }
            assertEquals(0, page.worker.pending)
        } finally { skills.builtin.transactionCheckpoint = {}; page.close() }
    }

    fun testLeavingRejectsQueuedBuiltinReinstallAndLateConfirmation() {
        useFixtureBuiltins()
        val page = ControlledPage()
        val approved = page.prepareBuiltinReinstall()
        var writes = 0
        skills.builtin.transactionCheckpoint = { if (it == "before_journal") writes++ }
        mainThread { page.controller.reinstall(approved); page.controller.dispose(); page.controller.reinstall(approved) }
        page.worker.drain(); page.deliver()
        assertEquals(0, writes)
        mainThread { assertNull(page.controller.installMessage) }
        skills.builtin.transactionCheckpoint = {}
    }

    fun testAdmittedBuiltinReinstallFinishesAfterLeaveWithoutLatePageUpdate() {
        useFixtureBuiltins()
        val page = ControlledPage()
        val approved = page.prepareBuiltinReinstall()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var writes = 0
        skills.builtin.transactionCheckpoint = { boundary ->
            if (boundary == "before_journal") { writes++; entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        }
        mainThread { page.controller.reinstall(approved) }
        val thread = Thread { try { page.worker.runNext() } catch (problem: Throwable) { failure.set(problem) } }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            mainThread { page.controller.dispose(); page.controller.reinstall(approved) }
        } finally { release.countDown(); thread.join(5_000); skills.builtin.transactionCheckpoint = {} }
        assertFalse(thread.isAlive)
        failure.get()?.let { throw it }
        page.worker.drain(); page.deliver()
        assertEquals(1, writes)
        assertEquals(approved.entry.skill.revision, skills.builtin.list().single { it.name == "builtin-demo" }.revision)
        assertTrue(skills.builtin.list().single { it.name == "builtin-demo" }.enabled)
        mainThread { assertNull(page.controller.installMessage) }
    }

    fun testBuiltinReinstallUnknownConsumesConfirmationAndRefreshNeverReplays() {
        useFixtureBuiltins()
        val page = ControlledPage()
        try {
            val approved = page.prepareBuiltinReinstall()
            var attempts = 0
            skills.builtin.transactionCheckpoint = { if (it == "before_journal") { attempts++; error("unavailable") } }
            mainThread { page.controller.reinstall(approved) }
            page.finishNext()
            mainThread {
                assertTrue(page.controller.installMessage!!.contains("结果未知"))
                assertNull(page.controller.reinstall)
                page.controller.reinstall(approved)
                page.controller.refresh()
            }
            page.finishNext()
            assertEquals(1, attempts)
        } finally { skills.builtin.transactionCheckpoint = {}; page.close() }
    }

    fun testBuiltinReinstallOldPageTokenCannotBorrowFreshPageConfirmation() {
        useFixtureBuiltins()
        val old = ControlledPage()
        val stale = old.prepareBuiltinReinstall()
        old.close()
        val fresh = ControlledPage()
        try {
            val current = fresh.prepareBuiltinReinstall()
            mainThread { fresh.controller.reinstall(stale); assertSame(current, fresh.controller.reinstall) }
            assertEquals(0, fresh.worker.pending)
            mainThread { fresh.controller.reinstall(current) }
            fresh.finishNext()
            mainThread { assertTrue(fresh.controller.installMessage!!.contains("重装成功")) }
        } finally { fresh.close() }
    }

    fun testDamagedListKeepsGlobalToggleButRejectsRevisionSensitiveActions() {
        val root = java.io.File(instrumentation.targetContext.cacheDir, "damaged-skills-${java.util.UUID.randomUUID()}")
        val prefs = "damaged-skills-${java.util.UUID.randomUUID()}"
        val context = object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefs-$name", mode)
        }
        val blocked = java.io.File(root, "agent-builtin-skills-v1/installed")
        check(blocked.parentFile!!.mkdirs()); blocked.writeText("not a directory")
        val registry = app.daiyu.android.skills.SkillToolRegistry(context)
        val page = ControlledPage { SkillManagement.create { registry } }
        try {
            mainThread { page.controller.refresh() }; page.finishNext()
            val entry = SkillManagement.Entry("user:demo",
                app.daiyu.android.skills.InstalledSkill("demo", "demo", "old", true, "user", null), false)
            mainThread {
                assertTrue(page.controller.state!!.entriesUnavailable)
                assertTrue(page.controller.globalConfirmed); assertFalse(page.controller.confirmed)
                page.controller.setEnabled(entry, false); page.controller.prepareRemoval(entry)
                assertEquals(0, page.worker.pending)
                page.controller.setGlobalEnabled(false)
            }
            page.finishNext()
            mainThread {
                assertFalse(page.controller.state!!.enabled)
                assertTrue(page.controller.globalConfirmed); assertFalse(page.controller.confirmed)
            }
            assertFalse(app.daiyu.android.skills.SkillToolRegistry(context).enabled)
            assertEquals("not a directory", blocked.readText())
        } finally {
            page.close(); root.deleteRecursively()
            instrumentation.targetContext.deleteSharedPreferences("$prefs-runtime-skills-v1-settings")
        }
    }

    private class QueuedWorker : AbstractExecutorService() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        @Volatile private var stopped = false
        val pending: Int get() = tasks.size
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks.add(command)
        }
        fun runNext() = checkNotNull(tasks.poll()) { "missing worker task" }.run()
        fun drain() { while (tasks.isNotEmpty()) runNext() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return mutableListOf<Runnable>().also { tasks.drainTo(it) }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated()
    }

    private inner class ControlledPage(managementFactory: () -> SkillManagement = { skills.manager }) {
        val worker = QueuedWorker()
        private val callbacks = LinkedBlockingQueue<() -> Unit>()
        lateinit var controller: SkillManagementPageState
        init {
            mainThread {
                controller = SkillManagementPageState(
                    managementFactory = managementFactory, worker = worker,
                    postToMain = { callbacks.add(it); Unit },
                )
            }
        }
        fun deliver() = mainThread { while (callbacks.isNotEmpty()) callbacks.remove().invoke() }
        fun finishNext() { worker.runNext(); deliver() }
        fun prepare(): SkillManagement.Preview {
            mainThread {
                controller.url = "https://github.com/owner/repo"
                controller.inspect()
            }
            finishNext()
            mainThread { controller.select(skills.directory, true); controller.prepareInstall() }
            finishNext()
            var preview: SkillManagement.Preview? = null
            mainThread { preview = controller.preview }
            return checkNotNull(preview)
        }
        fun close() {
            mainThread { controller.dispose() }
            worker.drain()
            deliver()
        }
    }

    private fun ControlledPage.prepareRemoval(): SkillManagement.Removal {
        mainThread { controller.refresh() }
        finishNext()
        var entry: SkillManagement.Entry? = null
        mainThread { entry = controller.state!!.entries.single { it.id == "user:demo" } }
        mainThread { controller.prepareRemoval(checkNotNull(entry)) }
        finishNext()
        var removal: SkillManagement.Removal? = null
        mainThread { removal = controller.removal }
        return checkNotNull(removal)
    }

    private class LocalZipPicker : androidx.activity.result.ActivityResultRegistry(), androidx.activity.result.ActivityResultRegistryOwner {
        override val activityResultRegistry: androidx.activity.result.ActivityResultRegistry get() = this
        var launched: Int? = null
        var launchCount = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
            input: I, options: androidx.core.app.ActivityOptionsCompat?) {
            check(contract is androidx.activity.result.contract.ActivityResultContracts.OpenDocument)
            check((input as Array<*>).toList() == listOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            launched = requestCode
            launchCount++
        }
        fun result(uri: android.net.Uri?) {
            dispatchResult(checkNotNull(launched), if (uri == null) android.app.Activity.RESULT_CANCELED else android.app.Activity.RESULT_OK,
                uri?.let { android.content.Intent().setData(it) })
        }
    }

    fun testLocalOpenDocumentCancelAndRealProviderImportThroughScreen() {
        val file = java.io.File(instrumentation.targetContext.cacheDir, "local-skill-${java.util.UUID.randomUUID()}.zip")
        file.writeBytes(localZip())
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: android.net.Uri) = "application/zip"
            override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                check(mode == "r"); opens.incrementAndGet()
                return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): android.database.Cursor? = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = error("unused")
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = error("unused")
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = error("unused")
        }
        provider.attachInfo(instrumentation.targetContext, android.content.pm.ProviderInfo().apply {
            authority = "local-skill-test"; exported = false
            applicationInfo = instrumentation.targetContext.applicationInfo
        })
        val resolver = android.test.mock.MockContentResolver(instrumentation.targetContext).apply { addProvider("local-skill-test", provider) }
        val context = object : android.content.ContextWrapper(activity) { override fun getContentResolver() = resolver }
        val picker = LocalZipPicker()
        try {
            mainThread { activity.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides context,
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides picker,
                ) { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    SkillManagementScreen(managementFactory = { skills.manager }, onBack = {}, engineeringUi = true)
                } }
            } }
            ready("skills-import-zip")
            reveal("skills-import-zip", "技能").performClick()
            mainThread { picker.result(null) }
            ready("skills-import-zip")
            assertEquals(0, opens.get()); assertTrue(skills.user.list().isEmpty())
            reveal("skills-import-zip", "技能").performClick()
            mainThread { picker.result(android.net.Uri.parse("content://local-skill-test/package")) }
            ready("skills-confirm-local-install")
            assertEquals(1, opens.get()); assertTrue(skills.user.list().isEmpty())
            file.writeBytes(byteArrayOf(0)) // confirmation must use already frozen bytes
            node("skills-confirm-local-install").performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText("安装成功", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, opens.get()); assertEquals(1, skills.user.list().size)
            assertNull(skills.user.list().single().commit); assertTrue(skills.calls.isEmpty())
        } finally { file.delete() }
    }

    private fun localZip(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { out -> skills.oldBundle().files.forEach { (path, data) ->
            out.putNextEntry(java.util.zip.ZipEntry(path)); out.write(data); out.closeEntry()
        } }
        return bytes.toByteArray()
    }

    private fun ControlledPage.prepareLocal(): SkillManagement.LocalPreview {
        var request: SkillManagementPageState.LocalPick? = null
        mainThread { request = controller.beginLocalPick() }
        worker.drain(); deliver()
        mainThread { controller.receiveLocalPick(checkNotNull(request)) { localZip().inputStream() } }
        finishNext()
        var preview: SkillManagement.LocalPreview? = null
        mainThread { preview = controller.localPreview }
        return checkNotNull(preview)
    }

    fun testLocalPickerCanceledDuplicateAndLateResultNeverReadDocument() {
        val page = ControlledPage()
        var request: SkillManagementPageState.LocalPick? = null
        var reads = 0
        try {
            mainThread { request = page.controller.beginLocalPick(); assertTrue(page.controller.busy) }
            page.worker.drain(); page.deliver()
            mainThread {
                page.controller.receiveLocalPick(checkNotNull(request), null)
                page.controller.receiveLocalPick(checkNotNull(request)) { reads++; localZip().inputStream() }
                assertFalse(page.controller.busy); assertNull(page.controller.localPreview)
                request = page.controller.beginLocalPick()
                page.controller.dispose()
                page.controller.receiveLocalPick(checkNotNull(request)) { reads++; localZip().inputStream() }
            }
            page.worker.drain(); page.deliver()
            assertEquals(0, reads)
            assertTrue(skills.user.list().isEmpty())
        } finally { page.close() }
    }

    fun testLocalQueuedInstallIsRevokedOnLeaveAndFreshPageCanInstallOnce() {
        val old = ControlledPage()
        val preview = old.prepareLocal()
        mainThread { old.controller.installLocal(preview, preview.replacements); old.controller.dispose() }
        old.worker.drain(); old.deliver()
        assertTrue(skills.user.list().isEmpty())
        mainThread { assertNull(old.controller.installMessage) }
        val fresh = ControlledPage()
        try {
            val next = fresh.prepareLocal()
            mainThread {
                fresh.controller.installLocal(preview, preview.replacements)
                assertFalse(fresh.controller.busy)
                fresh.controller.installLocal(next, next.replacements)
                fresh.controller.installLocal(next, next.replacements)
                assertEquals(1, fresh.worker.pending)
            }
            fresh.finishNext()
            assertEquals(next.change.candidate.incomingRevision, skills.user.list().single().revision)
            assertTrue(skills.calls.isEmpty())
            mainThread { assertNotNull(fresh.controller.installMessage); assertNull(fresh.controller.localPreview) }
        } finally { fresh.close() }
    }

    fun testLocalAdmittedInstallFinishesAfterLeaveWithoutUpdatingOldPage() {
        val page = ControlledPage()
        val preview = page.prepareLocal()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        skills.user.transactionCheckpoint = { boundary -> if (boundary == "after_publish") {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
        } }
        mainThread { page.controller.installLocal(preview, preview.replacements) }
        val thread = Thread { try { page.worker.runNext() } catch (problem: Throwable) { failure.set(problem) } }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            mainThread { page.controller.dispose() }
            release.countDown(); thread.join(5000); assertFalse(thread.isAlive)
            failure.get()?.let { throw AssertionError("local install failed", it) }
            page.worker.drain(); page.deliver()
            assertEquals(preview.change.candidate.incomingRevision, skills.user.list().single().revision)
            mainThread { assertNull(page.controller.installMessage); assertNull(page.controller.localPreview) }
        } finally { release.countDown(); thread.join(5000); page.close() }
    }

    fun testCanceledRemovalCannotReuseOldConfirmationAndDuplicateRunsOnce() {
        val old = skills.user.install(listOf(skills.oldBundle())).single()
        val page = ControlledPage()
        try {
            val canceled = page.prepareRemoval()
            mainThread { page.controller.cancelRemoval(); page.controller.remove(canceled) }
            page.worker.drain(); page.deliver()
            assertEquals(old, skills.user.list().single())
            val approved = page.prepareRemoval()
            mainThread {
                page.controller.remove(approved); page.controller.remove(approved)
                assertEquals(1, page.worker.pending)
            }
            page.finishNext()
            assertTrue(skills.user.list().isEmpty())
            mainThread { page.controller.remove(approved); assertNotNull(page.controller.installMessage) }
            assertEquals(0, page.worker.pending)
        } finally { page.close() }
    }

    fun testLeavingRejectsQueuedRemovalBeforeAdmission() {
        val old = skills.user.install(listOf(skills.oldBundle())).single()
        val page = ControlledPage()
        val approved = page.prepareRemoval()
        mainThread { page.controller.remove(approved); page.controller.dispose() }
        page.worker.drain(); page.deliver()
        assertEquals(old, skills.user.list().single())
        mainThread { assertNull(page.controller.installMessage) }
    }

    fun testAdmittedRemovalFinishesOnceAfterLeaveWithoutLatePageUpdate() {
        skills.user.install(listOf(skills.oldBundle()))
        val page = ControlledPage()
        val approved = page.prepareRemoval()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        skills.user.transactionCheckpoint = { boundary ->
            if (boundary == "after_backup") {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            }
        }
        mainThread { page.controller.remove(approved) }
        val thread = Thread {
            try { page.worker.runNext() } catch (problem: Throwable) { failure.set(problem) }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            mainThread { page.controller.dispose(); page.controller.remove(approved) }
        } finally { release.countDown(); thread.join(5_000); skills.user.transactionCheckpoint = {} }
        assertFalse(thread.isAlive)
        failure.get()?.let { throw it }
        page.worker.drain(); page.deliver()
        assertTrue(skills.user.list().isEmpty())
        mainThread { assertNull(page.controller.installMessage) }
    }

    fun testLateInspectionAfterLeavingCannotPopulateEitherPage() {
        val old = ControlledPage()
        mainThread {
            old.controller.url = "https://github.com/owner/repo"
            old.controller.inspect()
        }
        old.worker.runNext() // completed inspection, but its UI callback is held
        mainThread { old.controller.dispose() }
        old.worker.drain()
        val fresh = ControlledPage()
        try {
            val freshPreview = fresh.prepare()
            val calls = skills.calls.size
            old.deliver() // delivery after a different page already has its own preview
            mainThread {
                assertNull(old.controller.inspection)
                assertNull(old.controller.preview)
                assertNull(old.controller.installMessage)
                assertSame(freshPreview, fresh.controller.preview)
                old.controller.prepareInstall()
                old.controller.inspect()
            }
            assertEquals(0, old.worker.pending)
            assertEquals(calls, skills.calls.size)
            assertTrue(skills.user.list().isEmpty())
        } finally { fresh.close() }
    }

    fun testCanceledInspectionCallbackCannotClearNewInspectionBusyState() {
        val page = ControlledPage()
        try {
            mainThread {
                page.controller.url = "https://github.com/owner/repo"
                page.controller.inspect()
            }
            page.worker.runNext() // hold the first inspection's callback
            mainThread {
                page.controller.cancelInstall()
                page.controller.inspect()
                assertTrue(page.controller.busy)
            }
            page.worker.runNext() // ordered manager approval cleanup
            page.deliver() // stale generation must not clear the new operation's busy flag
            mainThread {
                assertTrue(page.controller.busy)
                assertNull(page.controller.inspection)
            }
            page.finishNext()
            mainThread {
                assertFalse(page.controller.busy)
                assertNotNull(page.controller.inspection)
            }
            assertTrue(skills.user.list().isEmpty())
        } finally { page.close() }
    }

    fun testOldConfirmationCallbackCannotBorrowNewPageOrPreview() {
        val old = ControlledPage()
        val oldPreview = old.prepare()
        val lateConfirm = { old.controller.install(oldPreview, oldPreview.replacements) }
        old.close()
        val fresh = ControlledPage()
        try {
            val freshPreview = fresh.prepare()
            val calls = skills.calls.size
            mainThread {
                lateConfirm()
                fresh.controller.install(oldPreview, oldPreview.replacements)
                assertSame(freshPreview, fresh.controller.preview)
                assertFalse(fresh.controller.busy)
                assertFalse(fresh.controller.installing)
            }
            assertEquals(0, old.worker.pending)
            assertEquals(0, fresh.worker.pending)
            assertEquals(calls, skills.calls.size)
            assertTrue(skills.user.list().isEmpty())
            mainThread { fresh.controller.install(freshPreview, freshPreview.replacements) }
            fresh.finishNext()
            assertEquals(skills.commit, skills.user.list().single().commit)
        } finally { fresh.close() }
    }

    fun testLeavingRejectsQueuedButNotYetAdmittedInstallWithoutDownloadOrWrite() {
        val old = ControlledPage()
        val preview = old.prepare()
        val calls = skills.calls.size
        mainThread {
            old.controller.install(preview, preview.replacements)
            old.controller.install(preview, preview.replacements)
            assertTrue(old.controller.installing) // submitted is deliberately not admitted
            assertEquals(1, old.worker.pending)
            old.controller.dispose() // revoke BEFORE the queued runnable actually starts
            old.controller.dispose() // terminal/idempotent, never submits extra cleanup
        }
        old.worker.runNext() // the install must fail its admission CAS
        assertEquals(calls, skills.calls.size)
        assertTrue(skills.user.list().isEmpty())
        old.worker.drain()
        old.deliver()
        mainThread { assertNull(old.controller.installMessage) }
        assertEquals(calls, skills.calls.size)
        assertTrue(skills.user.list().isEmpty())
    }

    fun testAdmittedInstallFinishesAtMostOnceAcrossLeaveWithoutUpdatingNewPage() {
        val old = ControlledPage()
        val preview = old.prepare()
        val calls = skills.calls.size
        val enteredDownload = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        skills.fetchHook = {
            enteredDownload.countDown() // first fetch proves worker admission already won
            check(releaseDownload.await(10, TimeUnit.SECONDS)) { "test_download_timeout" }
        }
        mainThread {
            old.controller.install(preview, preview.replacements)
            old.controller.install(preview, preview.replacements)
        }
        val thread = Thread {
            try { old.worker.runNext() } catch (problem: Throwable) { failure.set(problem) }
            finally { finished.countDown() }
        }.apply { start() }
        var fresh: ControlledPage? = null
        try {
            assertTrue(enteredDownload.await(5, TimeUnit.SECONDS))
            // Keep the fake download blocked until dispose returns: disposal must not
            // acquire SkillManagement's network/storage lock or wait for this worker.
            mainThread { old.controller.dispose() }
            val newPage = ControlledPage().also { fresh = it }
            mainThread {
                old.controller.install(preview, preview.replacements)
                newPage.controller.install(preview, preview.replacements)
                assertNull(newPage.controller.state)
                assertNull(newPage.controller.installMessage)
            }
            releaseDownload.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            thread.join(1000)
            failure.get()?.let { throw AssertionError("worker failed", it) }
            old.worker.drain()
            old.deliver() // admitted result delivered after the new page exists
            mainThread {
                assertNull(old.controller.installMessage)
                assertNull(old.controller.state)
                assertNull(newPage.controller.state)
                assertNull(newPage.controller.installMessage)
                assertFalse(newPage.controller.busy)
            }
            assertEquals(calls + 2, skills.calls.size)
            assertEquals(skills.commit, skills.user.list().single().commit)
            assertEquals(preview.changes.single().candidate.incomingRevision, skills.user.list().single().revision)
            mainThread { newPage.controller.refresh() }
            newPage.finishNext() // only an explicit fresh read populates the new page
            mainThread { assertEquals(1, newPage.controller.state!!.entries.size) }
            assertEquals(calls + 2, skills.calls.size)
        } finally {
            releaseDownload.countDown()
            thread.join(5000)
            skills.fetchHook = null
            old.close()
            fresh?.close()
        }
    }
}
