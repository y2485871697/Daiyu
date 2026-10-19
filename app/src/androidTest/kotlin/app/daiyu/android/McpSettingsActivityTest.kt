package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.mcp.McpSettingsOwner

/** Real SettingsCatalog -> MainActivity route. Never reads, changes or exports stored secret values. */
class McpSettingsActivityTest : WiredActivityTestCase() {
    private fun enter() {
        openSettings()
        node("settings-search").performTextReplacement("MCP")
        reveal("settings-row:MCP 服务器", "设置").performClick()
        node("mcp-settings").assertExists()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("mcp-settings-status").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun currentOwner(): McpSettingsOwner {
        lateinit var owner: McpSettingsOwner
        mainThread {
            owner = MainActivity::class.java.getDeclaredField("mcpSettingsPageOwner").apply { isAccessible = true }.get(activity) as McpSettingsOwner
        }
        return owner
    }
    fun testRealRouteDoesNotStartModelOrWriteConversationAndNeverPrefillsSavedBearer() {
        val before = fixture.database.exportBackup()
        enter()
        node("mcp-server-bearer").assertDoesNotExist()
        node("mcp-settings-add").performClick()
        val field = node("mcp-server-bearer").performScrollTo().fetchSemanticsNode()
        assertEquals("", field.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertEquals(before, fixture.database.exportBackup())
        assertNull(fixture.controller.active); assertEquals(0, fixture.opens.get())
    }
    fun testBackAndRecreationRevokeExactlyTheOriginalSettingsOwner() {
        enter()
        val old = currentOwner(); assertTrue(old.current())
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle(); assertFalse(old.current())
        node("page:设置").assertExists()
        node("settings-search").performTextReplacement("MCP")
        reveal("settings-row:MCP 服务器", "设置").performClick()
        val replacement = currentOwner(); assertNotSame(old, replacement); assertTrue(replacement.current())
        activity = recreateActivity(activity)
        assertFalse(replacement.current()); assertEquals(0, fixture.opens.get())
    }
}
