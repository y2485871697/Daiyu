package app.daiyu.android

import android.os.Build
import androidx.test.platform.graphics.HardwareRendererCompat
import androidx.compose.ui.test.*
import app.daiyu.android.storage.ProviderRegistry
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PageNavigationRegressionActivityTest : WiredActivityTestCase() {
    private var drawingWasEnabled = false

    override fun setUp() {
        super.setUp()
        drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
    }

    override fun tearDown() {
        try { super.tearDown() }
        finally { if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false) }
    }

    private fun awaitMotion(tag: String) {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
        }
    }

    private fun withDelayedProviderRead(block: (release: () -> Unit) -> Unit) {
        val repo = fixture.controller.providers
        val editor = repo.editor(ProviderRegistry.PRIMARY)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(name = "provider-animation-read-barrier") {
            try {
                repo.withEditor(editor) { store -> store.modelCatalog.readConfiguration {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "provider test read barrier timed out" }
                } }
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue("provider read lock acquired", entered.await(5, TimeUnit.SECONDS))
            block { release.countDown() }
        } finally {
            release.countDown()
            worker.join(5_000)
            assertFalse("provider read worker drained", worker.isAlive)
            failure.get()?.let { throw it }
        }
    }

    fun testProviderEntryAnimatesWhileItsRealReadIsPendingAndReadyDoesNotReplay() {
        if (Build.VERSION.SDK_INT < 28) return
        val before = fixture.controller.providers.snapshot()
        val settings = fixture.settings.load()
        openProviderManagement()
        captureScreenNode(node("page-back-surface"))
        val initial = node("page-back-surface").fetchSemanticsNode().boundsInRoot
        withDelayedProviderRead { release ->
            ui.mainClock.autoAdvance = false
            try {
                node("provider-open-primary").performTouchInput { click() }
                awaitMotion("page-enter-previous")
                ui.mainClock.advanceTimeBy(64)
                val incoming = node("page-back-surface").fetchSemanticsNode().boundsInRoot
                assertTrue("pending read has an intermediate entry frame", incoming.left > initial.left && incoming.left < initial.right)
                ui.onNodeWithText("正在读取提供商详情").assertExists()
                node("page-enter-scrim").assertExists()
                ui.mainClock.advanceTimeBy(1_000)
                release()
                ui.waitUntil(5_000) {
                    ui.mainClock.advanceTimeByFrame()
                    node("page-enter-previous").assertDoesNotExist()
                    ui.onAllNodesWithTag("page:主模型服务商")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
                }
                node("page:主模型服务商").assertExists()
                repeat(4) {
                    ui.mainClock.advanceTimeByFrame()
                    node("page-enter-previous").assertDoesNotExist()
                }
                captureScreenNode(node("page-back-surface"))
                mainThread { activity.onBackPressedDispatcher.onBackPressed() }
                awaitMotion("page-back-departure")
                ui.mainClock.advanceTimeBy(1_000)
                node("provider-management").assertExists()
            } finally { ui.mainClock.autoAdvance = true }
        }
        assertEquals(before, fixture.controller.providers.snapshot())
        assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
    }

    fun testTerminalEnvironmentEntryAndSystemReturnKeepTheChatParentAndFreshOwner() {
        val provider = (instrumentation.targetContext.applicationContext as ChatApplication).terminalProvider
        awaitControl("terminal provider ready") {
            provider.state.value is app.daiyu.android.terminal.TerminalApplicationState.Ready
        }
        val workspace = (provider.state.value as app.daiyu.android.terminal.TerminalApplicationState.Ready)
            .application.terminalSessions
        val originalSimple = workspace.state.value.simple
        mainThread { workspace.setSimple(true) }
        val simpleBefore = workspace.state.value
        try {
            ui.onNodeWithContentDescription("更多").performClick()
            node("chat-menu:${ChatMenuAction.OPEN_TERMINAL.name}").scrollToVisible().performClick()
            awaitUniqueTag("terminal-screen")
            val owner = invokeHost(activity, "getTerminalPageOwner")
            assertNotNull(owner)
            assertTrue("fixture Linux environment is not installed", workspace.state.value.linux.state != "FILES_READY")
            node("terminal-env-linux").performClick()
            awaitUniqueTag("terminal-open-environment")
            assertNull("fixture has no matching active Linux session", workspace.state.value.active)
            val openEnvironment = node("terminal-open-environment").assertIsEnabled()
            captureScreenNode(node("page-back-surface"))
            val initial = node("page-back-surface").fetchSemanticsNode().boundsInRoot
            ui.mainClock.autoAdvance = false
            try {
                openEnvironment.performTouchInput { click() }
                if (Build.VERSION.SDK_INT >= 28) {
                    awaitMotion("page-enter-previous")
                    ui.mainClock.advanceTimeBy(64)
                    val incoming = node("page-back-surface").fetchSemanticsNode().boundsInRoot
                    assertTrue("environment entry has an intermediate frame", incoming.left > initial.left && incoming.left < initial.right)
                }
                ui.mainClock.advanceTimeBy(1_000)
                awaitMotion("page:Linux 工具环境")
                captureScreenNode(node("page-back-surface"))
                mainThread { activity.onBackPressedDispatcher.onBackPressed() }
                if (Build.VERSION.SDK_INT >= 28) {
                    awaitMotion("page-back-departure")
                    ui.mainClock.advanceTimeBy(64)
                    val outgoing = node("page-back-departure").fetchSemanticsNode().boundsInRoot
                    assertTrue("environment system return has an intermediate departure", outgoing.left > initial.left && outgoing.left < initial.right)
                    node("page-back-departure-scrim").assertExists()
                }
                ui.mainClock.advanceTimeBy(1_000)
                node("terminal-screen").assertExists()
                val returnedOwner = invokeHost(activity, "getTerminalPageOwner")
                assertNotNull(returnedOwner)
                assertNotSame(owner, returnedOwner)
                mainThread {
                    assertEquals(false, invokeHost(activity, "terminalPageCurrent", owner!!))
                    assertEquals(true, invokeHost(activity, "terminalPageCurrent", returnedOwner!!))
                }
                node("page:Linux 工具环境").assertDoesNotExist()
                mainThread { activity.onBackPressedDispatcher.onBackPressed() }
                ui.mainClock.advanceTimeBy(1_000)
                node("chat-draft").assertExists()
                assertEquals(0, fixture.opens.get())
            } finally { ui.mainClock.autoAdvance = true }
        } finally {
            mainThread {
                workspace.selectEnvironment(simpleBefore.linuxSelected)
                simpleBefore.activeSessionId?.let(workspace::select)
                workspace.setSimple(originalSimple)
            }
        }
    }
}
