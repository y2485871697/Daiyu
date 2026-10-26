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
    private val text = "mount-owned draft"

    private fun show() {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (mounted.value) ShizukuApprovalHost(fixture.controller, null, text,
                            onAccepted = { _, _ -> error("must not submit") })
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun ownPreview(): ControllerShizukuPreparation {
        ui.onNodeWithTag("shizuku-text-enable").scrollToVisible().performClick()
        ui.onNode(hasSetTextAction() and hasContentDescription("精确 UID：0 或 2000（必选，无默认值）"))
            .scrollToVisible().performTextReplacement("2000")
        ui.onNode(hasSetTextAction() and hasContentDescription("本次精确 shell 命令"))
            .scrollToVisible().performTextReplacement("printf own")
        ui.onNodeWithText("预览命令及本次运行").scrollToVisible().performClick()
        ui.waitUntil(5_000) { fixture.controller.shizukuPreparation() != null }
        return checkNotNull(fixture.controller.shizukuPreparation())
    }

    fun testOldMountDisposalDoesNotRejectReplacementPreparation() {
        verifyReplacementAfterDisposal(createOwned = true)
    }

    fun testMountWithoutOwnPreparationDoesNotRejectExternalPreparation() {
        verifyReplacementAfterDisposal(createOwned = false)
    }

    private fun verifyReplacementAfterDisposal(createOwned: Boolean) {
        show()
        val original = if (createOwned) ownPreview() else null
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

    fun testDisposalRejectsOwnUnsubmittedPreparation() {
        verifyOwnDisposal()
    }

    private fun verifyOwnDisposal() {
        show()
        val original = ownPreview()
        mainThread { mounted.value = false }
        ui.waitForIdle()
        assertTrue(original.isCancelled())
        assertNull(fixture.controller.shizukuPreparation())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
    }
}
