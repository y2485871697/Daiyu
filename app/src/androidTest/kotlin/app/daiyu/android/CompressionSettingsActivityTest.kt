package app.daiyu.android

import android.content.Context
import androidx.compose.ui.test.*
import app.daiyu.android.context.CompressionSettingsEditor
import app.daiyu.android.context.ProductionCompressionSettings
import app.daiyu.android.context.ProductionCompressionSettingsStore

class CompressionSettingsActivityTest : WiredActivityTestCase() {
    private var oldV1: String? = null
    private var oldV2: String? = null
    private val preferences get() = instrumentation.targetContext.getSharedPreferences("context-compression", Context.MODE_PRIVATE)

    override fun setUp() {
        oldV1 = preferences.getString("compression-settings-v1", null)
        oldV2 = preferences.getString("compression-settings-v2", null)
        ProductionCompressionSettingsStore(instrumentation.targetContext).save(ProductionCompressionSettings())
        super.setUp()
    }

    override fun tearDown() {
        try { super.tearDown() } finally {
            val edit = preferences.edit().remove("compression-settings-v1").remove("compression-settings-v2")
            oldV1?.let { edit.putString("compression-settings-v1", it) }
            oldV2?.let { edit.putString("compression-settings-v2", it) }
            check(edit.commit())
        }
    }

    private fun enter() {
        openSettings()
        reveal("settings-row:上下文压缩", "设置").performClick()
        node("page:上下文压缩").assertExists()
        ui.waitUntil(timeoutMillis = 5000) {
            ui.onAllNodes(hasTestTag("settings-field:上下文窗口 tokens") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun owner(): CompressionSettingsEditor {
        lateinit var result: CompressionSettingsEditor
        mainThread {
            result = MainActivity::class.java.getDeclaredField("compressionSettingsPageOwner")
                .apply { isAccessible = true }.get(activity) as CompressionSettingsEditor
        }
        return result
    }

    fun testSettingsEntryDoesNotSendOrCreateConversation() {
        val before = fixture.database.exportBackup()
        enter()
        assertEquals(before, fixture.database.exportBackup())
        assertNull(fixture.controller.active)
        assertEquals(0, fixture.opens.get())
    }

    fun testReturnRevokesOldPageAndReentryUsesNewOwner() {
        enter()
        val old = owner()
        backToPage(activity, "page:设置")
        node("page:设置").assertExists()
        assertNull(old.prepareLoad())
        reveal("settings-row:上下文压缩", "设置").performClick()
        assertNotSame(old, owner())
        assertEquals(0, fixture.opens.get())
    }

    fun testRecreationDoesNotReuseEditorOrStartSummary() {
        enter()
        val old = owner()
        activity = recreateActivity(activity)
        assertNull(old.prepareLoad())
        node("chat-draft").assertExists()
        assertEquals(0, fixture.opens.get())
    }
}
