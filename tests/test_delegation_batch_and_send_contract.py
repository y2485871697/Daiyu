from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
DOMAIN = ROOT / "core-domain/src/main/kotlin/app/daiyu/android/domain"

class DelegationBatchAndSendContract(unittest.TestCase):
    def test_ordinary_entries_explicitly_share_the_conversation_switch(self):
        source = (APP / "ChatController.kt").read_text()
        self.assertIn("private fun conversationChildrenEnabled(conversation: Conversation?): Boolean", source)
        self.assertEqual(3, source.count("withChildren = conversationChildrenEnabled(conversation)"))
        self.assertIn("val withChildren = conversationChildrenEnabled(conversation)", source)
        self.assertIn("withChildren = conversationChildrenEnabled(owner), actionRevision = revision", source)
        self.assertIn("MessageAction.REGENERATE, MessageAction.SAVE_EDIT ->", source)

    def test_specialized_entries_keep_opt_in_default(self):
        source = (APP / "ChatController.kt").read_text()
        self.assertIn("withChildren:Boolean = false", source)
        helper = source.split("private fun conversationChildrenEnabled", 1)[1].split("fun send(", 1)[0]
        self.assertIn("SubAgentConfigKey.Conversation(it.id.value)", helper)
        self.assertIn("?: false", helper)
        self.assertNotIn("enabled = true", helper)
        self.assertGreaterEqual(source.count("!withChildren"), 6)

    def test_no_eight_task_schema_or_domain_gate_remains(self):
        for name in ["RunChildModels.kt", "RunChildTasks.kt"]:
            self.assertIn("minimumItems = 1, maximumItems = null", (APP / "integration/tools" / name).read_text())
        for name in ["ChildModelBatch.kt", "RetainedChildTasks.kt"]:
            source = (DOMAIN / "delegation" / name).read_text()
            self.assertNotIn("1..8", source)
            self.assertNotIn("newFixedThreadPool", source)
            self.assertNotIn("MAX_TASKS", source)
            self.assertNotIn("MAX_WORKERS", source)
        self.assertIn("index >= 0", (DOMAIN / "delegation/ChildTaskStore.kt").read_text())

    def test_persisted_group_and_backup_keep_large_indices(self):
        source = (APP / "storage/SQLiteChildTaskStore.kt").read_text()
        group = source.split("internal fun forCall", 1)[1].split("private fun values", 1)[0]
        self.assertNotIn('"9"', group)
        self.assertNotIn("<= 8", group)
        self.assertIn("it.indices.toList()", group)
        backup = (APP / "backup/BackupChildTaskCodec.kt").read_text()
        self.assertIn("index in 0..Int.MAX_VALUE.toLong()", backup)

if __name__ == "__main__":
    unittest.main()
