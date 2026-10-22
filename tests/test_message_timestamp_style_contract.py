"""Timestamp source-style guards, not a substitute for device layout regression."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class MessageTimestampStyleContractTest(unittest.TestCase):
    def test_action_bar_reuses_retained_eleven_sp_timestamp(self):
        actions = (CHAT / 'MessageActionBar.kt').read_text(encoding='utf-8')
        self.assertIn('MessageTimestamp(timestamp)', actions)
        self.assertNotIn('fontSize = 8.sp', actions)
        style = (CHAT / 'MessageTimestamp.kt').read_text(encoding='utf-8')
        self.assertIn('fontSize = 11.sp', style)
        self.assertIn('TextAlign.End', style)
        self.assertIn('maxLines = 1', style)
        self.assertIn('TextOverflow.Ellipsis', style)

    def test_only_assistant_receives_elastic_space_before_timestamp(self):
        actions = (CHAT / 'MessageActionBar.kt').read_text(encoding='utf-8')
        self.assertIn('if (!endAligned) Spacer(Modifier.weight(1f))', actions)
        self.assertLess(actions.index('if (!endAligned) Spacer'), actions.index('MessageTimestamp(timestamp)'))
        self.assertIn('if (timestamp.isNotBlank())', actions)
        self.assertIn('if (endAligned) Alignment.End else Alignment.Start', actions)

    def test_production_user_and_assistant_calls_preserve_visibility_and_alignment(self):
        turns = (CHAT / 'ChatMessageList.kt').read_text(encoding='utf-8')
        self.assertIn('endAligned = true', turns)
        self.assertIn('if (turn.status != RunStatus.RUNNING)', turns)
        self.assertIn('allowSpeech = hasTextActions, showCopyAction = hasTextActions', turns)

    def test_time_precision_and_format_are_not_changed_by_visual_fix(self):
        formatter = (CHAT / 'MessageTimestampFormatter.kt').read_text(encoding='utf-8')
        self.assertIn('yyyy-MM-dd HH:mm', formatter)


if __name__ == '__main__':
    unittest.main()
