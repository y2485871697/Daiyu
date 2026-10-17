package app.daiyu.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.test.*
import app.daiyu.android.diagnostics.DiagnosticEvent
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals

class SettingsDiagnosticsActivityTest : WiredActivityTestCase() {
    private fun openDiagnostics() {
        openSettings()
        node("settings-search").performTextReplacement("诊断")
        awaitControl("diagnostic load") { fixture.diagnostics.state.value.loaded }
    }
    private fun row(title: String): SemanticsNodeInteraction {
        node("settings-search").performTextReplacement(title)
        return reveal("settings-row:$title", "设置")
    }
    private fun enable() {
        row("记录诊断日志").assertIsEnabled().performClick()
        awaitControl("logging enabled") { fixture.diagnostics.state.value.enabled }
    }
    private fun awaitNotice(text: String) {
        ui.waitUntil(5_000) { ui.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
    fun testRealProducerEnablePauseResumeAndClearPreserveDraftDatabaseAndOtherRecords() {
        setDraft("diagnostics unsent private-canary")
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.load()
        openDiagnostics(); enable()
        val completed = CountDownLatch(1)
        mainThread { fixture.controller.saveModelSettings(settings, false, false) { assertTrue(it); completed.countDown() } }
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        val saved = fixture.diagnostics.exportSnapshot()
        val events = JSONObject(String(saved, Charsets.UTF_8)).getJSONArray("events")
        assertTrue(events.length() >= 2)
        assertEquals("DATA_STARTED", events.getJSONObject(0).getString("event"))
        assertEquals("DATA_COMPLETED", events.getJSONObject(1).getString("event"))
        for (privateValue in listOf("private-canary", settings.apiKey, settings.endpoint, settings.model))
            assertFalse(String(saved, Charsets.UTF_8).contains(privateValue))
        realPauseResume(activity)
        row("记录诊断日志").assertIsOn()
        val unrelated = java.io.File(fixture.diagnosticsFile.parentFile, "operation-journal.json").apply { writeText("UNKNOWN canary") }
        row("清除日志").performClick(); node("dialog-cancel").performClick()
        assertArrayEquals(saved, fixture.diagnostics.exportSnapshot())
        row("清除日志").performClick(); node("dialog-confirm").performClick()
        awaitNotice("诊断日志已清除")
        assertEquals(0, fixture.diagnostics.state.value.count)
        assertTrue(fixture.diagnostics.state.value.enabled)
        assertEquals("UNKNOWN canary", unrelated.readText())
        assertEquals(before, fixture.database.exportBackup()); assertEquals(settings, fixture.settings.load())
        assertEquals(0, fixture.opens.get())
        backToChat(activity); assertDraft("diagnostics unsent private-canary")
    }
    fun testExportWritesOnlyStructuredSnapshotToAnExplicitDocumentTarget() {
        openDiagnostics(); enable()
        fixture.diagnostics.record(DiagnosticEvent.RUN_FAILED)
        val provider = instrumentation.context.packageManager.getPackageInfo(instrumentation.context.packageName,
            PackageManager.GET_PROVIDERS).providers.orEmpty().single { it.name == DiagnosticExportTestProvider::class.java.name }
        val uri = Uri.Builder().scheme("content").authority(provider.authority).appendPath(UUID.randomUUID().toString()).build()
        val launches = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                assertEquals("application/json", intent.type)
                assertEquals("daiyu-diagnostics.json", intent.getStringExtra(Intent.EXTRA_TITLE))
                launches.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            row("导出日志").performClick(); awaitNotice("诊断日志已导出")
            assertEquals(1, launches.get())
            val actual = activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(fixture.diagnostics.exportSnapshot(), actual)
            assertEquals(0, fixture.opens.get())
        } finally {
            instrumentation.removeMonitor(monitor)
            activity.contentResolver.delete(uri, null, null)
        }
    }
    fun testDiagnosticWriteFailureDoesNotFailARealSettingsSaveOrTouchChat() {
        setDraft("diagnostic write failure draft")
        val databaseBefore = fixture.database.exportBackup()
        val expected = fixture.settings.load().copy(model = "diagnostic-save-still-works")
        openDiagnostics(); enable()
        val durableBefore = fixture.diagnosticsFile.readBytes()
        val pending = java.io.File(fixture.diagnosticsFile.parentFile, "${fixture.diagnosticsFile.name}.pending")
        assertTrue(pending.mkdir())
        try {
            val completed = CountDownLatch(1)
            val success = java.util.concurrent.atomic.AtomicBoolean()
            mainThread { fixture.controller.saveModelSettings(expected, false, false) { success.set(it); completed.countDown() } }
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertTrue(success.get()); assertEquals(expected, fixture.settings.load())
            assertTrue(fixture.diagnostics.state.value.unavailable)
            assertArrayEquals(durableBefore, fixture.diagnosticsFile.readBytes())
            assertEquals(databaseBefore, fixture.database.exportBackup()); assertEquals(0, fixture.opens.get())
            backToChat(activity); assertDraft("diagnostic write failure draft")
        } finally {
            if (pending.exists()) assertTrue(pending.delete())
            fixture.diagnostics.clear()
        }
    }
    fun testExportCancellationAndPauseDismissClearWithoutChangingTheLog() {
        openDiagnostics(); enable(); fixture.diagnostics.record(DiagnosticEvent.RUN_COMPLETED)
        val before = fixture.diagnostics.exportSnapshot()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_CREATE_DOCUMENT) Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) else null
        }
        instrumentation.addMonitor(monitor)
        try { row("导出日志").performClick(); ui.waitForIdle() }
        finally { instrumentation.removeMonitor(monitor) }
        assertArrayEquals(before, fixture.diagnostics.exportSnapshot())
        row("清除日志").performClick()
        realPauseResume(activity)
        node("dialog-confirm").assertDoesNotExist()
        assertArrayEquals(before, fixture.diagnostics.exportSnapshot())
        assertEquals(0, fixture.opens.get())
    }
}
