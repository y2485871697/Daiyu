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
        for value in ('node("preview-dialog-scrim").performClick()', 'ui.onNodeWithContentDescription("关闭").performClick()',
                      'assertEquals(before, fixture.database.exportBackup())', 'assertDraft("sponsor unsent")'):
            self.assertIn(value, method)

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

if __name__ == '__main__':
    unittest.main()
