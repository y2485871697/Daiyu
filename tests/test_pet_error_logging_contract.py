"""Source contracts for pet-only silent pages; runtime cases live in Kotlin tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(path):
    return (APP / path).read_text()


class PetErrorLoggingContract(unittest.TestCase):
    def test_pet_settings_has_no_error_or_diagnostic_rows(self):
        screen = source('ui/screens/settings/PetSettingsScreen.kt')
        for text in ('snapshot.pet.error', 'model.problem', 'pet-model-problem', 'pet-error'):
            self.assertNotIn(text, screen)
        self.assertIn('Modifier.testTag("pet-enabled")', screen)
        for tag in ('pet-work-reactions', 'pet-global-visible', 'pet-custom-model', 'pet-model-configure'):
            self.assertIn('tag = "' + tag + '"', screen)
        self.assertIn('onCheckedChange = model.onEnabledChange', screen)
        self.assertIn('model.onChoose else null', screen)

    def test_pet_slot_suppresses_model_status_but_other_slots_keep_it(self):
        selection = source('ui/screens/settings/IndependentModelScreen.kt')
        self.assertIn('if (slot != IndependentModelSettings.Slot.PET)\n                controls.problem?.let', selection)
        self.assertIn('showStatusMessages = slot != IndependentModelSettings.Slot.PET', selection)
        self.assertIn('onLoadFailure = { if (slot == IndependentModelSettings.Slot.PET) logPetError(', selection)

    def test_picker_passes_status_policy_without_changing_selection_gates(self):
        settings = source('ui/screens/settings/SettingsModelPicker.kt')
        self.assertIn('showStatusMessages: Boolean = true', settings)
        self.assertIn('showStatusMessages = showStatusMessages', settings)
        self.assertIn('enabled = !loading && !failed && displayed.isNotEmpty()', settings)
        self.assertIn('catch (cancelled: CancellationException) { throw cancelled }', settings)
        self.assertIn('catch (_: Exception) { failed = true; onLoadFailure() }', settings)
        picker = source('ui/screens/chat/conversation/ChatModelPicker.kt')
        self.assertIn('showStatusMessages: Boolean = true', picker)
        self.assertIn('if (showStatusMessages && status != null) Text(status,', picker)
        self.assertIn('val canSelect = state.enabled && !state.loading', picker)
        self.assertIn('enabled = canSelect', picker)

    def test_runtime_errors_are_logged_from_callback_before_ui_post_and_on_close(self):
        host = source('pet/PetHost.kt')
        observation = host.split('observation = controller.observe { snapshot ->', 1)[1].split('\n        }', 1)[0]
        self.assertLess(observation.index('runtimeErrors.update(snapshot.error)'), observation.index('ui.post'))
        self.assertIn('overlay.close(); controller.close()\n            runtimeErrors.update(controller.snapshot().error)', host)
        self.assertIn('current.error?.contains("存储") != true', host)

    def test_overlay_permission_state_is_not_logged_as_an_error(self):
        host = source('pet/PetHost.kt')
        self.assertIn('overlayErrors.update(error.takeIf { state == PetOverlayState.ERROR })', host)
        self.assertNotIn('logPetError(', host)
        self.assertEqual(2, host.count('= PetErrorLog()'))

    def test_logger_deduplicates_until_recovery_and_does_not_accept_throwables(self):
        log = source('pet/PetErrorLog.kt')
        self.assertIn('android.util.Log.e("DaiyuPet", message)', log)
        self.assertIn('@Synchronized fun update(error: String?)', log)
        self.assertLess(log.index('if (error == previous) return'), log.index('previous = error'))
        self.assertLess(log.index('previous = error'), log.index('if (error != null) write(error)'))
        self.assertNotIn('Throwable', log)
        self.assertNotIn('Exception', log)

    def test_config_logs_are_pet_only_fixed_messages_not_state_or_exception_payload(self):
        selection = source('ui/screens/settings/IndependentModelScreen.kt')
        for message in ('说话模型配置读取失败', '说话模型配置更新失败', '说话模型停用失败', '说话模型列表读取失败'):
            self.assertIn('if (slot == IndependentModelSettings.Slot.PET) logPetError("' + message + '")', selection)
        self.assertEqual(3, selection.count('catch (cancelled: CancellationException) { throw cancelled }'))
        self.assertNotIn('logPetError(problem', selection)
        self.assertNotIn('logPetError("请先创建助手', selection)

    def test_successful_no_model_feed_still_has_no_error_assignment(self):
        controller = (ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain/pet/PetController.kt').read_text()
        branch = controller.split('if (!state.settings.model.configured) {', 1)[1].split('return start(PetEvent.FEED', 1)[0]
        self.assertNotIn('error =', branch.split('} catch (_: Exception)', 1)[0])
        self.assertIn('error = "已投喂；桌宠动作定时服务不可用"', branch)
        self.assertIn('return PetCommandResult.Applied', branch)

    def test_kotlin_regressions_cover_ui_suppression_and_default_compatibility(self):
        android = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'
        activity = (android / 'ActivityLifecycleTest.kt').read_text()
        self.assertIn('fun testPetRuntimeFailureDoesNotAppearOnSettingsPage()', activity)
        self.assertIn('ui.onNodeWithText(failure).assertDoesNotExist()', activity)
        picker = (android / 'ui/screens/chat/conversation/ChatModelPickerLayoutTest.kt').read_text()
        self.assertIn('fun testPetStatusSuppressionKeepsLoadingAndFailureSelectionDisabled()', picker)
        self.assertIn('fun testOtherPickerEntrancesStillShowStatusByDefault()', picker)
        self.assertIn('node("chat-model-option:same").assertIsNotEnabled()', picker)


if __name__ == '__main__':
    unittest.main()
