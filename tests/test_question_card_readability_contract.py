"""Readable active and archived forms share the production question-card layout."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
CARD = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/QuestionCard.kt'
DEVICE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/QuestionCardReadabilityTest.kt'
WORKFLOW = ROOT / '.github/workflows/chat-workflow-checks.yml'


class QuestionCardReadabilityContractTest(unittest.TestCase):
    def test_question_and_option_content_use_readable_sizes(self):
        text = CARD.read_text()
        self.assertIn('Text(value.question.question, color = colors.text, style = questionTextStyle(16, 1.6f))', text)
        option = text.split('private fun QuestionOptionRow(', 1)[1].split('\n@Composable', 1)[0]
        self.assertIn('questionTextStyle(15, 1.6f, FontWeight.Medium)', option)
        self.assertIn('questionTextStyle(14, 1.6f)', option)
        self.assertIn('heightIn(min = 48.dp)', option)
        self.assertIn('.size(18.dp)', option)
        self.assertTrue(all(int(n) >= 11 for n in re.findall(r'questionTextStyle\((\d+),', text)))

    def test_other_and_note_keep_original_full_width_multiline_input_style(self):
        text = CARD.read_text()
        field = text.split('private fun QuestionInput(', 1)[1].split('\n@Composable', 1)[0]
        for token in ('val container = daiyuInputContainerColor()', 'RoundedCornerShape(14.dp)',
                      'modifier.managedInput().fillMaxWidth()', 'minLines = 1', 'maxLines = 6',
                      'heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp)',
                      'contentAlignment = Alignment.CenterStart', 'colors.accent.copy(alpha = .45f)'):
            self.assertIn(token, field)
        self.assertNotIn('max = 160.dp', field)
        self.assertNotIn('Modifier.padding(start = ', text)

    def test_archive_uses_confirmed_answer_and_full_option_layout_without_fading(self):
        text = CARD.read_text()
        for token in ('val archived = confirmedAnswer != null', 'val selection = confirmedAnswer ?: draft.selection',
                      'confirmedAnswer.text else draft.other', 'confirmedAnswer.note.orEmpty() else draft.note',
                      'if (value.interrupted && !archived)', 'if (editable || archived) 1f else .5f',
                      'if (!archived) {', 'mutableStateOf(true)'):
            self.assertIn(token, text)
        self.assertNotIn('已选择：', text)
        self.assertEqual(1, text.count('value.question.options.forEachIndexed'))

    def test_archived_field_is_complete_static_text_not_an_input_target(self):
        text = CARD.read_text()
        field = text.split('private fun QuestionInput(', 1)[1].split('\n@Composable', 1)[0]
        archived = field.split('if (archived) {', 1)[1].split('return', 1)[0]
        self.assertIn('Text(value.ifEmpty { hint }', archived)
        self.assertIn('.background(container)', archived)
        self.assertIn('.border(1.dp, colors.line, shape)', archived)
        for token in ('BasicTextField', 'managedInput', 'clickable', 'maxLines', '.alpha('):
            self.assertNotIn(token, archived)
        self.assertEqual(2, text.count('archived = archived)'))

    def test_other_hides_note_without_clearing_its_draft_or_submitting_it(self):
        text = CARD.read_text()
        self.assertIn('value.question.allowNote && selection !is QuestionAnswer.Other', text)
        self.assertIn('value.question.allowNote && draft.selection !is QuestionAnswer.Other && it.isNotEmpty()', text)
        select = text.split('fun select(answer: QuestionAnswer)', 1)[1].split('fun editOther', 1)[0]
        self.assertNotIn('draft.note =', select)
        self.assertIn('QuestionAnswer.Other(draft.other, note)', text)

    def test_option_indicator_and_text_block_center_within_symmetric_background(self):
        option = CARD.read_text().split('private fun QuestionOptionRow(', 1)[1].split('\n@Composable', 1)[0]
        self.assertIn('verticalAlignment = Alignment.CenterVertically', option)
        self.assertIn('.padding(horizontal = 7.dp, vertical = 9.dp)', option)
        self.assertNotIn('Alignment.Top', option)
        self.assertNotIn('padding(top = 3.dp)', option)
        self.assertIn('question-option-indicator', option)
        self.assertIn('question-option-text', option)

    def test_draft_owner_and_submission_authority_are_unchanged(self):
        text = CARD.read_text()
        for token in ('val session = remember(owner, binding)', 'session.editOther(it)', 'session.editNote(it)',
                      'enabled = submitEnabled, role = Role.Button, onClick = session::submit',
                      'questionTextLimit(it)', 'WorkflowExpandableContent(visibility, disclosure)',
                      'if (!editable) return', 'Reply.SAVED || reply == Reply.SAVED_REFRESH_FAILED'):
            self.assertIn(token, text)

    def test_exact_cloud_scope_and_real_device_assertions_cover_new_behaviors(self):
        workflow = WORKFLOW.read_text()
        self.assertIn('message-actions, question-card]', workflow)
        self.assertIn("patterns = ('test_question_card_readability_contract.py', 'test_stream_question_projection_contract.py', 'test_question_card_smoke_scope.py')", workflow)
        self.assertIn('filters=(--tests app.daiyu.android.question.QuestionCheckpointEvidenceTest', workflow)
        self.assertIn("expected = {'QuestionCheckpointEvidenceTest', 'QuestionToolBridgeTest'}", workflow)
        device = DEVICE.read_text()
        for token in ('assertArchived(0)', 'assertArchived(1)', 'assertArchived(2)',
                      'QuestionAnswer.Delegate(longText)', 'QuestionAnswer.Other(custom)',
                      'node("question-note").assertDoesNotExist()', 'hasSetTextAction()',
                      'isFocused()', 'Indicator centered', 'Text centered', 'SAVED_REFRESH_FAILED',
                      'twoLineLabel = true', 'assertEquals(2, layout(', 'assertEquals(1, layout('):
            self.assertIn(token, device)
