package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString

class AssistantNavigationActivityTest : WiredActivityTestCase() {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun openPicker(fromSettings: Boolean) {
        if (fromSettings) {
            openSettings()
            node("page:设置").scrollToDescendant(hasTestTag("settings-row:助手"))
            node("settings-row:助手").assertIsDisplayed().assertIsEnabled().performClick()
            awaitUniqueTag("assistant-list")
        } else {
            val entry = node("chat-assistant").assertIsDisplayed().assertIsEnabled()
            awaitNodeWindowFocus(entry)
            val target = entry.fetchSemanticsNode()
            val view = (target.root as androidx.compose.ui.platform.ViewRootForTest).view
            val token = view.windowToken
            fun screenBounds() = target.boundsInRoot.translate(target.positionOnScreen - target.positionInRoot)
            val settled = awaitStableWindowFrame(view) {
                if (view.windowToken != token || !target.layoutInfo.isPlaced ||
                    !target.layoutInfo.coordinates.isAttached) null else screenBounds().takeIf {
                    it.width > 0f && it.height > 0f &&
                        listOf(it.left, it.top, it.right, it.bottom).all { value -> value.isFinite() }
                }
            }
            assertEquals("Assistant entry moved before touch", settled, ui.runOnIdle { screenBounds() })
            entry.assertIsDisplayed().assertIsEnabled().performClick()
            awaitUniqueTag("assistant-list")
        }
    }

    private fun dismissOutside() {
        val scrim = node("preview-dialog-scrim")
        val bounds = scrim.fetchSemanticsNode().boundsInRoot
        val sheet = node("assistant-sheet").fetchSemanticsNode().boundsInRoot
        val gap = sheet.top - bounds.top
        assertTrue("Assistant sheet must leave an outside hit region", gap > 24f)
        scrim.performTouchInput { click(Offset(center.x, gap / 2f)) }
        awaitUniqueTag("chat-draft")
    }

    private fun actions(id: String) {
        node("assistant-list").scrollToDescendant(hasTestTag("assistant-item-$id"))
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
        val foreground = listOf("assistant-editor", "assistant-action-edit", "assistant-list").first { tag ->
            ui.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        val current = node(foreground)
        awaitNodeWindowFocus(current)
        dismissIme()
        awaitImeFrame(current, visible = false)
        if (system) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        } else ui.onNodeWithContentDescription("返回").performClick()
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
                node("assistant-edit-name").scrollToVisible()
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
        node("assistant-edit-name").scrollToVisible()
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
    fun testOutsideDismissAtEveryAssistantLevelReturnsToChatAndReopensList() {
        val store = fixture.controller.assistantStore
        val profile = store.assistants.value.first()
        val before = fixture.database.exportBackup()
        val selected = store.selectedId.value
        for (fromSettings in listOf(false, true)) for (level in 0..2) {
            openPicker(fromSettings)
            ui.onNodeWithTag("assistant-action-edit").assertDoesNotExist()
            ui.onNodeWithTag("assistant-editor").assertDoesNotExist()
            if (level >= 1) actions(profile.id)
            if (level == 2) {
                edit()
                node("assistant-edit-name").scrollToVisible()
                    .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Unsaved outside name")) }
                dismissIme()
            }
            dismissOutside()
            node("chat-assistant").assertIsDisplayed()
            ui.onNodeWithTag("assistant-sheet").assertDoesNotExist()
            ui.onNodeWithTag("page:设置").assertDoesNotExist()
            openPicker(fromSettings = false)
            ui.onNodeWithTag("assistant-action-edit").assertDoesNotExist()
            ui.onNodeWithTag("assistant-editor").assertDoesNotExist()
            ui.onNodeWithText("Unsaved outside name").assertDoesNotExist()
            node("assistant-done").performClick()
            assertEquals(profile, store.read(profile.id))
        }
        assertEquals(selected, store.selectedId.value)
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testEditorKeepsOriginalPageComposed() {
        val profile = fixture.controller.assistantStore.assistants.value.first()
        val before = fixture.database.exportBackup()
        for (fromSettings in listOf(false, true)) {
            openPicker(fromSettings)
            val tag = if (fromSettings) "page:设置" else "chat-draft"
            val original = node(tag).fetchSemanticsNode().id
            for (system in listOf(false, true)) {
                actions(profile.id)
                edit()
                assertEquals(original, node(tag).fetchSemanticsNode().id)
                back(system)
                awaitUniqueTag("assistant-action-edit")
                assertEquals(original, node(tag).fetchSemanticsNode().id)
                back(system)
                awaitUniqueTag("assistant-list")
                assertEquals(original, node(tag).fetchSemanticsNode().id)
            }
            dismissOutside()
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

}
