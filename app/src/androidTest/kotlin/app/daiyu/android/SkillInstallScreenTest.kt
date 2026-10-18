package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
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

    private fun show() {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    if (visible.value) SkillManagementScreen(
                        managementFactory = { skills.manager }, onBack = { visible.value = false })
                }
            }
        }
        ready("skills-global")
    }

    private fun ready(tag: String) {
        ui.waitUntil(timeoutMillis = 10_000) {
            ui.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.config?.let {
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
        node("skills-replace:demo").performScrollTo().performClick()
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

    private inner class ControlledPage {
        val worker = QueuedWorker()
        private val callbacks = LinkedBlockingQueue<() -> Unit>()
        lateinit var controller: SkillManagementPageState
        init {
            mainThread {
                controller = SkillManagementPageState(
                    managementFactory = { skills.manager }, worker = worker,
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
