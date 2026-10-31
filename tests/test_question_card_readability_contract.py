"""Readable question content stays local to the interactive question card."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
CARD = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/QuestionCard.kt'


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

    def test_other_and_note_use_full_width_soft_multiline_fields(self):
        text = CARD.read_text()
        field = text.split('private fun QuestionInput(', 1)[1].split('\n@Composable', 1)[0]
        self.assertIn('val container = daiyuInputContainerColor()', field)
        self.assertIn('RoundedCornerShape(14.dp)', field)
        self.assertIn('modifier.managedInput().fillMaxWidth()', field)
        self.assertIn('minLines = 1', field)
        self.assertIn('maxLines = 6', field)
        self.assertIn('heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp)', field)
        self.assertIn('contentAlignment = Alignment.CenterStart', field)
        self.assertIn('colors.accent.copy(alpha = .45f)', field)
        self.assertNotIn('max = 160.dp', field)
        self.assertNotIn('Modifier.padding(start = ', text)
        self.assertEqual(2, field.count('questionTextStyle(15, 1.6f)'))

    def test_draft_owner_and_submission_authority_are_unchanged(self):
        text = CARD.read_text()
        for token in ('val session = remember(owner, binding)',
                      'session.editOther(it)', 'session.editNote(it)',
                      'enabled = submitEnabled, role = Role.Button, onClick = session::submit',
                      'questionTextLimit(it)', 'SettingsExpandableContent(expanded) {'):
            self.assertIn(token, text)
