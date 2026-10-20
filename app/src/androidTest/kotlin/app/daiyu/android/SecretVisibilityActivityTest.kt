package app.daiyu.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsActions
import android.content.ContextWrapper
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
        modelField("API Key").performSemanticsAction(SemanticsActions.SetText) { setText ->
            assertTrue(setText(AnnotatedString("visibility-secret-canary")))
        }
        assertMasked()
        toggle().assertContentDescriptionEquals("显示密钥").performClick()
        modelField("API Key").assertEditableText("visibility-secret-canary")
        toggle().assertContentDescriptionEquals("隐藏密钥").performClick(); assertMasked()
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

    fun testIndependentSelectorDoesNotExposeSecretsOrMutateStoredBinding() {
        val slot = IndependentModelSettings.Slot.VISION
        val saved = fixture.settings.load().copy(apiKey = "independent-secret-canary")
        fixture.controller.independentModels.save(slot, saved, null, true, true)
        val binding = fixture.controller.independentModels.record(slot)
        openSettings()
        node("settings-search")
            .performSemanticsAction(SemanticsActions.SetText) { setText ->
                assertTrue(setText(AnnotatedString("辅助视觉模型")))
            }
            .assertEditableText("辅助视觉模型")
        reveal("settings-row:辅助视觉模型", "设置").performClick()
        awaitUniqueTag("independent-model-vision")
        node("independent-model-configure").assertIsDisplayed()
        ui.onNodeWithText(saved.apiKey).assertDoesNotExist()
        node("settings-field:API 密钥").assertDoesNotExist()
        node("independent-model-secret-visibility").assertDoesNotExist()
        node("independent-model-configure").performClick()
        awaitUniqueTag("settings-model-picker")
        ui.onNodeWithText(saved.apiKey).assertDoesNotExist()
        realPauseResume(activity)
        ui.onNodeWithText(saved.apiKey).assertDoesNotExist()
        assertEquals(binding, fixture.controller.independentModels.record(slot))
        assertEquals(saved, fixture.controller.independentModels.editing(slot).settings)
        assertEquals(0, fixture.opens.get())
    }
}
