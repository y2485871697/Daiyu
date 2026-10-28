"""Remove introductory copy at its producer; retain actual model availability errors."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
PROMPT = '请选择本地目录中的模型'


class ModelPickerCaptionContractTest(unittest.TestCase):
    def test_host_does_not_generate_a_default_local_directory_prompt(self):
        main = (SRC / 'MainActivity.kt').read_text()
        body = main.split('private fun reloadChatModels(', 1)[1].split('private fun selectChatModel(', 1)[0]
        message = body.split('val message = when {', 1)[1].split('chatModelPicker =', 1)[0]
        self.assertNotIn(PROMPT, main)
        self.assertNotIn('result?.activeEditor == null ->', message)
        self.assertIn('else -> null', message)
        for status in ('loaded.isFailure ->', 'result?.activeEditor != null && saved == null ->',
                       'result?.choices.isNullOrEmpty() ->'):
            self.assertIn(status, message)
        self.assertIn('message = message', body)

    def test_shared_picker_keeps_real_status_feedback_instead_of_hiding_all_messages(self):
        text = (SRC / 'ui/screens/chat/conversation/ChatModelPicker.kt').read_text()
        for fragment in ('showStatusMessages: Boolean = true', 'state.message != null -> state.message',
                         'if (showStatusMessages && status != null) Text(status',
                         'state.loading -> "正在读取模型目录…"', '"当前任务结束后可切换模型"'):
            self.assertIn(fragment, text)
        self.assertNotIn(PROMPT, text)

    def test_all_shared_entry_wrappers_are_free_of_the_introductory_copy(self):
        for path in ('ui/screens/chat/conversation/LocalCatalogChatModelPicker.kt',
                     'ui/components/subagent/SubAgentModelPicker.kt', 'VoiceSettingsDialogs.kt',
                     'ui/screens/settings/SettingsModelPicker.kt'):
            with self.subTest(path=path):
                text = (SRC / path).read_text()
                self.assertIn('ChatModelPicker(', text)
                self.assertNotIn(PROMPT, text)
        settings = (SRC / 'ui/screens/settings/SettingsModelPicker.kt').read_text()
        self.assertIn('failed -> "模型列表读取失败，请重新打开。"', settings)
        self.assertIn('displayed.isEmpty() -> "暂无可选模型，请先在供应商中添加模型。"', settings)
        validation = (SRC / 'context/ManualSummaryTargets.kt').read_text()
        self.assertIn('requireNotNull(CatalogSummaryBindings.decode(it))', validation)

    def test_android_regression_opens_the_real_unbound_host_and_keeps_safety_oracles(self):
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/IntegrationSettingsActivityTest.kt').read_text()
        body = android.split('fun testUnboundChatModelPickerOmitsLocalDirectoryPromptAndKeepsSelection()', 1)[1].split('fun testChatModelButtonOpensPicker', 1)[0]
        for fragment in ('ConversationModelSelections.write(fixture.database.writableDatabase, null, null)',
                         'assertNull(fixture.database.modelBinding(null))', 'awaitChatPickerLoaded()',
                         'ui.onNodeWithText("请选择本地目录中的模型").assertDoesNotExist()',
                         'node(option).assertIsDisplayed().assertIsEnabled().performClick()',
                         'assertChatBinding("second")', 'assertEquals(before, fixture.settings.load())',
                         'assertEquals(database, fixture.database.exportBackup())',
                         'assertChatPickerOffline(calls)', 'assertEquals(0, fixture.opens.get())'):
            self.assertIn(fragment, body)


if __name__ == '__main__':
    unittest.main()
