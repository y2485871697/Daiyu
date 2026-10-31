"""Source regression gates for normal pause/resume, not process-death or Android execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


def source(name):
    return (SRC / name).read_text()


class BackgroundPageRetentionContractTest(unittest.TestCase):
    def test_pause_retains_provider_route_and_drafts_but_revokes_authority(self):
        route = source('ProviderManagementRoute.kt')
        pause = route.split('if (event == Lifecycle.Event.ON_PAUSE) {', 1)[1].split('} else if (event == Lifecycle.Event.ON_RESUME)', 1)[0]
        for kept in ('serial++', 'resumed = false; permit.revoke()', 'detail?.revoke()', 'confirmation = null'):
            self.assertIn(kept, pause)
        self.assertTrue('creating?.revoke()' in pause or 'creating?.balanceTestJob?.cancel()' in pause)
        for dropped in ('authentication = false', 'creating = null', 'detail = null', 'closeDetail()', 'clearPrompts()', 'clearTransientPrompts()', 'selectedSlots = null'):
            self.assertNotIn(dropped, pause)
        self.assertIn('permit = ProviderPagePermit(); resumed = true', route)

    def test_revalidation_preserves_composition_without_reviving_save_owners(self):
        route = source('ProviderManagementRoute.kt')
        for guard in ('restore.editor == loaded.editor', 'restore.editing == loaded.editing',
                      'restore.configurationRevision == loaded.configurationRevision',
                      'restore.balanceConfiguration == loaded.balanceConfiguration',
                      'retained.also { it.resume() }', 'saveOwner = ModelSettingsSaveOwner()',
                      'if (retained == null) closeDetail()', 'else if (openingSlot != null) open(checkNotNull(openingSlot))'):
            self.assertIn(guard, route)
        self.assertIn('key(d)', route)
        self.assertIn('permit === p && resumed', route)

    def test_new_provider_callbacks_recheck_their_original_foreground_permit(self):
        route = source('ProviderManagementRoute.kt')
        self.assertIn('if (current(p) && enabled && creating === newProvider && newProvider.draft === draft)', route)
        self.assertIn('if (current(p) && enabled && creating === newProvider && newProvider.draft === submitted', route)
        for entry in ('onAddOpenAi', 'onAddAnthropic', 'onAddSpeech'):
            self.assertIn(entry + ' = { if (current(p) && enabled)', route)
        self.assertIn('if (current(p) && enabled) creating = NewProviderDraft(ProviderProtocol.RESPONSES)', route)

    def test_new_provider_tabs_and_positions_share_visit_memory(self):
        route = source('ProviderManagementRoute.kt')
        self.assertIn('val pageUi by lazy { ProviderPageUiState() }', route.split('internal class NewProviderDraft', 1)[1])
        self.assertIn('var modelTab by newProvider.pageUi.modelTab', route)
        for retained in ('configurationListState', 'modelListState', 'modelQuery'):
            self.assertIn('newProvider.pageUi.' + retained, route)
        self.assertNotIn('rememberSaveable', route)
        self.assertNotIn('SavedStateHandle', route)

    def test_local_model_pause_retains_input_and_rotates_callback_identities(self):
        local = source('LocalCatalogModelsScreen.kt')
        pause = local.split('fun pause() {', 1)[1].split('fun reload()', 1)[0]
        self.assertIn('request?.set(true)', pause)
        self.assertIn('deleting = null', pause)
        self.assertIn('editing = editing?.copy()', pause)
        self.assertIn('adding = adding?.let { Any() }', pause)
        for dropped in ('editing = null', 'adding = null', 'snapshot = null', 'loaded = false'):
            self.assertNotIn(dropped, pause)
        self.assertIn('referentialEqualityPolicy()', local)
        self.assertIn('ON_PAUSE) { resumed = false; pause() }', local)

    def test_local_model_reload_checks_revision_without_unmounting_the_editor(self):
        local = source('LocalCatalogModelsScreen.kt')
        reload = local.split('fun reload() {', 1)[1].split('fun matches(', 1)[0]
        self.assertNotIn('editing = null', reload)
        self.assertNotIn('adding = null', reload)
        self.assertIn('selection.expectedRevision', reload)
        self.assertIn('selection.record', reload)
        self.assertIn('addBase.revision', reload)
        self.assertIn('editorStale = true', reload)
        self.assertIn('canSave = !editorStale', local)
        self.assertIn('!currentEnabled || editorStale || request != null', local)
        self.assertIn('confirmEnabled = enabled && canSave', source('CatalogModelEditorDialog.kt'))

    def test_explicit_exit_still_discards_the_edit_visit(self):
        local = source('LocalCatalogModelsScreen.kt')
        dispose = local.split('onDispose {', 1)[1].split('LaunchedEffect(visit)', 1)[0]
        self.assertIn('visit.set(false); selectionLease.revoke(); request?.set(true)', dispose)
        self.assertNotIn('selectedIds = null', dispose)
        self.assertIn('else { visit.set(false); cancel(); onBack() }', local)
        self.assertIn('if (selectedIds != null) { selectedIds = null; deletingSelected = null }', local)
        self.assertIn('else { visit.set(false); cancel(); onBack() }', local)
        route = source('ProviderManagementRoute.kt')
        self.assertIn('if (creating != null) { serial++; clearPrompts(); refresh(); return }', route)
        self.assertIn('onDispose {', route)

    def test_activity_regressions_cover_retention_and_old_callbacks(self):
        auth = (ANDROID / 'ProviderAuthenticationChoiceActivityTest.kt').read_text()
        local = (ANDROID / 'LocalCatalogModelsActivityTest.kt').read_text()
        for name in ('testPausedAddRetainsDraftWithoutRegisteringAndOldSaveStaysRevoked',
                     'testAuthenticationAndNewModelTabRetainTheirPageAcrossPause',
                     'testPausingUnsavedDoubaoProviderRetainsDraftWithoutRegistering',
                     'testAnthropicCreationRemainsOnItsDirectEntryAfterPause'):
            self.assertIn('fun ' + name, auth)
        for name in ('testUnsavedModelAddSurvivesRepeatedPauseWithoutWritingCatalog',
                     'testUnsavedModelEditSurvivesPauseAndRejectsOldSaveCallback'):
            self.assertIn('fun ' + name, local)
        for tests in (auth, local):
            self.assertIn('realPauseResume(activity', tests)
            self.assertIn('assertEquals(before, fixture.', tests)
            self.assertIn('oldSave()', tests)

class HostBackgroundRetentionContractTest(unittest.TestCase):
    def test_lifecycle_only_revokes_browser_and_context_capabilities(self):
        main = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        pause = main.split('override fun onPause()', 1)[1].split('override fun onStop()', 1)[0]
        stop = main.split('override fun onStop()', 1)[1].split('override fun onDestroy()', 1)[0]
        self.assertIn('closeBrowserVisualVisit(clearTarget = false)', pause)
        self.assertIn('closeBrowserVisualVisit(clearTarget = false)', stop)
        for body in (pause, stop):
            self.assertIn('revokeContextManagementEntry(closeRoute = false, preservePresentation = true)', body)
            self.assertNotIn('retainedPage = "chat"', body)
        resume = main.split('override fun onResume()', 1)[1].split('private fun applyPreferredRefreshRate()', 1)[0]
        self.assertIn('browserVisualEpoch++', resume)
        for action in ('openBrowserVisual(', 'openHumanBrowserVisit(', 'resumeBrowserVisualPage(',
                       'openContextManagement(', 'confirmCompression('):
            self.assertNotIn(action, resume)

    def test_task_browser_requires_a_fresh_click_and_exact_original_target(self):
        main = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        body = main.split('private fun resumeBrowserVisualPage(', 1)[1].split('private fun resumeContextManagementPage(', 1)[0]
        for guard in ('browserResumeTarget !== target', 'epoch != browserVisualEpoch',
                      'selected?.id != target.conversationId', '!generationResumed()',
                      'controller.openBrowserVisual(checkNotNull(target.conversationId), target.owner)'):
            self.assertIn(guard, body)
        self.assertIn('if (next == null) toast(', body)
        self.assertNotIn('next ?: openHumanBrowserVisit()', body)
        self.assertNotIn('.restore()', body)
        self.assertIn('Modifier.testTag("browser-resume")', main)
        self.assertIn('if (original != null) key(original)', main)
        tests = (ANDROID / 'BrowserPageLifecycleActivityTest.kt').read_text()
        self.assertIn('fun testTaskTargetDoesNotAutoReconnectOrFallBackToHuman()', tests)
        self.assertIn('oldContinue(); assertNull(visit())', tests)

    def test_context_continue_rechecks_epoch_editor_and_conversation(self):
        main = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        body = main.split('private fun resumeContextManagementPage(', 1)[1].split('private var retainedPageState', 1)[0]
        for guard in ('selected?.id != page.conversation', 'editorGeneration != page.generation',
                      'contextManagementEntryEpoch != epoch', 'contextManagementVisit != null',
                      '!generationResumed()', 'retainedPage != "context-management"'):
            self.assertIn(guard, body)
        self.assertIn('controller.openContextManagement(owner)', body)
        self.assertNotIn('confirmCompression(', body)

    def test_expired_provider_error_is_visible_above_all_tabs_without_rearming(self):
        route = source('ProviderManagementRoute.kt')
        body = route.split('if (d != null && !d.live.get()) d.error?.let { message ->', 1)[1].split('val confirm = confirmation', 1)[0]
        self.assertIn('DaiyuConfirmDialog("提供商设置", message', body)
        self.assertIn('if (detail === d && d.error == message) d.error = null', body)
        self.assertNotIn('.resume()', body)
        self.assertNotIn('enabled', body)


if __name__ == '__main__':
    unittest.main()
