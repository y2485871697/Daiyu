"""Timestamp source-style guards, not a substitute for device layout regression."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class MessageTimestampStyleContractTest(unittest.TestCase):
    def test_action_bar_reuses_retained_eleven_sp_timestamp(self):
        actions = (CHAT / 'MessageActionBar.kt').read_text(encoding='utf-8')
        self.assertIn('MessageTimestamp(timestamp, modifier = if (endAligned) Modifier else Modifier.weight(1f))', actions)
        self.assertNotIn('fontSize = 8.sp', actions)
        style = (CHAT / 'MessageTimestamp.kt').read_text(encoding='utf-8')
        self.assertIn('fontSize = 11.sp', style)
        self.assertIn('TextAlign.End', style)
        self.assertIn('maxLines = 1', style)
        self.assertIn('TextOverflow.Ellipsis', style)

    def test_only_assistant_receives_elastic_space_before_timestamp(self):
        actions = (CHAT / 'MessageActionBar.kt').read_text(encoding='utf-8')
        self.assertIn('modifier = if (endAligned) Modifier else Modifier.weight(1f)', actions)
        self.assertNotIn('Spacer(Modifier.weight(1f))', actions)
        self.assertIn('if (timestamp.isNotBlank())', actions)
        self.assertIn('if (endAligned) Alignment.End else Alignment.Start', actions)

    def test_production_user_and_assistant_calls_preserve_visibility_and_alignment(self):
        turns = (CHAT / 'ChatMessageList.kt').read_text(encoding='utf-8')
        self.assertIn('endAligned = true', turns)
        user, assistant = turns.split('internal fun AssistantTurn(', 1)
        self.assertIn('endAligned = true', user)
        self.assertNotIn('endAligned = true', assistant)
        self.assertIn('val hasDisplayBody = projection?.sources?.any { it.isNotBlank() } == true', assistant)
        self.assertIn('val initialBodyPending = sources.any { it.isNotBlank() } && !hasDisplayBody && !hasProjectedBody', assistant)
        self.assertIn('SideEffect { if (hasDisplayBody) hasProjectedBody = true }', assistant)
        self.assertIn('if (turn.status != RunStatus.RUNNING && !initialBodyPending && reasoningReady)', assistant)
        self.assertIn('allowSpeech = hasTextActions, showCopyAction = hasTextActions', turns)

    def test_production_user_hides_timestamp_without_changing_actions_or_assistant(self):
        turns = (CHAT / 'ChatMessageList.kt').read_text(encoding='utf-8')
        user = turns.split('internal fun UserTurn(', 1)[1].split('internal fun AssistantTurn(', 1)[0]
        assistant = turns.split('internal fun AssistantTurn(', 1)[1]
        self.assertNotIn('timestamp =', user)
        for setting in ('showEditAction = true', 'allowSpeech = false',
                        'showRegenerateAction = false', 'endAligned = true'):
            self.assertIn(setting, user)
        self.assertIn('if (actions.editingMessageKey != turn.listKey) MessageActionBar(', user)
        self.assertIn('MessageActionBar(turn.actionKey, actions, timestamp = turn.timestamp,', assistant)
        models = (CHAT / 'ChatScreenModels.kt').read_text(encoding='utf-8')
        self.assertIn('val actionKey: String get() = sourceListKeys.lastOrNull() ?: listKey', models)

    def test_time_precision_and_format_are_not_changed_by_visual_fix(self):
        formatter = (CHAT / 'MessageTimestampFormatter.kt').read_text(encoding='utf-8')
        self.assertIn('yyyy-MM-dd HH:mm', formatter)


if __name__ == '__main__':
    unittest.main()
