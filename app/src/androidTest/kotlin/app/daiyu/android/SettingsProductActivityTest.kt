package app.daiyu.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import java.util.concurrent.atomic.AtomicInteger

class SettingsProductActivityTest : WiredActivityTestCase() {
    fun testBackupPageHasOnlyTwoActionsAndImportUsesSinglePicker() {
        setDraft("product backup unsent")
        val before = fixture.database.exportBackup()
        openSettings()
        node("settings-search").performTextReplacement("数据备份")
        reveal("settings-row:数据备份", "设置").performClick()
        reveal("backup-export", "备份与导入").assertExists().assertIsEnabled()
        reveal("backup-import", "备份与导入").assertExists().assertIsEnabled()
        for (title in listOf("导出精简备份", "导出完整备份", "导入精简备份", "导入完整备份",
            "打开旧版兼容资料库", "预览模型配置", "恢复配置"))
            node("settings-row:$title").assertDoesNotExist()
        val launches = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                assertEquals("*/*", intent.type)
                assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertEquals(listOf("application/json", "application/zip", "application/octet-stream", "text/plain"),
                    intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
                launches.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent())
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            reveal("backup-import", "备份与导入").performClick()
            ui.waitUntil(5_000) { launches.get() == 1 }
            ui.waitForIdle()
            assertEquals(1, launches.get())
            node("dialog-confirm").assertDoesNotExist()
        } finally { instrumentation.removeMonitor(monitor) }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        backToChat(activity)
        assertDraft("product backup unsent")
    }

    fun testProductModelFormExcludesDeviceToolsAndEditsTypedBodyFields() {
        setDraft("product models unsent")
        val savedSettings = fixture.settings.load()
        val before = fixture.database.exportBackup()
        openModels()
        for (title in listOf("电量查询", "设备状态查询"))
            node("settings-switch:$title").assertDoesNotExist()
        node("settings-field:自定义请求体").assertDoesNotExist()
        reveal("model-body-fields").performClick()
        node("model-body-fields-list").performScrollToNode(hasTestTag("model-body-field-add"))
        node("model-body-field-add").performClick()
        node("settings-field:字段名").performTextReplacement("metadata")
        dismissIme()
        node("model-body-field-editor-list").performScrollToNode(hasTestTag("settings-radio:对象"))
        node("settings-radio:对象").performClick()
        dismissIme()
        node("model-body-field-editor-list").performScrollToNode(hasTestTag("settings-field:字段值"))
        node("settings-field:字段值").performTextReplacement("""{"settings_product":true}""")
        dismissIme()
        node("model-body-field-editor-list").performScrollToNode(hasTestTag("model-body-field-apply"))
        node("model-body-field-apply").performClick()
        reveal("settings-switch:原生网页搜索").assertExists().assertIsEnabled()
        assertEquals(savedSettings, fixture.settings.load())
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        backToChat(activity); assertDraft("product models unsent")
    }
}
