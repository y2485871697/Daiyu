package app.daiyu.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import org.json.JSONObject

class RequestBodyFieldsActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private fun beginField(name: String, type: String, value: String) {
        reveal("model-body-fields").performClick()
        node("model-body-field-add").performScrollTo().performClick()
        node("settings-field:字段名").performScrollTo().performTextReplacement(name)
        node("settings-radio:$type").performScrollTo().performClick()
        node("settings-field:字段值").performScrollTo().performTextReplacement(value)
        node("model-body-field-apply").performScrollTo()
    }
    fun testTypedFieldEditsOnlyDraftThenPersistsThroughTheExistingSave() {
        val before = fixture.settings.load()
        setDraft("field editor unsent draft"); openModels()
        beginField("metadata", "对象", """{"marker":true,"numeric":"123"}""")
        node("model-body-field-apply").performClick()
        assertEquals(before, fixture.settings.load())
        saveModel()
        val body = JSONObject(fixture.settings.load().requestOptions.customBody)
        assertTrue(body.getJSONObject("metadata").getBoolean("marker"))
        assertEquals("123", body.getJSONObject("metadata").get("numeric"))
        assertEquals(before.copy(requestOptions = before.requestOptions.copy(customBody = body.toString())), fixture.settings.load())
        openModels(); reveal("model-body-fields").performClick()
        node("model-body-field:0").assertExists()
        node("model-body-field-delete:0").performScrollTo().performClick()
        assertTrue(JSONObject(fixture.settings.load().requestOptions.customBody).has("metadata"))
        saveModel(); assertFalse(JSONObject(fixture.settings.load().requestOptions.customBody).has("metadata"))
        assertEquals(0, fixture.opens.get())
    }
    fun testPausedFieldApplyCapabilityCannotChangeTheReopenedDraftOrSave() {
        val before = fixture.settings.load()
        openModels(); beginField("metadata", "对象", """{"marker":true}""")
        val apply = checkNotNull(node("model-body-field-apply").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        realPauseResume(activity, atPause = { apply() })
        mainThread { apply() }
        assertEquals(before, fixture.settings.load())
        modelField("自定义请求体").assertEditableText(before.requestOptions.customBody)
        node("model-body-field-apply").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
}
