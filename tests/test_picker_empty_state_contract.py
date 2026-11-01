"""Empty list placement only; the Android geometry fixtures are compiled in cloud checks."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(name):
    return (SRC / name).read_text()


class PickerEmptyStateContractTest(unittest.TestCase):
    def test_shared_empty_text_centers_multiline_copy_without_new_sheet_sizing(self):
        shared = source('ui/components/GroupedPickerComponents.kt')
        empty = shared.split('internal fun GroupedPickerEmptyState(', 1)[1].split('// Draw a continuous', 1)[0]
        for part in ('Box(modifier, contentAlignment = Alignment.Center)', 'TextAlign.Center',
                     'Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)'):
            self.assertIn(part, empty)
        self.assertNotIn('height(', empty)
        sheet = source('ui/screens/chat/conversation/ChatSelectionSheet.kt')
        self.assertIn('weight(1f, fill = height != null)', sheet)
        self.assertIn('if (height == null) Modifier.heightIn(max = available)', sheet)

    def test_models_center_only_empty_feedback_keep_clear_row_and_status_suppression(self):
        text = source('ui/screens/chat/conversation/ChatModelPicker.kt')
        self.assertIn('val hasListContent = groups.isNotEmpty() || (onClearSelection != null && search.isEmpty())', text)
        self.assertIn('hasListContent -> null', text)
        self.assertIn('showStatusMessages && status != null -> status', text)
        self.assertIn('!state.loading && search.isNotEmpty() -> "没有找到匹配的模型"', text)
        self.assertIn('if (showStatusMessages && status != null && hasListContent) Text(status', text)
        self.assertIn('Box(Modifier.fillMaxWidth().weight(1f).testTag("chat-model-content"))', text)
        self.assertIn('GroupedPickerEmptyState(it, Modifier.matchParentSize().testTag("chat-model-empty"),', text)
        self.assertIn('item(key = "clear-selection")', text)
        self.assertIn('onClick = onClearSelection', text)
        self.assertNotIn('item(key = "empty")', text)
        self.assertIn('val canSelect = state.enabled && !state.loading && !state.switching', text)

    def test_tones_center_empty_and_search_feedback_keep_options_and_extra_content(self):
        text = source('VoiceTonePicker.kt')
        for part in ('if (groups.isNotEmpty()) message?.let', 'extraContent()',
                     'Box(Modifier.fillMaxWidth().weight(1f).testTag("voice-tone-content"))',
                     'if (groups.isEmpty()) GroupedPickerEmptyState(',
                     'if (search.isNotEmpty()) "没有找到匹配的音色" else message ?: "暂无可选音色"',
                     'Modifier.matchParentSize().testTag("voice-tone-empty")',
                     'onClick = { onSelect(voice.id) }'):
            self.assertIn(part, text)
        self.assertNotIn('item(key = "empty")', text)

    def test_assistant_empty_item_uses_full_viewport_without_asymmetric_padding(self):
        text = source('ui/screens/assistants/AssistantPickerDialogs.kt')
        self.assertIn('contentPadding = if (filtered.isEmpty()) PaddingValues(0.dp)', text)
        self.assertIn('else PaddingValues(start = 2.dp * s, end = 2.dp * s, top = 2.dp * s, bottom = 7.dp * s)', text)
        self.assertIn('Box(Modifier.fillParentMaxSize().testTag("assistant-picker-empty"), contentAlignment = Alignment.Center)', text)
        self.assertIn('lineHeight = (20f * s).sp, textAlign = TextAlign.Center', text)
        self.assertIn('items(filtered, key = { it.id })', text)

    def test_reference_and_provider_empty_regions_do_not_move_footers_or_errors(self):
        text = source('ui/screens/chat/conversation/ComposerShortcutPicker.kt')
        empty = text.split('if (filtered?.isEmpty() == true) item {', 1)[1]
        self.assertIn('Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center)', empty)
        self.assertIn('Text(if (conversation) "暂无可引用会话" else "暂无可用 Skill"', empty)
        self.assertIn('textAlign = TextAlign.Center', empty)
        provider = source('ui/screens/settings/ProviderModelSelectionDialog.kt')
        self.assertIn('GroupedPickerEmptyState(if (selection.totalCount == 0)', provider)
        self.assertIn('Modifier.fillMaxWidth().weight(1f).testTag("provider-model-empty")', provider)
        self.assertIn('if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)', provider)
        self.assertIn('onConfirm = { if (editable && selection.canConfirm) onConfirm(selection) }', provider)

    def test_cloud_checks_include_geometry_declarations_without_packaging(self):
        workflow = (ROOT / '.github/workflows/voice-selector-checks.yml').read_text()
        for part in ('test_picker_empty_state_contract.py', ':app:compileDebugKotlin',
                     ':app:compileDebugAndroidTestKotlin', 'checks_only.init.gradle',
                     'apk_built=False', 'instrumentation_run=False'):
            self.assertIn(part, workflow)
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/PickerEmptyStateActivityTest.kt').read_text()
        for part in ('testModelEmptyFeedbackAndLoadedTransitionKeepSearchFixed',
                     'testSearchEmptyAndClearSelectionKeepOriginalCallbacks',
                     'testToneEmptyAndSearchFeedbackShareTheRemainingRegion',
                     'testConversationAndSkillEmptyMessagesUseTheListViewport',
                     'TextAlign.Center', 'boundsInRoot', '1.3f', 'false, true'):
            self.assertIn(part, android)


if __name__ == '__main__':
    unittest.main()
