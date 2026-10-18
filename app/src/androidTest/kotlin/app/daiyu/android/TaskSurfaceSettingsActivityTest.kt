package app.daiyu.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        val entered = AtomicReference<CountDownLatch>()
        val release = AtomicReference<CountDownLatch>()
        val store = TaskSurfaceStore(object : TaskSurfacePersistence {
            override fun read() = record
            override fun write(value: String) {
                entered.get().countDown()
                check(release.get().await(10, TimeUnit.SECONDS)) { "test write gate was not released" }
                check(!failWrite); record = value
            }
        })
        fun saveWithObservedBusyState() {
            val gate = CountDownLatch(1)
            entered.set(CountDownLatch(1)); release.set(gate)
            try {
                reveal("settings-row:保存任务位置", "Agent任务偏好").performClick()
                awaitControl("preference persistence entered") { entered.get().count == 0L }
                awaitUniqueTag("settings-row:正在保存…")
            } finally { gate.countDown() }
            awaitUniqueTag("settings-row:保存任务位置")
        }
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
        saveWithObservedBusyState()
        val failure = "保存失败，未确认新偏好。后续执行将拒绝使用未确认的位置；请重试保存。"
        awaitControl("failed preference write fenced") { store.load() === TaskSurfaceSettings.Unavailable }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("settings-row:保存任务位置")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        node("page:Agent任务偏好").performScrollToNode(hasText(failure))
        ui.onNodeWithText(failure).assertIsDisplayed()
        assertEquals("task-surface-v1\nASK\n", record)
        assertSame(TaskSurfaceSettings.Unavailable, store.load())
        mainThread { failWrite = false }
        saveWithObservedBusyState()
        val success = "任务位置偏好已保存；当前运行的决定保持不变。"
        awaitControl("successful preference retry read back") {
            store.load() == TaskSurfaceSettings.Available(TaskSurfacePreference.BACKGROUND)
        }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("settings-row:保存任务位置")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        node("page:Agent任务偏好").performScrollToNode(hasText(success))
        ui.onNodeWithText(success).assertIsDisplayed()
        assertEquals(TaskSurfaceSettings.Available(TaskSurfacePreference.BACKGROUND), store.load())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(modelSettings, fixture.settings.loadForEditing().settings)
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
    }
}
