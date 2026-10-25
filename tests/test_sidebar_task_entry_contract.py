"""Source guards for removing only the sidebar task-record entry."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(name):
    return (SRC / name).read_text()


class SidebarTaskEntryContractTest(unittest.TestCase):
    def test_sidebar_no_longer_exposes_a_task_record_callback_or_menu_item(self):
        pane = source('ui/components/ConversationSidePaneScaffold.kt')
        self.assertNotIn('onConversationTasks', pane)
        self.assertNotIn('onTasks', pane)
        self.assertNotIn('R.string.business_task_records', pane)
        host = source('MainActivity.kt')
        self.assertNotIn('onConversationTasks', host)

    def test_remaining_long_press_actions_keep_their_original_order_and_menu(self):
        pane = source('ui/components/ConversationSidePaneScaffold.kt')
        row = pane.split('private fun ConversationTextRow(', 1)[1]
        menu = row.split('DrawerGlassMenu(', 1)[1].split('\n@Composable', 1)[0]
        positions = [menu.index(fragment) for fragment in (
            'R.string.action_rename', 'R.string.conversation_unpin',
            'R.string.conversation_export', 'R.string.drawer_move_to_folder', 'R.string.action_delete')]
        self.assertEqual(positions, sorted(positions))
        for callback in ('onRename()', 'onTogglePin()', 'onExport()', 'onMoveToFolder()', 'onDelete()'):
            self.assertIn(callback, menu)
        self.assertEqual(5, len(re.findall(r'\bDropdownMenuItem\(', menu)))
        self.assertNotIn('OptionSelectionSheet', menu)

    def test_tool_result_still_has_a_real_route_to_the_shared_record_page(self):
        host = source('MainActivity.kt')
        self.assertIn('ToolCardAction.READ_TASK_RESULT -> guard { openChildTaskRecords(owner.conversationId) }', host)
        self.assertIn('"child-tasks" -> taskRecordsConversationId?.let', host)
        self.assertIn('ChildTaskRecordsScreen(id, load = { target -> controller.childTasks(ConversationId(target)) }', host)
        self.assertIn('private fun openChildTaskRecords(id: String)', host)
        self.assertIn('retainedPage = "child-tasks"', host)

    def test_shared_status_queries_and_task_controls_are_not_deleted(self):
        status = source('ui/screens/chat/conversation/SubAgentStatusDialog.kt')
        self.assertIn('controller.childTasks(id, 20)', status)
        self.assertIn('controller.liveChildTasks(id)', status)
        card = source('MainActivity.kt')
        for callback in ('controller.pauseChildTask(target)', 'controller.resumeChildTask(target)',
                         'controller.stopChildTask(target)', 'controller.directChildTask(command)'):
            self.assertIn(callback, card)
        self.assertTrue((SRC / 'storage/SQLiteChildTaskStore.kt').is_file())
        self.assertTrue((SRC / 'ui/screens/conversation/ChildTaskRecordsScreen.kt').is_file())

    def test_android_regression_checks_absence_and_retained_actions_without_mutations(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ConversationPaneManagementActivityTest.kt').read_text()
        method = test.split('fun testConversationLongPressKeepsOriginalActionsWithoutTaskRecords()', 1)[1].split('\n    fun ', 1)[0]
        for fragment in ('R.string.business_task_records', 'assertCountEquals(0)',
                         'R.string.action_rename', 'R.string.conversation_pin',
                         'R.string.conversation_export', 'R.string.drawer_move_to_folder', 'R.string.action_delete',
                         'assertHasClickAction()', 'assertEquals(before, fixture.database.exportBackup())',
                         'assertEquals(0, fixture.opens.get())'):
            self.assertIn(fragment, method)


if __name__ == '__main__':
    unittest.main()
