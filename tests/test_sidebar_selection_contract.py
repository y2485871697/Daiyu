"""Source wiring only: Android regression verifies real drawer selection and explicit dismissal."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class SidebarSelectionContractTest(unittest.TestCase):
    def test_sidebar_selection_switches_owner_without_close_or_navigation(self):
        host = (SRC / 'MainActivity.kt').read_text()
        callback = host.split('onConversationSelected = ', 1)[1].split('\n', 1)[0]
        self.assertEqual('{ id -> selectPaneConversation(id) },', callback)
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

    def test_selection_feedback_precedes_async_history_and_keeps_pending_highlight(self):
        host = (SRC / 'MainActivity.kt').read_text()
        click = host.split('private fun selectPaneConversation(', 1)[1].split('private fun retainedConversation(', 1)[0]
        self.assertLess(click.index('paneState = paneState.copy(selectedConversationId = id)'),
                        click.index('withContext(Dispatchers.IO) { read(id) }'))
        self.assertIn('paneSelectionRequest !== request', click)
        self.assertIn('selected?.id != owner || editorGeneration != editor', click)
        self.assertIn('catch (cancelled: kotlinx.coroutines.CancellationException)', click)
        self.assertIn('select(snapshot.conversation, snapshot)', click)
        self.assertIn('if (revision != currentPaneRevision()) continue', click)
        pause = host.split('override fun onPause() {', 1)[1].split('override fun onStop()', 1)[0]
        self.assertIn('cancelPaneConversationSelection()', pause)
        opening = host.split('private fun openSidebar()', 1)[1].split('private fun dismissSidebar()', 1)[0]
        self.assertIn('cancelPaneConversationSelection()', opening)
        self.assertNotIn('retainedConversation(id)', click)
        self.assertIn('selectedConversationId = pendingPaneConversationId ?: selected?.id?.value', host)
        selected = host.split('private fun select(conversation: Conversation?, prepared:', 1)[1].split('private fun sendDraft(', 1)[0]
        self.assertIn('prepared?.conversations ?: controller.database.conversations()', selected)
        self.assertIn('prepared?.messages ?: controller.database.messages(conversation.id)', selected)
        self.assertLess(selected.index('paneState = paneState.copy(selectedConversationId = conversation?.id?.value)'),
                        selected.index('publishChat(); jumpToBottom(); refresh()'))
        pane = (SRC / 'ui/components/ConversationSidePaneScaffold.kt').read_text()
        self.assertIn('val rowSelected = if (managing) checked else selected', pane)
        self.assertIn('this.selected = rowSelected', pane)

    def test_async_selection_regressions_cover_blocking_latest_failure_and_destroy(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ConversationPaneSwitchActivityTest.kt').read_text()
        for name in ['testSelectionFeedbackPrecedesSlowHistoryAndPaneRefresh',
                     'testLastClickWinsWhenCancelledReadFailsLate',
                     'testFailedHistoryReadRestoresOriginalSelectionAndDraft',
                     'testDestroyedActivityRejectsLateConversationRead']:
            self.assertIn(name, test)
        for fragment in ['assertIsSelected()', 'assertIsNotSelected()',
                         'Looper.myLooper() == Looper.getMainLooper()',
                         'realReader(id)', 'paneRelease.countDown()', 'ui.mainClock.advanceTimeByFrame()',
                         'oldJob.isCompleted', 'closingJob.isCompleted',
                         'assertEquals(0, fixture.opens.get())']:
            self.assertIn(fragment, test)

    def test_search_and_new_conversation_keep_explicit_chat_navigation(self):
        host = (SRC / 'MainActivity.kt').read_text()
        helper = host.split('private fun openRetainedConversation(id: String) {', 1)[1].split('\n', 1)[0]
        self.assertIn('val conversation = retainedConversation(id); dismissSidebar(); returnToChat(); select(conversation)', helper)
        self.assertIn('onOpenConversation = { id -> guard { openRetainedConversation(id) } }', host)
        self.assertIn('onNewConversation = { guard { createNewConversation(); dismissSidebar() } }', host)
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


    def test_inactive_drawer_search_never_clears_another_editors_focus(self):
        pane = (SRC / 'ui/components/ConversationSidePaneScaffold.kt').read_text()
        search = pane.split('private fun PaneSearchRow(', 1)[1].split('private fun PaneDrawerActions(', 1)[0]
        self.assertNotIn('clearFocus(', search)
        self.assertNotIn('LocalFocusManager', search)
        self.assertIn('if (!searchFocusEnabled) {\n            searchActive = false', search)
        self.assertIn('enabled = searchFocusEnabled && searchActive', search)
        self.assertIn('canFocus = searchFocusEnabled && searchActive', search)
        self.assertIn('if (shouldHide) hideChatInputIme(focusManager, keyboard, view)', pane)

    def test_single_sidebar_back_waits_for_hidden_native_ime_at_dispatch_boundary(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ConversationPaneManagementActivityTest.kt').read_text()
        body = test.split('fun testSelectingConversationKeepsSidebarAndSearchUntilExplicitBackOrScrimDismissal()', 1)[1].split('\n    fun ', 1)[0]
        before, after = body.split('instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)')
        self.assertLess(before.rindex('dismissIme()'), before.index('awaitImeFrame(node("drawer-search-input"), visible = false)'))
        self.assertLess(before.index('awaitNodeWindowFocus(node("conversation-drawer"))'), before.rindex('dismissIme()'))
        self.assertIn('awaitControl("explicit hardware Back dismisses sidebar")', after)
        self.assertIn('.assertIsNotEnabled().assert(isFocused().not())', after)
        self.assertIn('assertSelection(1, 3)', after)
        self.assertNotIn('Thread.sleep', body)

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

    def test_recreation_collapse_diagnostic_does_not_change_selection_acceptance(self):
        host = (SRC / 'MainActivity.kt').read_text()
        diagnostic = host.split('if (BuildConfig.DEBUG && selectionStart != selectionEnd && start == end)', 1)[1].split('selectionStart = start; selectionEnd = end', 1)[0]
        self.assertIn('Throwable("selection-collapse-origin")', diagnostic)
        self.assertIn('windowFocused=', diagnostic)
        self.assertNotIn('return', diagnostic)
        self.assertNotIn('draft', diagnostic)

    def test_subagent_reopened_name_field_exists_before_window_focus(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/SubAgentSettingsButtonTest.kt').read_text()
        body = test.split('fun testAddGroupButtonStillOpensFormAndSavesOnlyOnConfirmation()', 1)[1]
        for opening in body.split('addGroupButton().performClick()')[1:]:
            self.assertLess(opening.index('fetchSemanticsNodes().size == 1'), opening.index('awaitNodeWindowFocus(name)'))
            self.assertIn('ui.waitUntil(5_000)', opening)
        self.assertIn('assertEquals(listOf("新子代理组"), repository.presets().map { it.name })', body)
        self.assertIn('assertEquals(0, fixture.opens.get())', body)

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
