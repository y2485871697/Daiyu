package app.daiyu.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import java.util.concurrent.CopyOnWriteArrayList

/** Real retained settings navigation. External intents are intercepted, never actually granted. */
class SystemAccessSettingsActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private fun entry(title: String) {
        when (title) {
            "Root 与系统增强" -> openRetainedSettingsEntry("enhancements", "page:Root 与系统增强")
            "悬浮窗权限" -> openRetainedSettingsEntry("overlay", "page:悬浮窗权限")
            else -> {

                reveal("settings-row:$title", "设置").performClick()
            }
        }
    }
    private fun intercept(action: () -> Unit): List<Intent> {
        val observed = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action !in setOf(Intent.ACTION_VIEW, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) return null
                observed += Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try { action(); instrumentation.waitForIdleSync(); return observed.toList() }
        finally { instrumentation.removeMonitor(monitor) }
    }

    fun testEnhancementsUsesExistingShizukuStatusWithoutApprovingOrRunning() {
        setDraft("system access unsent")
        val before = fixture.database.exportBackup()
        openSettings(); entry("Root 与系统增强")
        node("page:Root 与系统增强").assertExists()
        node("settings-row:Shizuku 状态与系统授权").performClick()
        node("page:Shizuku 命令授权").assertExists()
        node("shizuku-approval").assertDoesNotExist()
        node("shizuku-open").assertDoesNotExist()
        backToPage(activity, "page:Root 与系统增强")
        backToPage(activity, "page:设置")
        backToChat(activity)
        assertDraft("system access unsent")
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testOverlaySettingsIntentTargetsThisPackageWithoutChangingPermissionOrConsent() {
        val app = activity.application as ChatApplication
        var allowed = false
        lateinit var saved: app.daiyu.android.domain.pet.PetSettings
        mainThread {
            allowed = app.pets.overlayAllowed
            saved = app.pets.host.snapshot().pet.state.settings
        }
        val granted = Settings.canDrawOverlays(activity)
        openSettings(); entry("悬浮窗权限")
        node("overlay-system-status").assertExists()
        val intents = intercept { node("settings-row:前往系统悬浮窗设置").performClick() }
        assertEquals(1, intents.size)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intents.single().action)
        assertEquals("package:${activity.packageName}", intents.single().dataString)
        assertEquals(granted, Settings.canDrawOverlays(activity))
        mainThread {
            assertEquals(allowed, app.pets.overlayAllowed)
            assertEquals(saved, app.pets.host.snapshot().pet.state.settings)
        }
        assertEquals(0, fixture.opens.get())
        backToPage(activity, "page:设置")
        backToChat(activity)
    }

    fun testSourceUsesOnlyThisProjectAndPreservesDraftAndDatabase() {
        setDraft("source unsent")
        val before = fixture.database.exportBackup()
        openSettings()
        entry("APP 信息与更新")
        val intents = intercept { reveal("settings-row:源代码", "APP 信息与更新").performClick() }
        assertEquals(1, intents.size)
        assertEquals(Intent.ACTION_VIEW, intents.single().action)
        assertEquals("https://github.com/y2485871697/Daiyu", intents.single().dataString)
        assertTrue(intents.single().hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        backToChat(activity); assertDraft("source unsent")
    }

    fun testPausedOverlayCallbackCannotLaunchSystemSettings() {
        openSettings(); entry("悬浮窗权限")
        val click = checkNotNull(node("settings-row:前往系统悬浮窗设置")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val intents = intercept { realPauseResume(activity, atPause = { click() }) }
        assertTrue(intents.isEmpty())
        node("page:悬浮窗权限").assertExists()
        backToPage(activity, "page:设置")
        backToChat(activity)
        assertEquals(0, fixture.opens.get())
    }
}
