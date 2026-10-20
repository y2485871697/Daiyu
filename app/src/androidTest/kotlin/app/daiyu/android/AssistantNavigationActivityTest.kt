package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString

class AssistantNavigationActivityTest : WiredActivityTestCase() {
    private fun openPicker(fromSettings: Boolean) {
        if (fromSettings) {
            openSettings()
            openRetainedSettingsEntry("assistants", "assistant-list")
        } else {
            node("chat-assistant").performClick()
            awaitUniqueTag("assistant-list")
        }
    }

    private fun actions(id: String) {
        node("assistant-list").performScrollToNode(hasTestTag("assistant-item-$id"))
        node("assistant-item-$id").performTouchInput { longClick() }
        awaitUniqueTag("assistant-action-edit")
        ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
    }

    private fun edit() {
        node("assistant-action-edit").performClick()
        awaitUniqueTag("assistant-editor")
        ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
        ui.onNodeWithTag("assistant-action-edit").assertDoesNotExist()
    }

    private fun back(system: Boolean) {
        if (system) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        else ui.onNodeWithContentDescription("返回").performClick()
        ui.waitForIdle()
    }

    fun testHeaderAndSystemBackReturnThroughActionsAndListWithoutSaving() {
        val profile = fixture.controller.assistantStore.assistants.value.first()
        val before = fixture.database.exportBackup()
        val selected = fixture.controller.assistantStore.selectedId.value
        for (fromSettings in listOf(true, false)) {
            openPicker(fromSettings)
            for (system in listOf(false, true)) {
                actions(profile.id)
                edit()
                node("assistant-edit-name").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Unsaved name")) }
                dismissIme()
                back(system)
                awaitUniqueTag("assistant-action-edit")
                ui.onNodeWithTag("assistant-editor").assertDoesNotExist()
                ui.onNodeWithText("Unsaved name").assertDoesNotExist()
                assertEquals(profile, fixture.controller.assistantStore.read(profile.id))
                back(system)
                awaitUniqueTag("assistant-list")
                node("assistant-item-${profile.id}").assertExists()
            }
            actions(profile.id)
            edit()
            node("assistant-edit-cancel").performClick()
            awaitUniqueTag("assistant-action-edit")
            ui.onNodeWithText(activity.getString(R.string.assistant_back_to_list)).performClick()
            awaitUniqueTag("assistant-list")
            node("assistant-done").performClick()
            ui.onNodeWithTag("assistant-sheet").assertDoesNotExist()
            if (fromSettings) {
                node("page:设置").assertExists()
                backToChat(activity)
            } else node("chat-draft").assertExists()
        }
        assertEquals(selected, fixture.controller.assistantStore.selectedId.value)
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testSaveReturnsToActionsWithUpdatedIdentityAndThenOriginalSettingsPage() {
        val store = fixture.controller.assistantStore
        val profile = store.assistants.value.first()
        val selected = store.selectedId.value
        openPicker(fromSettings = true)
        actions(profile.id)
        edit()
        node("assistant-edit-name").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Renamed assistant")) }
        dismissIme()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("assistant-edit-save").fetchSemanticsNodes()
                .singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
        }
        node("assistant-edit-save").performClick()
        awaitUniqueTag("assistant-action-edit")
        ui.onNodeWithText("Renamed assistant").assertIsDisplayed()
        assertEquals("Renamed assistant", store.read(profile.id)?.name)
        assertEquals(selected, store.selectedId.value)
        back(system = false)
        awaitUniqueTag("assistant-list")
        ui.onNodeWithText("Renamed assistant").assertIsDisplayed()
        node("assistant-done").performClick()
        node("page:设置").assertExists()
        assertEquals(0, fixture.opens.get())
    }
}
