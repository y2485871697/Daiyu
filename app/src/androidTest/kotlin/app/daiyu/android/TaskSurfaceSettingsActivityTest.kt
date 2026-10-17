package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import app.daiyu.android.tasksurface.TaskSurfacePersistence
import app.daiyu.android.tasksurface.TaskSurfacePreference
import app.daiyu.android.tasksurface.TaskSurfaceSettings
import app.daiyu.android.tasksurface.TaskSurfaceStore
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.TaskSurfaceSettingsPanel

/** Inject only persistence failure; exercise the actual settings page, not a fake page/model. */
class TaskSurfaceSettingsActivityTest : WiredActivityTestCase() {
    fun testSaveFailureIsVisibleAndSuccessfulRetryPersistsOnlyTheSelectedBasePreference() {
        var record: String? = "task-surface-v1\nASK\n"
        var failWrite = true
        val store = TaskSurfaceStore(object : TaskSurfacePersistence {
            override fun read() = record
            override fun write(value: String) { check(!failWrite); record = value }
        })
        val before = fixture.database.exportBackup()
        val modelSettings = fixture.settings.loadForEditing().settings
        mainThread {
            activity.setContent {
                AgentAppTheme(app.daiyu.android.data.model.AppearanceSettings(), applyInterfaceScale = true) {
                    TaskSurfaceSettingsPanel(onBack = {}, store = store)
                }
            }
        }
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.onAllNodesWithTag("settings-radio:后台执行").fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.Disabled) == null &&
                ui.onAllNodesWithTag("settings-radio:后台执行").fetchSemanticsNodes().isNotEmpty()
        }
        reveal("settings-radio:后台执行", "Agent任务偏好").assertIsEnabled().performClick()
        reveal("settings-row:保存任务位置", "Agent任务偏好").performClick()
        val failure = "保存失败，未确认新偏好。后续执行将拒绝使用未确认的位置；请重试保存。"
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithText(failure).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(failure).assertExists()
        assertEquals("task-surface-v1\nASK\n", record)
        assertSame(TaskSurfaceSettings.Unavailable, store.load())
        mainThread { failWrite = false }
        reveal("settings-row:保存任务位置", "Agent任务偏好").performClick()
        val success = "任务位置偏好已保存；当前运行的决定保持不变。"
        ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithText(success).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.BACKGROUND), store.load())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(modelSettings, fixture.settings.loadForEditing().settings)
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
}
