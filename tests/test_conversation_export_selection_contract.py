from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class ConversationExportSelectionContract(unittest.TestCase):
    def test_single_and_batch_use_the_same_direct_picker(self):
        activity = (APP / 'MainActivity.kt').read_text()
        self.assertIn('pickConversationExports(listOf(item.id))', activity)
        self.assertIn('onConversationsExport = { ids -> backupGuard { pickConversationExports(ids) } }', activity)
        self.assertNotIn('ConversationExportScreen', activity)
        self.assertNotIn('retainedPage = "conversation-export"', activity)
        self.assertIn('Intent.ACTION_CREATE_DOCUMENT', activity)
        self.assertIn('"daiyu-conversations.json"', activity)

    def test_selection_is_frozen_saved_restored_and_consumed_once(self):
        activity = (APP / 'MainActivity.kt').read_text()
        self.assertIn('getStringArrayList("pending-conversation-exports")', activity)
        self.assertIn('putStringArrayList("pending-conversation-exports"', activity)
        self.assertIn('val originalIds = conversationExport.take()', activity)
        self.assertIn('controller.exportConversationsBackup(checkNotNull(uri), originalIds.map(::ConversationId))', activity)
        selection = (APP / 'ui/actions/ConversationActionState.kt').read_text()
        self.assertIn('ids.isNotEmpty()', selection)
        self.assertIn('ids.distinct()', selection)
        self.assertIn('pendingConversationIds = null', selection)

    def test_context_menu_replaces_duplicate_button_and_exports_the_same_selection_it_labels(self):
        ui = (APP / 'ui/components/ConversationSidePaneScaffold.kt').read_text()
        self.assertNotIn('drawer-export-selected', ui)
        self.assertNotIn('导出所选', ui)
        self.assertIn('val selectedExportIds = state.historyConversations.filter { it.id in selectedIds }.map { it.id }', ui)
        self.assertIn('val batchExport = managing && selectedExportIds.size > 1', ui)
        self.assertIn('batchExport = batchExport', ui)
        self.assertIn('if (batchExport) onConversationsExport(selectedExportIds)', ui)
        self.assertIn('else onConversationExport(conversation)', ui)
        self.assertIn('if (batchExport) R.string.conversation_export_batch else R.string.conversation_export', ui)
        self.assertIn('enabled = exportEnabled', ui)

    def test_batch_menu_has_all_existing_localizations(self):
        values = {'values': 'Batch export', 'values-b+zh+Hans': '批量导出', 'values-b+zh+Hant': '批次匯出'}
        for folder, label in values.items():
            resource = (ROOT / 'app/src/main/res' / folder / 'preserved_ui.xml').read_text()
            self.assertIn(f'<string name="conversation_export_batch">{label}</string>', resource)

    def test_one_prepared_package_and_output_stream_for_all_selected(self):
        controller = (APP / 'ChatController.kt').read_text()
        self.assertIn('internal fun exportConversationsBackup(', controller)
        batch = controller.split('internal fun exportConversationsBackup(', 1)[1].split('internal fun importUserBackup(', 1)[0]
        self.assertIn('val targets = conversationIds.toList()', batch)
        self.assertIn('neutralBackup.prepareConversationsExport(targets)', batch)
        self.assertEqual(1, batch.count('openOutputStream('))
        self.assertLess(batch.index('prepareConversationsExport'), batch.index('openOutputStream'))
        service = (APP / 'backup/NeutralBackupService.kt').read_text()
        self.assertIn('targets.isNotEmpty() && targets.size <= 1000', service)
        self.assertIn('captureExport(db, emptyMap(), id)', service)
        self.assertIn('captureSelectedExport(configuration, conversationId, conversationIds).signature() == capture.signature()', service)
        self.assertIn('validatePayload(8, "export-validation", payload, originals)', service)

if __name__ == '__main__':
    unittest.main()
