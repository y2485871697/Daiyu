package app.daiyu.android.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import app.daiyu.android.ControllerShizukuPreparation
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme

/** Mount ownership only: real controller reservation, no privileged transport or model request. */
class ShizukuApprovalHostTest : WiredActivityTestCase() {
    private val mounted = mutableStateOf(true)
    private val currentVisit = mutableStateOf(true)
    private val text = "mount-owned draft"

    private fun show() {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (mounted.value) ShizukuApprovalHost(fixture.controller, null, text,
                            isCurrent = { currentVisit.value },
                            onAccepted = { _, _ -> error("must not submit") })
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun withBlockedShizukuWorker(block: (() -> Unit) -> Unit) {
        val executor = app.daiyu.android.ChatController::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(fixture.controller) as java.util.concurrent.ExecutorService
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocker = executor.submit { entered.countDown(); release.await() }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            block { release.countDown() }
        } finally {
            release.countDown()
            blocker.get(5, java.util.concurrent.TimeUnit.SECONDS)
            executor.submit { }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun ownSubmission(): ControllerShizukuPreparation {
        ui.onNode(hasSetTextAction() and hasText("精确 UID：0 或 2000（必选，无默认值）"))
            .scrollToVisible().performTextReplacement("2000")
        ui.onNode(hasSetTextAction() and hasText("本次精确 shell 命令"))
            .scrollToVisible().performTextReplacement("printf own")
        ui.onNodeWithTag("shizuku-text-send").scrollToVisible().performClick()
        return checkNotNull(fixture.controller.shizukuPreparation()).also { assertTrue(it.isSubmitted()) }
    }

    private fun ownCancelledSubmission(): ControllerShizukuPreparation {
        lateinit var original: ControllerShizukuPreparation
        withBlockedShizukuWorker {
            original = ownSubmission()
            mainThread { fixture.controller.rejectShizuku(original) }
        }
        ui.waitUntil(5_000) { fixture.controller.shizukuPreparation() == null }
        assertTrue(original.isCancelled())
        return original
    }

    fun testDisposedSendCallbackCannotCreateANewPreparation() {
        verifyRetainedSendRejected(dispose = true)
    }

    fun testRevokedParentVisitRejectsRetainedSendWithoutDisposal() {
        verifyRetainedSendRejected(dispose = false)
    }

    private fun verifyRetainedSendRejected(dispose: Boolean) {
        show()
        ui.onNode(hasSetTextAction() and hasText("精确 UID：0 或 2000（必选，无默认值）"))
            .scrollToVisible().performTextReplacement("2000")
        ui.onNode(hasSetTextAction() and hasText("本次精确 shell 命令"))
            .scrollToVisible().performTextReplacement("printf stale")
        val oldSend = checkNotNull(ui.onNodeWithTag("shizuku-text-send").scrollToVisible()
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        mainThread { if (dispose) mounted.value = false else currentVisit.value = false }
        ui.waitForIdle()
        mainThread { oldSend() }
        assertNull(fixture.controller.shizukuPreparation())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
    }

    fun testOldMountDisposalDoesNotRejectReplacementPreparation() {
        verifyReplacementAfterDisposal(createOwned = true)
    }

    fun testMountWithoutOwnPreparationDoesNotRejectExternalPreparation() {
        verifyReplacementAfterDisposal(createOwned = false)
    }

    private fun verifyReplacementAfterDisposal(createOwned: Boolean) {
        show()
        val original = if (createOwned) ownCancelledSubmission() else null
        var replacement: ControllerShizukuPreparation? = null
        try {
            mainThread {
                original?.let(fixture.controller::rejectShizuku)
                assertNull(fixture.controller.shizukuPreparation())
                replacement = checkNotNull(fixture.controller.prepareShizukuSend(
                    null, text, "printf replacement", "2000", "1234"))
            }
            ui.waitForIdle()
            val current = checkNotNull(replacement)
            assertSame(current, fixture.controller.shizukuPreparation())
            mainThread { mounted.value = false }
            ui.waitForIdle()
            assertSame(current, fixture.controller.shizukuPreparation())
            assertFalse(current.isCancelled())
            assertFalse(current.isSubmitted())
            original?.let { assertTrue(it.isCancelled()) }
            assertEquals(0, fixture.opens.get())
            assertTrue(fixture.database.conversations().isEmpty())
        } finally {
            mainThread { replacement?.let(fixture.controller::rejectShizuku) }
        }
    }

    fun testDisposalDoesNotRejectOwnSubmittedPreparation() {
        show()
        withBlockedShizukuWorker {
            val original = ownSubmission()
            mainThread { mounted.value = false }
            ui.waitForIdle()
            assertSame(original, fixture.controller.shizukuPreparation())
            assertTrue(original.isSubmitted())
            assertFalse(original.isCancelled())
            assertEquals(0, fixture.opens.get())
            assertTrue(fixture.database.conversations().isEmpty())
        }
        ui.waitUntil(5_000) { fixture.controller.shizukuPreparation() == null }
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
    }

    fun testUidRequiredAndMountEditingDoNotSubmit() {
        show()
        ui.onNodeWithTag("shizuku-text-enable").assertDoesNotExist()
        ui.onNode(hasSetTextAction() and hasText("本次精确 shell 命令"))
            .scrollToVisible().performTextReplacement("printf own")
        ui.onNodeWithTag("shizuku-text-send").scrollToVisible().performClick()
        assertNull(fixture.controller.shizukuPreparation())
        assertEquals(0, fixture.opens.get())
        ui.onNode(hasSetTextAction() and hasText("精确 UID：0 或 2000（必选，无默认值）"))
            .scrollToVisible().performTextReplacement("2000")
        ui.waitForIdle()
        assertNull(fixture.controller.shizukuPreparation())
        mainThread { mounted.value = false }
        ui.waitForIdle()
        assertNull(fixture.controller.shizukuPreparation())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
    }
}
