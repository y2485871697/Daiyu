package app.daiyu.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.delegation.ReasoningEffort

class DefaultReasoningSettingsActivityTest : WiredActivityTestCase() {
    private fun revealDefault(): SemanticsNodeInteraction {
        node("settings-search")
            .performSemanticsAction(SemanticsActions.SetText) { setText ->
                assertTrue(setText(AnnotatedString("默认启用推理")))
            }
            .assertEditableText("默认启用推理")
        return reveal("settings-row:默认启用推理", "设置")
    }

    fun testExplicitStrengthPersistsAcrossRecreationAndDoesNotOverwriteExistingConversation() {
        val conversation = fixture.database.create("default")
        fixture.database.draft(conversation.id, "reasoning settings draft")
        fixture.controller.reasoningPreference.set(conversation.id.value, ReasoningEffort.HIGH)
        mainThread { activity.select(conversation) }
        awaitControl("selected readers finish") { !fixture.controller.dataBusy }
        val before = fixture.database.exportBackup()
        openSettings()
        revealDefault().assertIsOff()
        node("reasoning-default-select").assertDoesNotExist()
        ui.onNodeWithText("选择默认推理强度").assertDoesNotExist()
        revealDefault().performClick()
        assertEquals(ReasoningEffort.DEFAULT, fixture.controller.reasoningPreference.snapshot(null))
        ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performTouchInput {
            click(Offset(width * 0.2f, height / 2f))
        }
        revealDefault().assertIsOn()
        assertEquals(ReasoningEffort.LOW, fixture.controller.reasoningPreference.snapshot(null))
        assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(conversation.id.value))
        assertEquals(before, fixture.database.exportBackup())
        backToChat(activity); assertDraft("reasoning settings draft")
        activity = recreateActivity(activity)
        openSettings(); revealDefault().assertIsOn().performClick()
        revealDefault().assertIsOff()
        assertEquals(ReasoningEffort.DEFAULT, fixture.controller.reasoningPreference.snapshot(null))
        assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(conversation.id.value))
        backToChat(activity); assertDraft("reasoning settings draft")
        assertEquals(0, fixture.opens.get())
    }

    fun testCancelingStrengthSelectionDoesNotSaveOrSend() {
        setDraft("cancelled default choice")
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.loadForEditing().settings
        openSettings(); revealDefault().assertIsOff().performClick()
        ui.onNodeWithText(activity.getString(R.string.subagent_reasoning_picker_title)).assertExists()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText(activity.getString(R.string.subagent_reasoning_picker_title))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        ui.onNodeWithText(activity.getString(R.string.subagent_reasoning_picker_title)).assertDoesNotExist()
        node("page:设置").assertExists()
        revealDefault().assertIsOff()
        assertEquals(ReasoningEffort.DEFAULT, fixture.controller.reasoningPreference.snapshot(null))
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(settings, fixture.settings.loadForEditing().settings)
        assertEquals(0, fixture.opens.get())
        backToChat(activity); assertDraft("cancelled default choice")
    }
}
