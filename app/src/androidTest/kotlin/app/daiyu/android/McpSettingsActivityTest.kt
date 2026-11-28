package app.daiyu.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.mcp.McpSettingsOwner

/** Real SettingsCatalog -> MainActivity route. Never reads, changes or exports stored secret values. */
class McpSettingsActivityTest : WiredActivityTestCase() {
    private fun enter() {
        openSettings()
        node("settings-search").assertDoesNotExist()
        reveal("settings-row:MCP 服务器", "设置").performClick()
        node("mcp-settings").assertExists()
        ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("mcp-settings") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "能力默认可用；保存只更新服务器配置，不会执行工具。")).fetchSemanticsNodes().isNotEmpty() }
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
        val field = node("mcp-server-bearer").scrollToVisible().fetchSemanticsNode()
        assertEquals("", field.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertEquals(before, fixture.database.exportBackup())
        assertNull(fixture.controller.active); assertEquals(0, fixture.opens.get())
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testBackAndRecreationRevokeExactlyTheOriginalSettingsOwner() {
        enter()
        val old = currentOwner()
        mainThread { assertTrue("original owner must be current on entry", old.current()) }
        val screen = node("mcp-settings")
        val root = (screen.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
        awaitNodeWindowFocus(screen)
        dismissIme(root)
        awaitNodeWindowFocus(screen)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitUniqueTag("page:设置")
        node("page:设置").assertIsDisplayed()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("mcp-settings").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        node("settings-search").assertDoesNotExist()
        mainThread { assertFalse("back must revoke the original MCP owner", old.current()) }
        reveal("settings-row:MCP 服务器", "设置").performClick()
        awaitUniqueTag("mcp-settings")
        val replacement = currentOwner()
        assertNotSame(old, replacement)
        mainThread { assertTrue("new MCP visit must have its own current owner", replacement.current()) }
        activity = recreateActivity(activity)
        mainThread { assertFalse("recreation must revoke the replacement owner", replacement.current()) }
        assertEquals(0, fixture.opens.get())
    }
}
