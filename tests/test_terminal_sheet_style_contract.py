"""Source contracts only; device rendering and Kotlin checks await the approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens'
SCREEN = (SRC / 'terminal/TerminalScreen.kt').read_text()
TASKS = (SRC / 'terminal/TerminalTasksDialog.kt').read_text()
SESSIONS = SCREEN.split('private fun TerminalSessionsDialog(', 1)[1].split('private fun TerminalToolButton(', 1)[0]
MENU = SCREEN.split('if (menu) ChatSelectionSheet(', 1)[1].split('private fun TerminalSessionsDialog(', 1)[0]


class TerminalSheetStyleContractTest(unittest.TestCase):
    def test_terminal_modals_use_existing_glass_shells(self):
        self.assertIn('AssistantPreviewDialog(show = true, title = "会话"', SESSIONS)
        self.assertIn('AssistantPreviewDialog(show = true, title = "后台任务"', TASKS)
        self.assertIn('if (menu) ChatSelectionSheet(title = "命令操作"', SCREEN)
        for text in (SCREEN, TASKS):
            self.assertNotIn('WindowDialog', text)
            self.assertNotIn('LocalDensity provides', text)
            self.assertNotIn('DialogProperties(', text)

    def test_create_is_filled_primary_footer_outside_scrolling_sessions(self):
        footer = SESSIONS.split('footer = {', 1)[1].split('}) {', 1)[0]
        self.assertIn('AssistantPreviewButton("新建会话", onNew', footer)
        self.assertIn('Modifier.fillMaxWidth().heightIn(min = actionMinHeight).testTag("terminal-create-session")', footer)
        self.assertIn('primary = true, enabled = canCreate', footer)
        self.assertNotIn('TerminalAction(', SESSIONS)
        self.assertIn('LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("terminal-session-list")', SESSIONS)

    def test_sheet_actions_have_real_minimum_height_and_preserve_permissions(self):
        for tag in ('terminal-restart:${entry.id}', 'terminal-close:${entry.id}'):
            self.assertIn('Modifier.heightIn(min = actionMinHeight).testTag("' + tag + '")', SESSIONS)
        self.assertIn('enabled = !entry.restarting', SESSIONS)
        for tag in ('terminal-task-read:$index', 'terminal-task-stop:$index'):
            self.assertIn('Modifier.heightIn(min = actionMinHeight).testTag("' + tag + '")', TASKS)
        self.assertIn('enabled = !busy || row.id in expanded', TASKS)
        self.assertIn('danger = true, enabled = !busy', TASKS)
        self.assertIn('if (row.id !in logs) row.onRead()', TASKS)
        self.assertIn('LaunchedEffect(outputId, output, busy)', TASKS)
        self.assertIn('itemsIndexed(rows, key = { _, row -> row.id })', TASKS)

    def test_sheet_minimum_uses_upward_pixel_alignment_and_scroll_completion(self):
        self.assertIn('ceil(48.dp.toPx().toDouble()).toInt().toDp()', TASKS)
        for text in (SESSIONS, TASKS):
            self.assertIn('val actionMinHeight = terminalSheetActionMinHeight()', text)
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/terminal/TerminalSheetLayoutTest.kt').read_text()
        for index in ('performScrollToIndex(5)', 'performScrollToIndex(19)'):
            after = source.split(index, 1)[1]
            self.assertLess(after.index('advanceTimeByFrame()'), after.index('assertIsDisplayed()'))
            self.assertLess(after.index('waitForIdle()'), after.index('assertIsDisplayed()'))
        self.assertEqual(2, source.count('assertHeightIsAtLeast(48.dp)'))

    def test_local_typography_and_cards_follow_assistant_palette(self):
        for text in (SESSIONS, TASKS):
            self.assertIn('val colors = assistantPreviewPalette()', text)
            self.assertIn('fontSize = 14.sp, lineHeight = 22.sp', text)
            self.assertIn('RoundedCornerShape(13.dp)', text)
            self.assertIn('Arrangement.spacedBy(8.dp)', text)
        self.assertIn('fontSize = 15.sp, lineHeight = 22.sp', SESSIONS)
        self.assertIn('fontSize = 13.sp, lineHeight = 20.sp', TASKS)
        self.assertIn('heightIn(max = 160.dp)', TASKS)

    def test_command_actions_retain_copy_reinput_and_dismiss(self):
        self.assertIn('clipboard.setText(AnnotatedString(block.command)); menu = false', MENU)
        self.assertIn('clipboard.setText(AnnotatedString(terminalPlainOutput(block.output))); menu = false', MENU)
        self.assertIn('onReinput(block.command); menu = false', MENU)
        self.assertIn('enabled = block.output.isNotEmpty()', MENU)
        self.assertEqual(3, MENU.count('heightIn(min = 48.dp)'))
        self.assertIn('.verticalScroll(rememberScrollState())', MENU)

    def test_existing_toolbar_action_is_not_globally_rescaled(self):
        action = SCREEN.split('internal fun TerminalAction(', 1)[1].split('private val terminalLayers', 1)[0]
        self.assertIn('heightIn(min = 28.dp)', action)
        self.assertIn('MaterialTheme.typography.labelMedium', action)
        assistant = (SRC / 'assistants/AssistantPreviewComponents.kt').read_text()
        self.assertIn('.then(surface)', assistant)
        self.assertIn('fontSize = (14f * s).sp, lineHeight = (20f * s).sp', assistant)


if __name__ == '__main__':
    unittest.main()
