"""Source wiring and SQLite predicate checks; Android input/lifetime tests run in the device batch."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class TaskConfigurationAdmissionContractTest(unittest.TestCase):
    def test_task_gate_covers_preparation_cleanup_and_retained_children(self):
        text = (SRC / 'ChatController.kt').read_text()
        gate = text.split('@Synchronized internal fun taskConfigurationBusy()', 1)[1].split('@Synchronized internal fun setChatReasoning', 1)[0]
        for required in ('preparing', 'active != null', 'resources != null', 'generationEntry != null',
                         'virtualDisplayEntry != null', 'guiEntry != null', 'generationReaders > 0',
                         'pendingShizuku != null', 'pendingPlatformAction != null', 'unacceptedTools.isNotEmpty()',
                         'hasLiveChildTasks()'):
            self.assertIn(required, gate)

    def test_model_rejection_precedes_switching_and_all_controller_side_effects(self):
        text = (SRC / 'MainActivity.kt').read_text()
        method = text.split('private fun selectChatModel(', 1)[1].split('private fun gatewayCurrent', 1)[0]
        self.assertLess(method.index('if (!allowTaskConfigurationChange()) return'), method.index('picker.copy(switching = true)'))
        self.assertLess(method.index('if (!allowTaskConfigurationChange()) return'), method.index('try {'))
        refresh = text.split('private fun refresh()', 1)[1].split('controlOwner = controller.active', 1)[0]
        self.assertNotIn('controller.active == null', refresh)
        load = text.split('chatModelPicker = (session?.state', 1)[1].split('private fun selectChatModel(', 1)[0]
        self.assertNotIn('controller.active == null', load)
        controller = (SRC / 'ChatController.kt').read_text().split('internal fun selectActiveProviderCatalogModel', 1)[1].split('internal fun renameProviderCatalogModel', 1)[0]
        self.assertLess(controller.index('check(idleAvailable() && !hasLiveChildTasks())'), controller.index('invalidateVideoConfiguration()'))
        self.assertIn('{ "请先结束任务" }', controller)

    def test_reasoning_is_saved_only_under_controller_idle_gate(self):
        text = (SRC / 'MainActivity.kt').read_text()
        body = text.split('onEffortChange = { effort ->', 1)[1].split('controller.observe(listener)', 1)[0]
        self.assertIn('allowTaskConfigurationChange()', body)
        self.assertIn('controller.setChatReasoning(thinkingOwner, effort)', body)
        self.assertNotIn('reasoningPreference.set(', body)
        controller = (SRC / 'ChatController.kt').read_text().split('@Synchronized internal fun setChatReasoning', 1)[1].split('@Synchronized internal fun deleteAssistant', 1)[0]
        self.assertLess(controller.index('if (!idleAvailable() || hasLiveChildTasks()) return false'), controller.index('reasoningPreference.set'))
        picker = (SRC / 'ui/components/subagent/ConversationThinkingPicker.kt').read_text().split('    ConversationAgentTypography {', 1)[1].split('/** .depth-slider', 1)[0]
        self.assertIn('ConversationDepthSlider(selected,', picker)
        self.assertNotIn('selected = index', picker)
        self.assertIn('choices.getOrNull(selected)', picker)

    def test_assistant_rejection_keeps_picker_and_management_state(self):
        text = (SRC / 'ui/screens/assistants/AssistantPickerDialogs.kt').read_text()
        self.assertIn('if (canInteract()) { onSelect(profile.id); onDismiss() }', text)
        self.assertIn('if (canInteract()) actionProfileId = profile.id', text)
        self.assertIn('if (canInteract()) { deleteProfile = null; onDelete(profile.id) }', text)
        self.assertIn('if (canInteract()) onEdit(profile.id)', text)
        self.assertIn('canInteract = canInteract', text)
        host = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('canInteract = { assistantOwnerMatches(owner) && allowTaskConfigurationChange() }', host)
        self.assertIn('controller.deleteAssistant(id)', host)
        self.assertIn('if (sidebarVisible) selectCurrentConversationAssistant(id, renderedAssistantConversation, renderedAssistantGeneration)', host)
        gate = host.split('private fun allowTaskConfigurationChange()', 1)[1].split('private var assistantPicker', 1)[0]
        self.assertIn('toast("请先结束任务")', gate)

    def test_delete_reserves_data_lane_before_store_or_asset_writes(self):
        text = (SRC / 'ChatController.kt').read_text().split('@Synchronized internal fun deleteAssistant', 1)[1].split('internal val selections', 1)[0]
        self.assertLess(text.index('check(idleAvailable() && !hasLiveChildTasks())'), text.index('dataTask('))
        self.assertLess(text.index('dataTask('), text.index('assistantStore.delete(id)'))
        self.assertLess(text.index('assistantStore.delete(id)'), text.index('assistantAssets.deleteMemory(id)'))
        store = (SRC / 'assistants/SqliteAssistantStore.kt').read_text().split('override fun delete(id: String) = change { db ->', 1)[1].split('override fun select', 1)[0]
        self.assertLess(store.index("m.status='RUNNING'"), store.index('db.delete("assistants"'))
        self.assertIn('throw AssistantInUseException()', store)
        self.assertIn('if (failure is AssistantInUseException) "请先结束任务"', (SRC / 'ChatController.kt').read_text())

    def test_actual_sql_query_distinguishes_running_other_conversation_from_idle_binding(self):
        source = (SRC / 'assistants/SqliteAssistantStore.kt').read_text()
        query = re.search(r'rawQuery\("(SELECT 1 FROM conversations c JOIN messages m[^"\n]+)"', source)[1]
        with sqlite3.connect(':memory:') as db:
            db.executescript("""
                CREATE TABLE conversations(id TEXT PRIMARY KEY, assistant_id TEXT);
                CREATE TABLE messages(conversation_id TEXT, status TEXT);
                INSERT INTO conversations VALUES('running-x', 'x'), ('viewing-y', 'y'), ('idle-x', 'x');
                INSERT INTO messages VALUES('running-x', 'RUNNING'), ('idle-x', 'COMPLETED'), ('viewing-y', 'COMPLETED');
            """)
            self.assertIsNotNone(db.execute(query, ('x',)).fetchone())
            self.assertIsNone(db.execute(query, ('y',)).fetchone())
            self.assertIsNone(db.execute(query, ('missing',)).fetchone())
            for terminal in ('COMPLETED', 'STOPPED', 'FAILED', 'INTERRUPTED'):
                db.execute("UPDATE messages SET status=? WHERE conversation_id='running-x'", (terminal,))
                self.assertIsNone(db.execute(query, ('x',)).fetchone())
            db.execute("INSERT INTO messages VALUES('idle-x', 'RUNNING')")
            self.assertIsNotNone(db.execute(query, ('x',)).fetchone())


if __name__ == '__main__':
    unittest.main()
