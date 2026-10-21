package app.daiyu.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.delegation.ReasoningEffort

class DefaultReasoningSettingsActivityTest : WiredActivityTestCase() {
    private fun assertRemovedFromHome() {
        node("settings-search").assertDoesNotExist()
        node("settings-row:默认启用推理").assertDoesNotExist()
        node("reasoning-default-select").assertDoesNotExist()
    }

    fun testRemovedEntryIsAbsentFromHomeWithoutChangingSettingsOrDraft() {
        setDraft("removed reasoning entry draft")
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.loadForEditing().settings
        val default = fixture.controller.reasoningPreference.snapshot(null)
        openSettings()
        node("settings-row:外观与主题").assertIsDisplayed()
        node("settings-row:默认启用推理").assertDoesNotExist()
        assertRemovedFromHome()
        node("settings-search-clear").assertDoesNotExist()
        node("settings-row:外观与主题").assertIsDisplayed()
        node("settings-row:默认启用推理").assertDoesNotExist()
        backToChat(activity); assertDraft("removed reasoning entry draft")
        assertEquals(default, fixture.controller.reasoningPreference.snapshot(null))
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(settings, fixture.settings.loadForEditing().settings)
        assertEquals(0, fixture.opens.get())
    }

    fun testRemovingEntryPreservesSavedDefaultAndConversationPreferenceAcrossRecreation() {
        val conversation = fixture.database.create("default")
        fixture.database.draft(conversation.id, "reasoning settings draft")
        fixture.controller.reasoningPreference.set(null, ReasoningEffort.LOW)
        fixture.controller.reasoningPreference.set(conversation.id.value, ReasoningEffort.HIGH)
        mainThread { activity.select(conversation) }
        awaitControl("selected readers finish") { !fixture.controller.dataBusy }
        val before = fixture.database.exportBackup()
        repeat(2) { visit ->
            openSettings()
            node("settings-row:默认启用推理").assertDoesNotExist()
            assertRemovedFromHome()
            backToChat(activity); assertDraft("reasoning settings draft")
            assertEquals(ReasoningEffort.LOW, fixture.controller.reasoningPreference.snapshot(null))
            assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(conversation.id.value))
            assertEquals(before, fixture.database.exportBackup())
            if (visit == 0) activity = recreateActivity(activity)
        }
        assertEquals(0, fixture.opens.get())
    }
}
