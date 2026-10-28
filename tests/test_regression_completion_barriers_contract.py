"""Keep backend, UI, and native-window completion distinct in regression fixtures."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
TESTS = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'

class RegressionCompletionBarriersContractTest(unittest.TestCase):
    def test_workspace_alias_input_retains_canonical_output_and_exact_safety_oracles(self):
        text = (TESTS / 'ConversationWorkspacesActivityTest.kt').read_text()
        method = text.split('fun testAddSwitchRemoveAndReopenPreserveOtherConversationAndActualFiles()',1)[1].split('fun testRealActivity',1)[0]
        for name in ('first', 'second', 'third'):
            self.assertIn('ConversationWorkspaceBinding.Private(' + name + '.canonicalPath)', method)
            self.assertIn(name + '.path', method)
        for value in ('assertEquals("keep", marker.readText())', 'assertNull(fixture.database.workspaceBinding(other))',
                      'assertNull(fixture.database.workspaceBinding(null))', 'assertEquals(current, reopened.workspaceBinding(owner))',
                      'assertEquals(first.canonicalFile, frozen.resolved.hostDirectory)'):
            self.assertIn(value, method)
        self.assertIn('ConversationWorkspaceBinding.Private(first.path), updated.active', text)

    def test_retirement_waits_for_ui_before_final_exact_count_and_queue_assertions(self):
        text = (TESTS / 'ui/screens/settings/LinuxInstallClickTest.kt').read_text()
        method = text.split('private fun finishRetirement(',1)[1].split('fun testOneClick',1)[0]
        self.assertIn('ui.onAllNodesWithTag("linux-install-progress")', method)
        self.assertLess(method.index('prepared == null'), method.index('ui.onAllNodesWithTag("linux-install-progress")'))
        self.assertLess(method.index('ui.onAllNodesWithTag("linux-install-progress")'), method.index('assertEquals(expected, transactions.discarded.size)'))
        for value in ('assertFalse(dispatcher.pending)', 'node("linux-install-progress").assertDoesNotExist()', 'assertNoLegacyActions()'):
            self.assertIn(value, method)
        self.assertEqual(1, method.count('dispatcher.runNext()'))

    def test_model_editor_uses_fresh_state_queries_after_single_click(self):
        text = (TESTS / 'LocalCatalogModelsActivityTest.kt').read_text()
        self.assertIn('private fun awaitEditorSwitch', text)
        self.assertIn('ui.onAllNodes(expected).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1', text)
        self.assertIn('hasAnyAncestor(hasTestTag("catalog-model-editor"))', text)
        method = text.split('fun testModelBadgesUseExplicitNamesAndKeepOnlyTheCurrentModelCheck()',1)[1]
        for tag, before, after in (('支持视觉', 'true', 'false'), ('原生搜索', 'false', 'true')):
            start = 'awaitEditorSwitch("settings-switch:' + tag + '", ' + before + ')'
            click = 'editorSwitch("settings-switch:' + tag + '").performClick()'
            finish = 'awaitEditorSwitch("settings-switch:' + tag + '", ' + after + ')'
            self.assertLess(method.index(start), method.index(click))
            self.assertLess(method.index(click), method.index(finish))
            self.assertEqual(1, method.count(click))
        for value in ('it.contextWindowOverride == 8000', 'it.visionOverride == false', 'it.requestOptions.openAiWebSearchEnabled', 'assertEquals(0, fixture.opens.get())'):
            self.assertIn(value, method)

    def test_protocol_save_awaits_detail_reopen_before_alias_selection(self):
        text = (TESTS / 'ResponsesSearchSettingsActivityTest.kt').read_text()
        method = text.split('fun testChatModelSearchPersistsWithoutChangingEndpointOrSending()',1)[1].split('fun testSaveError',1)[0]
        self.assertIn('saveModel()\n        awaitModelEditor()\n        editCurrentModelSearch(true)', method)
        self.assertIn('assertEquals(before.endpoint, fixture.settings.load().endpoint)', method)
        self.assertIn('editCurrentModelSearch(false)', method)

    def test_sponsor_waits_for_entry_focus_and_native_dialog_removal(self):
        text = (TESTS / 'SettingsExternalLinksActivityTest.kt').read_text()
        self.assertIn('private fun awaitSponsorReady()', text)
        ready = text.split('private fun awaitSponsorReady()',1)[1].split('private fun awaitSponsorClosed()',1)[0]
        self.assertIn('awaitDialogEnter(node("sponsor-wechat"))', ready)
        self.assertIn('awaitNodeWindowFocus(node("sponsor-wechat"))', ready)
        closed = text.split('private fun awaitSponsorClosed()',1)[1].split('fun testSponsorSwitches',1)[0]
        self.assertIn('ui.onAllNodesWithTag("preview-dialog-viewport")', closed)
        self.assertIn('awaitNodeWindowFocus(node("page:APP 信息与更新"))', closed)
        method = text.split('fun testSponsorSwitchesOriginalQrsAndClosesWithoutOpeningPayment()',1)[1].split('fun testPausedOrRetiredSponsorCallback',1)[0]
        self.assertEqual(3, method.count('awaitSponsorReady()'))
        self.assertEqual(3, method.count('awaitSponsorClosed()'))
        self.assertEqual(1, method.count('sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)'))
        self.assertEqual(2, method.count('node("preview-dialog-scrim").performClick()'))
        for value in ('ui.onNodeWithContentDescription("关闭").assertDoesNotExist()',
                      'assertEquals(before, fixture.database.exportBackup())', 'assertDraft("sponsor unsent")'):
            self.assertIn(value, method)

    def test_sponsor_system_back_requires_stable_native_window_before_single_delivery(self):
        text = (TESTS / 'SettingsExternalLinksActivityTest.kt').read_text()
        method = text.split('fun testSponsorSwitchesOriginalQrsAndClosesWithoutOpeningPayment()', 1)[1].split('fun testPausedOrRetiredSponsorCallback', 1)[0]
        reopened = method.split('entry("赞助").performClick()', 1)[1].split('entry("赞助").performClick()', 1)[0]
        order = ['awaitSponsorReady()', 'node("sponsor-wechat").assertIsSelected()',
                 'dismissIme()',
                 'awaitDialogImeFrame(node("sponsor-wechat"), visible = false)',
                 'instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)', 'awaitSponsorClosed()']
        for before, after in zip(order, order[1:]):
            self.assertIn(before, reopened)
            self.assertIn(after, reopened)
            self.assertLess(reopened.index(before), reopened.index(after))
        self.assertEqual(1, method.count('sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)'))
        self.assertEqual(1, method.count('dismissIme()'))
        self.assertEqual(1, method.count('awaitDialogImeFrame(node("sponsor-wechat"), visible = false)'))
        self.assertNotIn('awaitImeFrame(node("sponsor-wechat"), visible = false)', method)
        for oracle in ('assertEquals(before, fixture.database.exportBackup())',
                       'assertEquals(settings, fixture.settings.loadForEditing().settings)',
                       'assertEquals(0, fixture.opens.get())', 'assertDraft("sponsor unsent")'):
            self.assertIn(oracle, method)
        self.assertNotIn('Thread.sleep', method)

    def test_assistant_back_waits_for_settled_sheet_and_native_ime_before_single_back(self):
        text = (TESTS / 'AssistantNavigationActivityTest.kt').read_text()
        method = text.split('private fun back(system: Boolean)', 1)[1].split('fun testHeaderAndSystemBack', 1)[0]
        order = ['awaitNodeWindowFocus(current)', 'awaitDialogEnter(current)', 'dismissIme()',
                 'awaitDialogImeFrame(current, visible = false)', 'if (system)',
                 'instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)']
        for before, after in zip(order, order[1:]):
            self.assertLess(method.index(before), method.index(after))
        self.assertEqual(1, method.count('sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)'))
        self.assertIn('ui.onNodeWithContentDescription("返回").performClick()', method)
        for oracle in ('assertEquals(profile, fixture.controller.assistantStore.read(profile.id))',
                       'assertEquals(selected, fixture.controller.assistantStore.selectedId.value)',
                       'assertEquals(before, fixture.database.exportBackup())', 'assertEquals(0, fixture.opens.get())',
                       'ui.onNodeWithText("Unsaved name").assertDoesNotExist()'):
            self.assertIn(oracle, text)

    def test_empty_projection_cannot_replace_the_first_pending_body(self):
        text = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageList.kt').read_text()
        body = text.split('val displayedTurn = remember(turn, projection) {', 1)[1].split('var hasProjectedBody', 1)[0]
        self.assertIn('if (projection == null || projection.sources.none { it.isNotBlank() }) turn', body)
        self.assertLess(body.index('projection.sources.none'), body.index('turn.copy(finalText = projection.sources.single())'))
        test = (TESTS / 'ui/screens/chat/conversation/MessageMarkdownTest.kt').read_text()
        method = test.split('fun testEmptyProjectionDoesNotCountAsPreviouslyDisplayedBody()', 1)[1].split('fun testColdPendingTurnReplacement', 1)[0]
        for oracle in ('MarkdownProjection(listOf(""),', 'entered.count == 0L',
                       'ui.onNodeWithTag("markdown-pending", useUnmergedTree = true).assertIsDisplayed()',
                       'ui.onNodeWithContentDescription("复制回答", useUnmergedTree = true).assertDoesNotExist()',
                       'release.countDown()', 'body("empty-to-body", "First body.").assertIsDisplayed()'):
            self.assertIn(oracle, method)

    def test_retained_callbacks_capture_inspector_metadata_not_detached_nodes(self):
        text = (TESTS / 'ComposeActivityTestCase.kt').read_text()
        helper = text.split('protected fun retainedClickCallback(', 1)[1].split('protected fun retainedClick(', 1)[0]
        for value in ('getModifierInfo()', 'InspectableValue', 'it.inspectableElements',
                      '.single { it.name == "onClick" }.value'):
            self.assertIn(value, helper)
        for forbidden in ('SemanticsActions.OnClick', 'getDeclaredField', 'isAccessible', 'runCatching'):
            self.assertNotIn(forbidden, helper)
        assistant = (TESTS / 'AssistantNavigationActivityTest.kt').read_text()
        self.assertIn('val staleCreate = retainedClickCallback(newButton)', assistant)
        self.assertIn('mainThread { staleCreate() }', assistant)
        bulk = (TESTS / 'ModelBulkSelectionActivityTest.kt').read_text()
        self.assertIn('val oldSettings = retainedClickCallback(reveal("local-model-rename:bulk-first"))', bulk)
        self.assertIn('mainThread { oldSettings() }', bulk)
        linux = (TESTS / 'ui/screens/settings/LinuxSettingsScreenTest.kt').read_text()
        for name in ('oldSwitch', 'oldBeforeFiles'):
            self.assertIn('val ' + name + ' = retainedClickCallback(', linux)
            self.assertIn('mainThread { ' + name + '() }', linux)

if __name__ == '__main__':
    unittest.main()
