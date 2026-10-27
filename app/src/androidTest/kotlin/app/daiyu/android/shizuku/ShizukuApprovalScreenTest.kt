package app.daiyu.android.shizuku

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.tools.ExecutionReceipt
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.shizuku.ShizukuApprovalContent
import java.util.concurrent.atomic.AtomicInteger

/** Pure approval UI; these tests never request Shizuku permission or open a device backend. */
class ShizukuApprovalScreenTest : WiredActivityTestCase() {
    private fun show(session: ShizukuRunSession, opens: AtomicInteger = AtomicInteger()) {
        mainThread {
            activity.setContent {
                val state = session.states.collectAsState().value
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ShizukuApprovalContent(state, busy = false, canOpen = true, requireInteractiveApproval = true,
                            onEdit = session::edit, onApprove = { session.approve(state) },
                            onOpen = { opens.incrementAndGet() }, onRevoke = session::revoke)
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    fun testNoIdentityOrCommandMeansNoApprovalAndNoAutomaticOpen() {
        val session = ShizukuRunSession("session", "run")
        val opens = AtomicInteger()
        show(session, opens)
        node("shizuku-approve").scrollToVisible().assertIsNotEnabled()
        node("shizuku-open").assertDoesNotExist()
        node("shizuku-select-shell").scrollToVisible().performClick()
        node("shizuku-script").scrollToVisible().performTextReplacement("printf exact")
        node("shizuku-approve").scrollToVisible().performClick()
        node("shizuku-open").scrollToVisible().assertIsEnabled()
        assertEquals(ShizukuApprovalStatus.APPROVED, session.state.status)
        assertEquals(2000, session.state.selectedUid)
        assertEquals(0, opens.get())
        node("shizuku-open").performClick()
        assertEquals(1, opens.get())
    }

    fun testTimeoutAndIdentityEditsInvalidateApprovalImmediately() {
        val session = ShizukuRunSession("session", "run").apply {
            edit(2000, "printf exact", 1000)
            approve(state)
        }
        show(session)
        node("shizuku-timeout").scrollToVisible().performTextReplacement("")
        assertTrue(session.allowedWrites.isEmpty())
        node("shizuku-open").assertDoesNotExist()
        node("shizuku-approve").scrollToVisible().assertIsNotEnabled()
        node("shizuku-timeout").scrollToVisible().performTextReplacement("1001")
        node("shizuku-approve").scrollToVisible().performClick()
        assertEquals(setOf(RunShizukuCommands.writeEffect(2000, "printf exact", 1001)), session.allowedWrites)
        node("shizuku-select-root").scrollToVisible().performClick()
        assertEquals(0, session.state.selectedUid)
        assertTrue(session.allowedWrites.isEmpty())
        node("shizuku-open").assertDoesNotExist()
    }

    fun testRevokeDoesNotLeaveExecutableOrEditableControls() {
        val session = ShizukuRunSession("session", "run").apply {
            edit(2000, "printf exact", 1000)
            approve(state)
        }
        show(session)
        node("shizuku-revoke").scrollToVisible().performClick()
        node("shizuku-open").assertDoesNotExist()
        node("shizuku-approve").assertDoesNotExist()
        node("shizuku-script").scrollToVisible().assertIsNotEnabled()
        assertTrue(session.allowedWrites.isEmpty())
        assertEquals(ShizukuApprovalStatus.REVOKED, session.state.status)
    }

    fun testUnknownExecutionAndCleanupAreNotRenderedAsSuccessOrRelease() {
        val state = ShizukuRunState("session", "run", 2000, "printf exact", 1000,
            status = ShizukuApprovalStatus.CLOSED,
            receipt = ExecutionReceipt.Unknown("lost_receipt"),
            cleanup = ShizukuCloseResult.Unknown("permission_revoked"))
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ShizukuApprovalContent(state, busy = false, canOpen = false,
                            onEdit = { _, _, _ -> fail("closed") }, onApprove = { fail("closed") },
                            onOpen = { fail("closed") }, onRevoke = { fail("closed") })
                    }
                }
            }
        }
        ui.onNodeWithText("结果未知：lost_receipt。禁止自动重放；请人工确认设备实际状态。").scrollToVisible().assertExists()
        ui.onNodeWithText("关闭未确认：permission_revoked。不能视为已释放资源。").scrollToVisible().assertExists()
        node("shizuku-open").assertDoesNotExist()
    }
}
