"""Source contracts only; native-window input requires Android regression."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/IntegrationSettingsActivityTest.kt'


class ChatPickerResumeFocusContractTest(unittest.TestCase):
    def body(self):
        text = SOURCE.read_text(encoding='utf-8')
        return text.split('fun testPausedChatModelPickerRevokesOldSelectionAndCannotBorrowResumedOwner()', 1)[1].split('fun testDismissedChatModelPickerCannotReviveFromPendingCatalog()', 1)[0]

    def test_first_open_waits_for_the_clicked_nodes_native_window_and_enabled_state(self):
        body = self.body()
        focus = 'awaitNodeWindowFocus(modelButton)'
        click = 'modelButton.assertIsDisplayed().assertIsEnabled().performClick()'
        self.assertEqual(1, body.count(focus))
        self.assertEqual(1, body.count(click))
        self.assertNotIn('node("chat-model").performClick()', body)
        self.assertLess(body.index('val modelButton = node("chat-model")'), body.index(focus))
        self.assertLess(body.index(focus), body.index('ui.waitUntil(5_000)'))
        self.assertIn('it.getOrNull(SemanticsProperties.Disabled) == null', body)
        self.assertLess(body.index('SemanticsProperties.Disabled'), body.index(click))
        self.assertLess(body.index(click), body.index('realPauseResume(activity, atPause = {'))
        for forbidden in ['Thread.sleep', 'SystemClock.sleep', 'advanceTimeBy', 'performSemanticsAction', 'repeat(']:
            self.assertNotIn(forbidden, body)

    def test_close_waits_for_picker_entry_and_native_focus_before_single_system_back(self):
        text = SOURCE.read_text(encoding='utf-8')
        body = text.split('private fun closeChatPicker()', 1)[1].split('private fun reopenChatPicker()', 1)[0]
        ordered = ['val picker = node("chat-model-picker")', 'awaitDialogEnter(picker)',
                   'awaitNodeWindowFocus(picker)', 'dismissIme()',
                   'instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)',
                   'ui.waitUntil(5_000)', 'fetchSemanticsNodes().isEmpty()']
        positions = [body.index(step) for step in ordered]
        self.assertEqual(sorted(positions), positions)
        self.assertEqual(1, body.count('sendKeyDownUpSync'))
        for forbidden in ['awaitChatPickerLoaded', 'awaitChatPickerReady', 'Thread.sleep',
                          'SystemClock.sleep', 'repeat(', 'performSemanticsAction']:
            self.assertNotIn(forbidden, body)

    def test_pending_catalog_is_released_only_after_real_dismiss_and_retains_safety_oracles(self):
        text = SOURCE.read_text(encoding='utf-8')
        body = text.split('fun testDismissedChatModelPickerCannotReviveFromPendingCatalog()', 1)[1].split('fun testChatModelPickerRejectsChangedSettingsAndRunningTask()', 1)[0]
        self.assertLess(body.index('closeChatPicker()'), body.index('release.countDown()'))
        self.assertIn('"正在读取模型目录"', body)
        self.assertIn('completed.await(5, TimeUnit.SECONDS)', body)
        self.assertIn('node("chat-model-picker").assertDoesNotExist()', body)
        self.assertIn('assertEquals(database, fixture.database.exportBackup())', body)
        self.assertIn('assertDraft("本地目录读取取消后保留草稿")', body)
        self.assertIn('assertChatPickerOffline(calls)', body)
        self.assertIn('assertEquals(0, fixture.opens.get())', body)

    def test_catalog_retry_waits_for_the_list_window_then_scrolls_the_lazy_option_into_view(self):
        text = SOURCE.read_text(encoding='utf-8')
        body = text.split('fun testCatalogChangedAndRestoredDraftRevokesLateResponseAndCanRetry()', 1)[1].split('fun testCatalogSaveFailureAndDismissRevokePendingResponse()', 1)[0]
        steps = ['finished(retryConnection)', 'awaitUniqueTag("provider-model-list")',
                 'awaitNodeWindowFocus(models)', 'awaitDialogEnter(models)',
                 'models.scrollToDescendant(hasTestTag("provider-model-option:second"))',
                 'node("provider-model-option:second").assertIsDisplayed().assertIsEnabled()']
        positions = [body.index(step) for step in steps]
        self.assertEqual(sorted(positions), positions)
        self.assertIn('node("provider-model-selection").assertDoesNotExist()', body)
        self.assertIn('assertEquals(old, fixture.settings.load().apiKey)', body)
        self.assertIn('assertEquals(before, fixture.settings.load())', body)

    def test_pause_revocation_and_stale_capability_oracles_remain(self):
        body = self.body()
        for fragment in [
            'stale = retainedChatPickerSelection()', 'realPauseResume(activity, atPause = {',
            'previousSession = sessionField.get(activity)', 'it !== previousSession',
            'mainThread { stale() }', 'assertFalse(fixture.controller.dataBusy)',
            'assertEquals(bindings, modelBindings())', 'assertChatBinding("second")',
            'assertEquals(bindings + ("" to expectedChatBinding("second")), modelBindings())',
            'assertEquals(database, fixture.database.exportBackup())',
            'node("chat-model-option:second").assertIsEnabled().performClick()',
            'node("chat-model-picker").assertExists()', 'fetchSemanticsNodes().isEmpty()',
            'assertChatPickerOffline(calls)', 'assertEquals(0, fixture.opens.get())',
        ]:
            self.assertIn(fragment, body)
        pause = body.split('realPauseResume(activity, atPause = {', 1)[1].split('})', 1)[0]
        self.assertIn('stale()', pause)
        self.assertIn('assertEquals(bindings, modelBindings())', pause)
        self.assertIn('assertEquals(before, fixture.settings.load())', pause)
        self.assertEqual(3, body.count('assertEquals(before, fixture.settings.load())'))

    def test_pending_catalog_waits_for_all_new_jobs_not_an_assumed_single_job(self):
        text = SOURCE.read_text(encoding='utf-8')
        body = text.split('fun testDismissedChatModelPickerCannotReviveFromPendingCatalog()', 1)[1].split('fun testChatModelPickerRejectsChangedSettingsAndRunningTask()', 1)[0]
        for fragment in ['parent.children.filter { it !in previousJobs }.toList()',
                         'pending.isNotEmpty()', 'AtomicInteger(pending.size)', 'pending.forEach { job ->',
                         'job.invokeOnCompletion', 'remaining.decrementAndGet() == 0',
                         'completed.await(5, TimeUnit.SECONDS)', 'assertEquals(bindings, modelBindings())']:
            self.assertIn(fragment, body)
        self.assertNotIn('parent.children.single', body)

    def test_running_picker_clicks_different_model_but_preserves_configuration_owner_and_data(self):
        text = SOURCE.read_text(encoding='utf-8')
        body = text.split('fun testChatModelPickerRejectsChangedSettingsAndRunningTask()', 1)[1].split('fun testCatalogImportsCheckedModels', 1)[0]
        self.assertIn('node("chat-model-option:second").assertIsNotEnabled()', body)
        running = body.split('val session = fixture.session(holdSeconds = 120)', 1)[1]
        self.assertEqual(2, running.count('node("chat-model-option:second").assertIsEnabled().performClick()'))
        for fragment in ['assertEquals(changed, fixture.settings.load())',
                         'assertEquals(owner, fixture.controller.active)',
                         'assertEquals(runningBindings, modelBindings())',
                         'assertEquals(runningDatabase, fixture.database.exportBackup())',
                         'assertEquals(runningOpens, fixture.opens.get())',
                         'assertFalse(fixture.controller.dataBusy)', 'assertChatPickerOffline(calls)']:
            self.assertIn(fragment, running)
        self.assertNotIn('assertIsNotEnabled()', running)


if __name__ == '__main__':
    unittest.main()
