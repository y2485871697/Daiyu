package app.daiyu.android

import android.view.KeyEvent
import app.daiyu.android.assistants.create
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

    private fun dismissOutside(fromSettings: Boolean) {
        val scrim = node("preview-dialog-scrim")
        val bounds = scrim.fetchSemanticsNode().boundsInRoot
        val sheet = node("assistant-sheet").fetchSemanticsNode().boundsInRoot
        val gap = sheet.top - bounds.top
        assertTrue("Assistant sheet must leave an outside hit region", gap > 24f)
        scrim.performTouchInput { click(Offset(center.x, gap / 2f)) }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("assistant-sheet").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        awaitUniqueTag(if (fromSettings) "page:设置" else "chat-draft")
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
        awaitDialogEnter(current)
        dismissIme()
        awaitDialogImeFrame(current, visible = false)
        if (system) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        } else ui.onNodeWithContentDescription("返回").performClick()
        ui.waitForIdle()
    }

    private fun selectBoundConversation() {
        val bound = fixture.controller.assistantStore.create("Existing bound assistant")
        val conversation = fixture.database.create(bound.id)
        mainThread { activity.select(conversation) }
        awaitUniqueTag("chat-draft")
        assertEquals(bound.id, fixture.database.assistantId(conversation.id))
        assertFalse(bound.id == fixture.controller.assistantStore.selectedId.value)
    }

    fun testNewAssistantOpensEditorAndSavesWithoutChangingBinding() {
        selectBoundConversation()
        val store = fixture.controller.assistantStore
        val selected = store.selectedId.value
        val bindings = fixture.database.conversations().associate { it.id to fixture.database.assistantId(it.id) }
        for (fromSettings in listOf(false, true)) {
            openPicker(fromSettings)
            val originTag = if (fromSettings) "page:设置" else "chat-draft"
            val origin = node(originTag).fetchSemanticsNode().id
            val before = store.list()
            if (fromSettings) {
                node("assistant-search-input").performTextInput("no matching assistant")
                dismissIme()
            }
            val newButton = node("assistant-new").assertIsDisplayed().assertIsEnabled()
            val staleCreate = retainedClickCallback(newButton)
            newButton.performTouchInput { click() }
            awaitUniqueTag("assistant-editor")
            awaitNodeWindowFocus(node("assistant-editor"))
            awaitDialogEnter(node("assistant-editor"))
            mainThread { staleCreate() }
            ui.onNodeWithTag("assistant-list").assertDoesNotExist()
            assertEquals(origin, node(originTag).fetchSemanticsNode().id)
            assertEquals(before.size + 1, store.list().size)
            val created = store.list().single { candidate -> before.none { it.id == candidate.id } }
            assertEquals("新助手", created.name)
            assertEquals("", created.prompt)
            assertEquals("", created.description)
            assertNull(created.avatarFileName)
            assertTrue(created.memoryEnabled)
            assertTrue(created.enabledSkillIds.isEmpty())
            node("assistant-edit-save").assertIsNotEnabled()
            val name = "Created from $originTag"
            node("assistant-edit-name").scrollToVisible()
                .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(name)) }
            dismissIme()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithTag("assistant-edit-save").fetchSemanticsNodes()
                    .singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
            }
            node("assistant-edit-save").assertIsEnabled().performClick()
            awaitUniqueTag("assistant-list")
            if (fromSettings) {
                node("assistant-search-input").performTextClearance()
                dismissIme()
            }
            val list = node("assistant-list")
            awaitDialogTouchReady(list)
            list.performScrollToKey(created.id)
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            node("assistant-item-${created.id}").assertIsDisplayed()
            node("assistant-item-${created.id}").assert(hasText(name))
            val restored = app.daiyu.android.assistants.SqliteAssistantStore(fixture.database)
            assertEquals(created.copy(name = name), restored.read(created.id))
            assertEquals(selected, restored.selectedId.value)
            assertEquals(before.size + 1, restored.list().size)
            node("assistant-done").performClick()
            awaitUniqueTag(originTag)
            assertEquals(origin, node(originTag).fetchSemanticsNode().id)
            if (fromSettings) backToChat(activity)
        }
        assertEquals(bindings, fixture.database.conversations().associate { it.id to fixture.database.assistantId(it.id) })
        assertEquals(0, fixture.opens.get())
    }

    fun testNewAssistantCancelAndOutsideKeepCreatedDefaultsWithoutSavingDraft() {
        selectBoundConversation()
        val store = fixture.controller.assistantStore
        val selected = store.selectedId.value
        val bindings = fixture.database.conversations().associate { it.id to fixture.database.assistantId(it.id) }
        for (fromSettings in listOf(false, true)) for (exit in listOf("cancel", "header", "system", "outside")) {
            openPicker(fromSettings)
            val before = store.list()
            node("assistant-new").assertIsDisplayed().assertIsEnabled().performClick()
            awaitUniqueTag("assistant-editor")
            awaitNodeWindowFocus(node("assistant-editor"))
            awaitDialogEnter(node("assistant-editor"))
            val created = store.list().single { candidate -> before.none { it.id == candidate.id } }
            node("assistant-edit-name").scrollToVisible()
                .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Unsaved new assistant")) }
            dismissIme()
            awaitDialogImeFrame(node("assistant-editor"), visible = false)
            when (exit) {
                "outside" -> {
                    dismissOutside(fromSettings)
                    if (fromSettings) {
                        node("settings-row:助手").assertIsDisplayed().assertIsEnabled().performClick()
                        awaitUniqueTag("assistant-list")
                    } else openPicker(fromSettings = false)
                }
                "cancel" -> node("assistant-edit-cancel").performClick()
                else -> back(system = exit == "system")
            }
            awaitUniqueTag("assistant-list")
            assertEquals(before.size + 1, store.list().size)
            assertEquals(created, store.read(created.id))
            assertEquals(selected, store.selectedId.value)
            ui.onNodeWithText("Unsaved new assistant").assertDoesNotExist()
            actions(created.id)
            edit()
            node("assistant-edit-name").assertTextEquals(created.name)
            node("assistant-edit-cancel").performClick()
            awaitUniqueTag("assistant-action-edit")
            back(system = false)
            awaitUniqueTag("assistant-list")
            node("assistant-done").performClick()
            awaitUniqueTag(if (fromSettings) "page:设置" else "chat-draft")
            if (fromSettings) backToChat(activity)
        }
        assertEquals(bindings, fixture.database.conversations().associate { it.id to fixture.database.assistantId(it.id) })
        assertEquals(0, fixture.opens.get())
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
    fun testOutsideDismissAtEveryAssistantLevelKeepsOriginalPageAndReopensList() {
        val store = fixture.controller.assistantStore
        val profile = store.assistants.value.first()
        val before = fixture.database.exportBackup()
        val selected = store.selectedId.value
        for (fromSettings in listOf(false, true)) for (level in 0..2) {
            openPicker(fromSettings)
            val originalPage = if (fromSettings) "page:设置" else "chat-draft"
            val originalNode = node(originalPage).fetchSemanticsNode().id
            ui.onNodeWithTag("assistant-action-edit").assertDoesNotExist()
            ui.onNodeWithTag("assistant-editor").assertDoesNotExist()
            if (level >= 1) actions(profile.id)
            if (level == 2) {
                edit()
                node("assistant-edit-name").scrollToVisible()
                    .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Unsaved outside name")) }
                dismissIme()
            }
            dismissOutside(fromSettings)
            assertEquals(originalNode, node(originalPage).fetchSemanticsNode().id)
            ui.onNodeWithTag("assistant-sheet").assertDoesNotExist()
            if (fromSettings) {
                node("settings-row:助手").assertIsDisplayed().assertIsEnabled().performClick()
                awaitUniqueTag("assistant-list")
            } else {
                node("chat-assistant").assertIsDisplayed()
                ui.onNodeWithTag("page:设置").assertDoesNotExist()
                openPicker(fromSettings = false)
            }
            ui.onNodeWithTag("assistant-action-edit").assertDoesNotExist()
            ui.onNodeWithTag("assistant-editor").assertDoesNotExist()
            ui.onNodeWithText("Unsaved outside name").assertDoesNotExist()
            node("assistant-done").performClick()
            ui.waitUntil(5_000) {
                ui.onAllNodesWithTag("assistant-sheet").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
            }
            assertEquals(originalNode, node(originalPage).fetchSemanticsNode().id)
            if (fromSettings) backToChat(activity)
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
            dismissOutside(fromSettings)
            assertEquals(original, node(tag).fetchSemanticsNode().id)
            if (fromSettings) backToChat(activity)
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

}
