package app.daiyu.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsActions
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.platform.PlatformReadTool
import app.daiyu.android.storage.PlatformToolSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.DaiyuSettingsScreen
import app.daiyu.android.ui.screens.settings.PlatformToolSettingsScreen
import java.util.UUID

/** UI callbacks are synthetic; no system prompts, provider reads or model requests. */
class PlatformToolSettingsActivityTest : WiredActivityTestCase() {
    fun testEngineeringPermissionEntriesAreAbsentFromProductSettings() {
        val events = mutableListOf<String>()
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), false) {
            DaiyuSettingsScreen(onBack = {}, onRoute = {}, onOpenPlatformTools = { events.add("platform") })
        } } }
        ui.waitForIdle()
        for (title in listOf("平台只读工具与私密出站", "权限检查", "浏览器")) {
            node("settings-search")
                .performSemanticsAction(SemanticsActions.SetText) { setText ->
                    assertTrue(setText(AnnotatedString(title)))
                }
                .assertEditableText(title)
            node("settings-row:$title").assertDoesNotExist()
        }
        ui.runOnIdle { assertTrue(events.isEmpty()) }
    }

    fun testLocationPermissionClicksNeverSelectToolOrGrantOutboundConsentAndStaleVisitIsInert() {
        val preferences = activity.getSharedPreferences("location-page-test-${UUID.randomUUID()}", 0)
        val settings = PlatformToolSettings(preferences)
        val events = mutableListOf<String>()
        var current = true
        val tool = PlatformReadTool.LAST_KNOWN_LOCATION
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), false) {
                PlatformToolSettingsScreen(activity, settings, 0, grants = { emptyList() },
                    ownerCurrent = { current }, onBack = {},
                    onPermission = { selected, permission -> events.add("${selected.toolName}:$permission") },
                    onPick = {}, onCopy = {}, onRevoke = {})
            } } }
            ui.waitForIdle()
            ui.runOnIdle { assertTrue(events.isEmpty()); assertTrue(settings.freeze().platformAllowed.isEmpty()) }
            val page = "平台只读工具与私密出站"
            reveal("settings-row:请求 android.permission.ACCESS_COARSE_LOCATION", page).performClick()
            reveal("settings-row:请求 android.permission.ACCESS_FINE_LOCATION", page).performClick()
            ui.runOnIdle {
                assertEquals(listOf("last_known_location:android.permission.ACCESS_COARSE_LOCATION",
                    "last_known_location:android.permission.ACCESS_FINE_LOCATION"), events)
                assertTrue(settings.freeze().platformAllowed.isEmpty())
                assertFalse(settings.freeze().modelConsent(tool)); assertFalse(settings.freeze().historyConsent(tool))
                current = false
            }
            reveal("settings-row:请求 android.permission.ACCESS_FINE_LOCATION", page).performClick()
            reveal("settings-switch:选择 ${tool.toolName}", page).performClick()
            ui.runOnIdle { assertEquals(2, events.size); assertTrue(settings.freeze().platformAllowed.isEmpty()) }
        } finally { mainThread { preferences.edit().clear().commit() } }
    }

    fun testSelectionConsentsAndPermissionActionAreIndependentAndStalePageCannotSave() {
        val preferences = activity.getSharedPreferences("platform-page-test-${UUID.randomUUID()}", 0)
        val settings = PlatformToolSettings(preferences)
        val events = mutableListOf<String>()
        var current = true
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), false) {
                PlatformToolSettingsScreen(activity, settings, 0, grants = { emptyList() },
                    ownerCurrent = { current }, onBack = {},
                    onPermission = { tool, permission -> events.add("permission:${tool.toolName}:$permission") },
                    onPick = { events.add("picker:$it") }, onCopy = { events.add("copy") }, onRevoke = { events.add("revoke") })
            } } }
            ui.waitForIdle()
            ui.runOnIdle { assertTrue(events.isEmpty()); assertTrue(settings.freeze().platformAllowed.isEmpty()) }
            val page = "平台只读工具与私密出站"
            val tool = PlatformReadTool.CONTACTS
            reveal("settings-switch:选择 ${tool.toolName}", page).performClick().assertIsOn()
            ui.runOnIdle {
                val frozen = settings.freeze()
                assertEquals(setOf(tool), frozen.platformAllowed)
                assertFalse(frozen.modelConsent(tool)); assertFalse(frozen.historyConsent(tool)); assertTrue(events.isEmpty())
            }
            reveal("settings-switch:${tool.toolName}：同意发送私密结果给模型", page).performClick().assertIsOn()
            ui.runOnIdle { assertFalse(settings.freeze().historyConsent(tool)); assertTrue(events.isEmpty()) }
            reveal("settings-row:请求 android.permission.READ_CONTACTS", page).performClick()
            ui.runOnIdle {
                assertEquals(listOf("permission:search_contacts:android.permission.READ_CONTACTS"), events)
                assertFalse(settings.freeze().historyConsent(tool))
            }
            reveal("settings-switch:${tool.toolName}：同意私密结果写入历史/账本", page).performClick().assertIsOn()
            ui.runOnIdle { assertTrue(settings.freeze().resultPolicy.allowsShared(tool.toolName)); current = false }
            reveal("settings-switch:选择 ${tool.toolName}", page).performClick()
            ui.runOnIdle { assertEquals(setOf(tool), settings.freeze().platformAllowed) }
        } finally { mainThread { preferences.edit().clear().commit() } }
    }
}
