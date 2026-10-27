"""Source guardrails for revoked compression visits; Activity regressions run in CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class ContextRouteLifecycleContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = (SRC / 'MainActivity.kt').read_text()
        cls.activity_tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ContextManagementEntryActivityTest.kt').read_text()

    def test_compression_sheet_keeps_chat_composition_and_skips_page_motion(self):
        host = self.main.split('app.daiyu.android.ui.navigation.PageBackGestureHost(', 1)[1].split('previewScopeKey = selected?.id,', 1)[0]
        self.assertIn('"context-management" -> "chat"', host)
        self.assertIn('"assistant-edit" -> assistantEditReturnPage', host)
        animation = host.split('animateBackEnabled = ', 1)[1].split('canNavigateBack =', 1)[0]
        for route in ('assistant-edit', 'history-search', 'context-management'):
            self.assertIn('retainedPage != "' + route + '"', animation)
        self.assertIn('routeKey = if (retainedPage == "chat" && sidebarVisible) "chat-sidebar" else retainedPage', host)
        self.assertIn('canNavigateBack = retainedPage != "chat"', host)

    def test_real_menu_regression_samples_open_and_close_before_navigation_can_settle(self):
        body = self.activity_tests.split('fun testCompressionSheetNeverSlidesOrRemountsChatOnRepeatedOpenAndClose()', 1)[1].split('\n    fun ', 1)[0]
        for token in ('repeat(2)', 'ui.mainClock.autoAdvance = false',
                      'node("chat-menu:COMPRESS_CONTEXT").performTouchInput { click() }',
                      'repeat(40)', '"page-enter-previous"', '"page-enter-scrim"',
                      '"page-enter-blocker"', '"page-back-departure"',
                      'boundsInRoot', 'originalDraftId', '"context-close"',
                      'activity.onBackPressedDispatcher.onBackPressed()',
                      'assertEquals(before, fixture.database.exportBackup())',
                      'assertEquals(0, fixture.opens.get())'):
            self.assertIn(token, body)

    def test_revocation_clears_owner_before_callbacks_and_closes_only_its_route(self):
        body = self.main.split('private fun revokeContextManagementEntry(', 1)[1].split('private var legacyPage', 1)[0]
        self.assertIn('closeRoute: Boolean = true', body)
        empty = body.index('val visit = contextManagementVisit')
        clear = body.index('contextManagementVisit = null')
        invalidate = body.index('if (visit != null) controller.invalidateContextManagement(visit.adapter)')
        close = body.index('if (closeRoute && retainedPage == "context-management") retainedPage = "chat"')
        self.assertLess(empty, clear)
        self.assertLess(clear, invalidate)
        self.assertLess(invalidate, close)
        self.assertNotIn('returnToChat()', body)
        self.assertNotIn('openContextManagement(', body)

    def test_navigation_keeps_its_requested_destination_without_reentrant_close(self):
        setter = self.main.split('private var retainedPage: String', 1)[1].split('private var paneState', 1)[0]
        self.assertIn('if (value != retainedPageState) revokeContextManagementEntry(closeRoute = false)', setter)
        self.assertNotIn('editorGeneration++', setter)
        self.assertIn('retainedPageState = value', setter)
        self.assertEqual(1, setter.count('revokeContextManagementEntry(closeRoute = false)'))

    def test_context_visit_epoch_does_not_remount_chat_editor(self):
        self.assertIn('ContextManagementOwner(conversation.id, renderedContextEntryEpoch)', self.main)
        self.assertIn('ContextManagementOwner(conversation, epoch)', self.main)
        revoke = self.main.split('private fun revokeContextManagementEntry(', 1)[1].split('private var legacyPage', 1)[0]
        self.assertIn('contextManagementEntryEpoch++', revoke)
        self.assertNotIn('editorGeneration++', revoke)

    def test_pause_stop_and_editor_changes_still_revoke_not_restore(self):
        for method, end in [('onPause()', 'override fun onStop()'), ('onStop()', 'override fun onDestroy()')]:
            body = self.main.split('override fun ' + method, 1)[1].split(end, 1)[0]
            self.assertIn('revokeContextManagementEntry(closeRoute = false)', body)
        resume = self.main.split('override fun onResume()', 1)[1].split('private fun applyPreferredRefreshRate()', 1)[0]
        self.assertIn('contextManagementEntryEpoch++', resume)
        self.assertNotIn('openContextManagement(', resume)
        self.assertNotIn('confirmCompression(', resume)
        editor = self.main.split('@Volatile private var editorGeneration', 1)[1].split('private var draftRevision', 1)[0]
        self.assertIn('revokeContextManagementEntry()', editor)

    def test_pause_and_stop_keep_route_without_restoring_old_authority(self):
        tests = self.activity_tests
        self.assertIn('private fun assertChatRoute()', tests)
        self.assertIn('assertEquals("chat", getter.apply { isAccessible = true }.invoke(activity))', tests)
        self.assertIn('fun testPausedContextContinueCallbackCannotSurviveAnotherPauseOrSelection()', tests)
        for name in ('testPauseWithoutStopRevokesOldCompressionConfirmationAndRetainsRoute',
                     'testStopRevokesUnopenedEntryAndOpenAdapterConsentAndResumeNeedsFreshClick'):
            body = tests.split('fun ' + name + '()', 1)[1].split('\n    fun ', 1)[0]
            self.assertIn('assertPausedContextRoute()', body)
            self.assertIn('val next = continueAndAwait()', body)
            self.assertNotIn('backToChat(activity)', body)
            self.assertIn('assertEquals(before, fixture.database.exportBackup())', body)
            self.assertIn('assertEquals(0, fixture.opens.get())', body)

    def test_same_id_sidebar_and_navigation_regressions_cover_other_revocations(self):
        tests = self.activity_tests
        self.assertIn('fun testSameConversationReentryClosesRevokedRouteWithoutChangingHistoryOrDraft()', tests)
        sidebar = tests.split('fun testSidebarRevokesEntryWithoutVisitAndClosingDoesNotReviveOldClicks()', 1)[1].split('\n    fun ', 1)[0]
        self.assertIn('assertChatRoute()', sidebar)
        page = tests.split('fun testSelectionAndPageChangesRevokeBeforeOldCommandsAndSameIdGetsNewVisit()', 1)[1].split('\n    fun ', 1)[0]
        self.assertIn('awaitUniqueTag("page:设置")', page)


if __name__ == '__main__':
    unittest.main()
