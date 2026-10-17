package app.daiyu.android

import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.mcp.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.McpSettingsScreen
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

/** Actual settings form/store; only ephemeral synthetic encrypted data under an isolated noBackup root. */
class McpSettingsScreenTest : WiredActivityTestCase() {
    fun testSaveReopenAndRevokedPageCannotSaveOrBecomeTaskAuthorization() {
        val root = File(instrumentation.targetContext.cacheDir, "mcp-screen-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getNoBackupFilesDir() = root
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = McpSettingsStore(context) { key }; val owner = McpSettingsOwner()
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                McpSettingsScreen(store, owner, ownerCurrent = { true }, onBack = {})
            } } }
            ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("mcp-settings-status") and hasText("保存只配置服务器，不授予工具权限。")).fetchSemanticsNodes().isNotEmpty() }
            node("mcp-server-id").performScrollTo().performTextReplacement("fixture")
            node("mcp-server-label").performScrollTo().performTextReplacement("Synthetic fixture")
            node("mcp-server-endpoint").performScrollTo().performTextReplacement("https://fixture.invalid/mcp")
            node("mcp-server-bearer").performScrollTo().performTextReplacement("synthetic-fixture-token")
            node("mcp-settings-save").performScrollTo().performClick()
            ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("mcp-settings-status") and hasText("已保存；旧的本轮批准已失效，尚未连接服务器。")).fetchSemanticsNodes().isNotEmpty() }
            val saved = store.snapshot()
            assertEquals(1, saved.servers.size); assertEquals("synthetic-fixture-token", saved.servers.single().bearer)
            assertEquals(saved.revision, McpSettingsStore(context) { key }.snapshot().revision)
            assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
            node("mcp-settings-back").performScrollTo().performClick()
            assertFalse(owner.current())
            try { store.save(emptyList(), owner, saved); fail("revoked settings page saved") } catch (_: IllegalStateException) { }
            assertEquals(1, store.snapshot().servers.size)
        } finally {
            mainThread { activity.setContent {} }
            root.deleteRecursively()
        }
    }
}
