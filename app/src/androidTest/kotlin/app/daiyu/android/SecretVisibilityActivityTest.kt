package app.daiyu.android

import android.content.ContextWrapper
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.storage.IndependentModelSettings
import java.io.File
import java.util.UUID

class SecretVisibilityActivityTest : WiredActivityTestCase() {
    private var directory: File? = null
    internal override fun createFixture(): RunControlFixture {
        val root = File(instrumentation.targetContext.cacheDir, "secret-visibility-${UUID.randomUUID()}").apply { check(mkdirs()) }
        directory = root
        return RunControlFixture(object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = root
        })
    }
    override fun cleanupFixture() {
        try { super.cleanupFixture() } finally { directory?.deleteRecursively(); directory = null }
    }
    private fun toggle() = reveal("settings-secret-visibility:API Key")
    private fun assertMasked() = modelField("API Key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

    fun testPrimaryVisibilityPreservesDraftAndResetsOnPauseAndReentry() {
        setDraft("secret controls unsent draft")
        val before = fixture.database.exportBackup()
        val saved = fixture.settings.load()
        openModels(); assertMasked()
        editModel("API Key", "visibility-secret-canary")
        toggle().assertTextContains("显示密钥").performClick()
        modelField("API Key").assertEditableText("visibility-secret-canary")
        toggle().assertTextContains("隐藏密钥").performClick(); assertMasked()
        toggle().performClick()
        modelField("API Key").assertEditableText("visibility-secret-canary")
        realPauseResume(activity); assertMasked()
        toggle().performClick(); modelField("API Key").assertEditableText("visibility-secret-canary")
        assertEquals(saved, fixture.settings.load()); assertEquals(before, fixture.database.exportBackup())
        backToChat(activity); assertDraft("secret controls unsent draft")
        openModels(); assertMasked()
        toggle().performClick(); modelField("API Key").assertEditableText(saved.apiKey)
        assertEquals(0, fixture.opens.get())
    }

    fun testIndependentVisibilityPreservesValueSelectionCapabilityAndStoredBinding() {
        val slot = IndependentModelSettings.Slot.VISION
        val saved = fixture.settings.load().copy(apiKey = "independent-secret-canary")
        fixture.controller.independentModels.save(slot, saved, null, true, true)
        val binding = fixture.controller.independentModels.record(slot)
        openSettings(); node("settings-search").performTextReplacement("辅助视觉模型")
        reveal("settings-row:辅助视觉模型", "设置").performClick()
        ui.waitUntil(5_000) {
            ui.runOnIdle { activity.window.decorView.findViewWithTag<View>("independent-model-secret") != null }
        }
        mainThread {
            val key = checkNotNull(activity.window.decorView.findViewWithTag<EditText>("independent-model-secret"))
            val button = checkNotNull(activity.window.decorView.findViewWithTag<Button>("independent-model-secret-visibility"))
            val images = checkNotNull(activity.window.decorView.findViewWithTag<Spinner>("independent-model-image-capability"))
            assertTrue(key.transformationMethod is PasswordTransformationMethod)
            assertFalse(key.isSaveEnabled); key.setSelection(2, 5)
            assertTrue(button.performClick())
            assertNull(key.transformationMethod); assertEquals(saved.apiKey, key.text.toString())
            assertEquals(2, key.selectionStart); assertEquals(5, key.selectionEnd)
            assertEquals(1, images.selectedItemPosition)
        }
        realPauseResume(activity)
        mainThread {
            val key = checkNotNull(activity.window.decorView.findViewWithTag<EditText>("independent-model-secret"))
            val images = checkNotNull(activity.window.decorView.findViewWithTag<Spinner>("independent-model-image-capability"))
            assertTrue(key.transformationMethod is PasswordTransformationMethod)
            assertEquals(saved.apiKey, key.text.toString()); assertEquals(1, images.selectedItemPosition)
        }
        assertEquals(binding, fixture.controller.independentModels.record(slot))
        assertEquals(saved, fixture.controller.independentModels.editing(slot).settings)
        assertEquals(0, fixture.opens.get())
    }
}
