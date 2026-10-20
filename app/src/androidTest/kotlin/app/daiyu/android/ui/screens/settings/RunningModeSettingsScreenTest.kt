package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.runningmode.*
import app.daiyu.android.ui.app.AgentAppTheme
import kotlinx.coroutines.CompletableDeferred

class RunningModeSettingsScreenTest : WiredActivityTestCase() {
    private val store = RunningModeStore(object : RunningModePersistence {
        private var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    })
    private class Authorization : RunningModeAuthorization {
        var rootCalls = 0
        var shizukuCalls = 0
        var root = false
        var granted = false
        var answer = CompletableDeferred<Boolean>()
        override suspend fun requestRoot(): Boolean { rootCalls++; return root }
        override suspend fun requestShizuku(): Boolean { shizukuCalls++; return answer.await() }
        override suspend fun shizukuGranted() = granted
    }
    private fun show(auth: Authorization) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), false) {
                    RunningModeSettingsScreen({}, store = store, authorization = auth)
                }
            }
        }
        ui.waitForIdle()
    }
    fun testOnlyThreeOptionsNoDescriptionsAndRootFailureStaysInItsRow() {
        val auth = Authorization()
        show(auth)
        node("running-mode:normal").assertIsSelected()
        assertEquals(0, auth.rootCalls + auth.shizukuCalls)
        ui.onAllNodes(isSelectable()).assertCountEquals(3)
        node("running-mode:root").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Root · 设备无root").fetchSemanticsNodes().isNotEmpty() }
        node("running-mode:root").assertIsNotSelected().assertHasClickAction()
        node("running-mode:normal").assertIsSelected()
        assertEquals(1, auth.rootCalls)
        auth.root = true
        node("running-mode:root").performClick()
        ui.waitUntil(5_000) { store.mode.value == RunningMode.ROOT }
        node("running-mode:root").assertIsSelected()
        ui.onNodeWithText("Root · 设备无root").assertDoesNotExist()
        node("running-mode:normal").performClick()
        ui.waitUntil(5_000) { store.mode.value == RunningMode.NORMAL }
        assertEquals(2, auth.rootCalls)
        assertEquals(0, fixture.opens.get())
    }
    fun testShizukuWaitsForRealGrantAndCanRetryFailure() {
        val auth = Authorization()
        show(auth)
        node("running-mode:shizuku").performClick()
        node("running-mode:shizuku").assertIsNotSelected().assertIsNotEnabled()
        node("running-mode:normal").assertIsSelected()
        auth.answer.complete(false)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Shizuku · 授权失败").fetchSemanticsNodes().isNotEmpty() }
        node("running-mode:shizuku").assertIsNotSelected().assertIsEnabled()
        auth.answer = CompletableDeferred()
        node("running-mode:shizuku").performClick()
        auth.granted = true
        auth.answer.complete(true)
        ui.waitUntil(5_000) { store.mode.value == RunningMode.SHIZUKU }
        node("running-mode:shizuku").assertIsSelected()
        assertEquals(2, auth.shizukuCalls)
        assertEquals(0, fixture.opens.get())
    }
}
