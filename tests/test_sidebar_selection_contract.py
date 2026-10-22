"""Source wiring only: Android regression verifies real drawer selection and explicit dismissal."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class SidebarSelectionContractTest(unittest.TestCase):
    def test_sidebar_selection_switches_owner_without_close_or_navigation(self):
        host = (SRC / 'MainActivity.kt').read_text()
        callback = host.split('onConversationSelected = ', 1)[1].split('\n', 1)[0]
        self.assertEqual('{ id -> guard { select(retainedConversation(id)) } },', callback)
        self.assertNotIn('dismissSidebar', callback)
        self.assertNotIn('returnToChat', callback)
        select = host.split('internal fun select(conversation: Conversation?) {', 1)[1].split('private fun sendDraft(', 1)[0]
        self.assertNotIn('sidebarVisible = false', select)
        self.assertIn('check(controller.ready && !controller.dataBusy', select)
        self.assertIn('check(!selectionReady || (pendingTextSend == null && controller.textSendState() == null))', select)
        self.assertIn('controller.database.draft(id, draft)', select)
        self.assertIn('selections[id] = selectionStart to selectionEnd', select)
        self.assertIn('selected = conversation; selectionReady = true; draft = targetDraft', select)
        self.assertIn('publishChat(); jumpToBottom(); refresh()', select)

    def test_search_and_new_conversation_keep_explicit_chat_navigation(self):
        host = (SRC / 'MainActivity.kt').read_text()
        helper = host.split('private fun openRetainedConversation(id: String) {', 1)[1].split('\n', 1)[0]
        self.assertIn('val conversation = retainedConversation(id); dismissSidebar(); returnToChat(); select(conversation)', helper)
        self.assertIn('onOpenConversation = { id -> guard { openRetainedConversation(id) } }', host)
        self.assertIn('onNewConversation = { guard { dismissSidebar(); select(null) } }', host)
        hit = host.split('private fun openHistoryHit(', 1)[1].split('private fun toast(', 1)[0]
        self.assertIn('openRetainedConversation(hit.conversationId)', hit)

    def test_drawer_back_scrim_gestures_and_management_are_unchanged(self):
        pane = (SRC / 'ui/components/ConversationSidePaneScaffold.kt').read_text()
        self.assertIn('onBackCompleted = { currentOnDismiss() }', pane)
        self.assertIn('gesturesEnabled = backHandlerEnabled', pane)
        self.assertIn('else if (currentVisible) {\n                    currentOnDismiss()', pane)
        self.assertIn('if (managing) selectedIds =', pane)
        self.assertIn('else onConversationSelected(conversation.id)', pane)
        host = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('onOpen = { openSidebar() }, onDismiss = { dismissSidebar() }', host)
        self.assertIn('val allowed = !sidebarVisible && controller.ready && !controller.dataBusy', host)

    def test_activity_regression_covers_reselection_drafts_filter_and_explicit_close(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ConversationPaneManagementActivityTest.kt').read_text()
        for fragment in ['testSelectingConversationKeepsSidebarAndSearchUntilExplicitBackOrScrimDismissal',
                         'listOf(second, second, first)', 'paneState().selectedConversationId == conversation.id.value',
                         'assertEquals(true, hostValue("getSidebarVisible"))',
                         'fixture.controller.preferences.getString("selected", null)',
                         'node("drawer-search-input").assertEditableText("保留侧栏")',
                         'assertEquals(before, fixture.database.exportBackup())',
                         'KeyEvent.KEYCODE_BACK', 'drawerRoot.performTouchInput',
                         'isRoot() and hasAnyDescendant(hasTestTag("conversation-drawer"))',
                         'awaitNodeWindowFocus(drawer)',
                         'assertEquals(false, hostValue("getSidebarVisible"))',
                         'assertDraft("乙草稿")', 'assertSelection(1, 3)',
                         'node("chat-draft").assertIsNotEnabled()',
                         'testManageSelectsInPlacePreservesOwnerDraftAndReopensWithoutLosingSelection',
                         'assertEquals(0, fixture.opens.get())']:
            self.assertIn(fragment, test)


    def test_recreation_diagnostic_preserves_both_selection_endpoints_and_hide_boundary(self):
        fixture = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ComposeActivityTestCase.kt').read_text()
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ActivityTest.kt').read_text()
        helper = fixture.split('protected fun recreateActivity(', 1)[1].split('protected fun finishExistingMainActivities', 1)[0]
        self.assertLess(helper.index('dismissIme()'), helper.index('afterImeHidden()'))
        self.assertLess(helper.index('afterImeHidden()'), helper.index('ActivityCompat.recreate(activity)'))
        self.assertIn('selection expected=($start,$end) actual=(${range.start},${range.end})', fixture)
        for fragment in ['selectionCheckpoint("before-hide")', 'selectionCheckpoint("after-hide-before-recreate")',
                         'selectionCheckpoint("replacement-focused")', 'after-destroy retained=',
                         'assertDraft(" B新稿\\n "); assertSelection(1, 2)']:
            self.assertIn(fragment, test)

    def test_drawer_reveal_uses_lazy_key_and_reselection_requires_fresh_generation(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ConversationPaneManagementActivityTest.kt').read_text()
        helper = test.split('private fun revealConversation(', 1)[1].split('private fun assertSelectedWithSidebarOpen', 1)[0]
        self.assertLess(helper.index('performScrollToKey(conversation.id.value)'), helper.index('awaitUniqueTag(tag)'))
        self.assertLess(helper.index('awaitUniqueTag(tag)'), helper.index('assertIsDisplayed().performClick()'))
        self.assertIn('getDeclaredField("editorGeneration")', test)
        self.assertIn('editorGeneration() > generation', test)
        self.assertIn('paneState().searchResults().map { it.id }.toSet() == expectedResults', test)

    def test_slot_restore_isolates_toolbar_without_changing_semantics_default(self):
        fixture = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ComposeActivityTestCase.kt').read_text()
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ActivityTest.kt').read_text()
        self.assertIn('relativeToOriginalText: Boolean = false', fixture)
        self.assertIn('it(start, end, relativeToOriginalText)', fixture)
        body = test.split('fun testDraftSlotsRunningSwitchAndActivityRecreation()', 1)[1].split('\n    fun ', 1)[0]
        self.assertEqual(4, body.count('relativeToOriginalText = true'))
        self.assertIn('assertSelection(1, 2)', body)
        self.assertIn('testSelectionSemanticsKeepTraversalAndToolbarRangesAndCollapsedCursor', test)
        self.assertIn('setSelection(2, 4, relativeToOriginalText = false)', test)
        self.assertIn('setSelection(4, 4, relativeToOriginalText = false)', test)


if __name__ == '__main__':
    unittest.main()
