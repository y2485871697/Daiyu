"""Assistant source contracts; device regressions await the next approved build."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
ASSISTANTS = SRC / "ui/screens/assistants"


class AssistantNavigationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = (SRC / "MainActivity.kt").read_text()
        cls.preview = (SRC / "ui/components/PreviewDialog.kt").read_text()
        cls.shell = (ASSISTANTS / "AssistantPreviewComponents.kt").read_text()
        cls.picker = (ASSISTANTS / "AssistantPickerDialogs.kt").read_text()
        cls.editor = (ASSISTANTS / "AssistantEditScreen.kt").read_text()

    def test_editor_overlays_original_content_inside_page_back_host(self):
        self.assertIn('routeKey = if (retainedPage == "chat" && sidebarVisible) "chat-sidebar" else retainedPage,', self.main)
        self.assertIn('contentKey = when (retainedPage) {', self.main)
        self.assertIn('"assistant-edit" -> assistantEditReturnPage', self.main)
        self.assertIn('"context-management" -> "chat"', self.main)
        route = self.main.index('when (if (retainedPage == "assistant-edit") assistantEditReturnPage else retainedPage)')
        overlay = self.main.index('if (retainedPage == "assistant-edit") assistantEditingId?.let')
        terminal = self.main.index('                TerminalTasksSurface()', route)
        self.assertLess(route, overlay)
        self.assertLess(overlay, terminal)
        self.assertNotIn('"assistant-edit" -> assistantEditingId', self.main)
        self.assertIn('\n                }\n                TerminalTasksSurface()', self.main[overlay:terminal + 60])

    def test_outside_dismiss_is_separate_from_system_back(self):
        self.assertIn('onOutsideDismiss: () -> Unit = onDismiss', self.preview)
        self.assertRegex(self.preview,
            r'Dialog\(onDismissRequest = \{\s*'
            r'if \(dismissEnabled\) motion\?\.requestDismiss\(BottomSheetDismissSource\.System\) \?: onDismiss\(\)')
        self.assertRegex(self.preview,
            r'onClick = \{\s*'
            r'if \(dismissEnabled\) motion\?\.requestDismiss\(BottomSheetDismissSource\.Outside\) \?: onOutsideDismiss\(\)')
        self.assertIn('DialogMotionProvider(motion, onDismiss, onOutsideDismiss = onOutsideDismiss)', self.preview)
        motion = (SRC / "ui/components/DialogMotion.kt").read_text()
        for token in ('val latestDismiss by rememberUpdatedState(onDismiss)',
                      'val latestOutsideDismiss by rememberUpdatedState(onOutsideDismiss)',
                      'BottomSheetDismissSource.System -> latestDismiss()',
                      'BottomSheetDismissSource.Outside -> latestOutsideDismiss()'):
            self.assertIn(token, motion)
        for token in ('dismissEnabled: Boolean = true', 'dismissOnBackPress = dismissEnabled',
                      'dismissOnClickOutside = dismissEnabled', '.clickable(enabled = dismissEnabled'):
            self.assertIn(token, self.preview)
        self.assertIn('testTag("preview-dialog-scrim")', self.preview)
        self.assertIn('onOutsideDismiss = onOutsideDismiss', self.shell)

    def test_full_dismiss_clears_flow_and_preserves_origin_page(self):
        close = self.main.split('private fun dismissAssistantFlow() {', 1)[1].split('private var assistantAvatarRevision', 1)[0]
        for assignment in ('assistantEditingId = null', 'assistantPicker = false',
                           'assistantSelectionOwner = null', 'assistantEditReturnPage = "chat"',
                           'assistantEditReturnToSidebar = false'):
            self.assertIn(assignment, close)
        self.assertIn('dismissSidebar()', close)
        self.assertIn('val returnPage = if (retainedPage == "assistant-edit") assistantEditReturnPage else retainedPage', close)
        self.assertLess(close.index('val returnPage ='), close.index('assistantEditReturnPage = "chat"'))
        self.assertIn('if (returnPage == "chat") returnToChat() else retainedPage = returnPage', close)
        self.assertEqual(1, close.count('returnToChat()'))

    def test_picker_reopens_with_fresh_owner_instead_of_child_state(self):
        opening = self.main.split('private fun openAssistantPicker(', 1)[1].split('private fun assistantOwnerMatches', 1)[0]
        self.assertIn('assistantSelectionOwner=AssistantSelectionOwner(', opening)
        self.assertLess(opening.index('assistantSelectionOwner='), opening.index('assistantPicker=true'))
        self.assertIn('assistantSelectionOwner?.let { owner -> key(owner)', self.main)
        self.assertIn('onOutsideDismiss = { if(assistantSelectionOwner===owner) dismissAssistantFlow() }', self.main)
        self.assertIn('if (!show) { searchFocused = false; searchQuery = ""; actionProfileId = null; deleteProfile = null }', self.picker)

    def test_picker_subpages_forward_outside_callback_but_keep_back(self):
        self.assertEqual(4, self.picker.count('onOutsideDismiss: () -> Unit = onDismiss'))
        self.assertGreaterEqual(self.picker.count('onOutsideDismiss = onOutsideDismiss'), 6)
        self.assertIn('onBack = onDismiss', self.picker)
        self.assertIn('onDismiss = { actionProfileId = null }', self.picker)
        self.assertIn('onDismiss = { deleteProfile = null }', self.picker)

    def test_editor_drops_session_before_outside_callback(self):
        self.assertIn('onOutsideDismiss = { session.close(); onOutsideDismiss() }', self.editor)
        self.assertIn('val close = { session.close(); onBack() }', self.editor)
        self.assertIn('onSaved(assistantId); session.close(); onBack()', self.editor)
        self.assertIn('owner === session && session.isActive && !saving', self.editor)

    def test_avatar_card_is_above_name_and_replaces_label_avatar(self):
        avatar = self.editor.index('testTag("assistant-edit-avatar")')
        name = self.editor.index('AssistantPreviewField(name,')
        self.assertLess(avatar, name)
        self.assertIn('size = 64.dp * s', self.editor[avatar:name])
        self.assertIn('testTag("assistant-avatar-name")', self.editor[avatar:name])
        self.assertIn('picker.launch(PickVisualMediaRequest', self.editor[avatar:name])
        self.assertNotIn('labelAction =', self.editor)
        self.assertEqual(1, self.editor.count('AssistantAvatar('))

    def test_skills_legend_has_no_opaque_mask(self):
        section = self.shell.split('internal fun AssistantPreviewSection(', 1)[1]
        legend = section.split('if (title != null) Box(', 1)[1]
        self.assertNotIn('.background(', legend)
        self.assertNotIn('Color.White', legend)
        self.assertIn('clipOp = ClipOp.Difference', section)

    def test_chat_entry_waits_for_native_geometry_before_one_real_click(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/AssistantNavigationActivityTest.kt').read_text()
        opening = source.split('private fun openPicker(', 1)[1].split('private fun dismissOutside(', 1)[0]
        chat = opening.split('        } else {', 1)[1]
        for token in ('assertIsDisplayed().assertIsEnabled()', 'awaitNodeWindowFocus(entry)',
                      'awaitStableWindowFrame(view)', 'view.windowToken != token',
                      '!target.layoutInfo.isPlaced', '!target.layoutInfo.coordinates.isAttached',
                      'target.boundsInRoot.translate(target.positionOnScreen - target.positionInRoot)',
                      'it.width > 0f && it.height > 0f', 'value.isFinite()',
                      'ui.runOnIdle { screenBounds() }', 'awaitUniqueTag("assistant-list")'):
            self.assertIn(token, chat)
        self.assertLess(chat.index('awaitNodeWindowFocus(entry)'), chat.index('awaitStableWindowFrame(view)'))
        self.assertLess(chat.index('awaitStableWindowFrame(view)'), chat.index('entry.assertIsDisplayed().assertIsEnabled().performClick()'))
        final_check = 'assertEquals("Assistant entry moved before touch", settled, ui.runOnIdle { screenBounds() })'
        self.assertIn(final_check, chat)
        self.assertLess(chat.index(final_check), chat.index('entry.assertIsDisplayed().assertIsEnabled().performClick()'))
        self.assertEqual(1, chat.count('.performClick()'))
        for forbidden in ('performSemanticsAction', 'Thread.sleep', 'repeat(', 'openAssistantPicker('):
            self.assertNotIn(forbidden, chat)
        oracle = source.split('fun testEditorKeepsOriginalPageComposed()', 1)[1]
        self.assertIn('for (fromSettings in listOf(false, true))', oracle)
        self.assertIn('for (system in listOf(false, true))', oracle)
        self.assertEqual(4, oracle.count('assertEquals(original, node(tag).fetchSemanticsNode().id)'))
        self.assertIn('assertEquals(before, fixture.database.exportBackup())', oracle)
        self.assertIn('assertEquals(0, fixture.opens.get())', oracle)

    def test_device_regressions_cover_outside_reopen_background_avatar_and_transparency(self):
        tests = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'
        navigation = (tests / 'AssistantNavigationActivityTest.kt').read_text()
        for name in ('testOutsideDismissAtEveryAssistantLevelKeepsOriginalPageAndReopensList',
                     'testEditorKeepsOriginalPageComposed',
                     'testHeaderAndSystemBackReturnThroughActionsAndListWithoutSaving'):
            self.assertIn('fun ' + name, navigation)
        self.assertIn('scrim.performTouchInput', navigation)
        outside = navigation.split('fun testOutsideDismissAtEveryAssistantLevelKeepsOriginalPageAndReopensList()', 1)[1].split('fun testEditorKeepsOriginalPageComposed()', 1)[0]
        for token in ('val originalPage = if (fromSettings) "page:设置" else "chat-draft"',
                      'val originalNode = node(originalPage).fetchSemanticsNode().id',
                      'dismissOutside(fromSettings)',
                      'assertEquals(originalNode, node(originalPage).fetchSemanticsNode().id)',
                      'node("settings-row:助手").assertIsDisplayed().assertIsEnabled().performClick()',
                      'if (fromSettings) backToChat(activity)'):
            self.assertIn(token, outside)
        dismiss = navigation.split('private fun dismissOutside(', 1)[1].split('private fun actions(', 1)[0]
        self.assertIn('fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()', dismiss)
        self.assertIn('awaitUniqueTag(if (fromSettings) "page:设置" else "chat-draft")', dismiss)
        visual = (tests / 'AssistantEditVisualTest.kt').read_text()
        self.assertIn('fun testAvatarCardIsAboveNameAndTracksNameDraft', visual)
        self.assertIn('fun testSkillsLegendPaddingIsTransparentInBothThemes', visual)
        self.assertIn('assertType("Visual editor", 14.sp, 21.sp, "assistant-edit-name")', visual)


if __name__ == '__main__':
    unittest.main()
