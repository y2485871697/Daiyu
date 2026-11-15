"""Sidebar selection deletion wiring; real SQLite outcomes are covered on both Android APIs."""
from pathlib import Path
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
SPEC = importlib.util.spec_from_file_location("batch_delete_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
RECORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECORDER)


class ConversationBatchDeleteContractTest(unittest.TestCase):
    def test_management_routes_selected_ids_instead_of_long_pressed_row(self):
        pane = (APP / "ui/components/ConversationSidePaneScaffold.kt").read_text()
        self.assertEqual(2, pane.count("onConversationsDelete: (List<String>) -> Unit"))
        self.assertIn("onConversationsDelete = onConversationsDelete", pane)
        self.assertIn("if (managing) onConversationsDelete(selectedConversationIds)", pane)
        self.assertIn("else onConversationDelete(conversation)", pane)
        self.assertIn("batchDelete = managing", pane)
        self.assertIn("deleteEnabled = !managing || selectedConversationIds.isNotEmpty()", pane)
        self.assertIn("enabled = deleteEnabled", pane)
        self.assertIn("if (batchDelete) R.string.conversation_batch_delete else R.string.action_delete", pane)
        for folder, label in (("values", "Batch delete"), ("values-b+zh+Hans", "批量删除"),
                              ("values-b+zh+Hant", "批次刪除")):
            resource = (ROOT / "app/src/main/res" / folder / "preserved_ui.xml").read_text()
            self.assertIn(f'<string name="conversation_batch_delete">{label}</string>', resource)

    def test_frozen_batch_uses_one_owner_boundary_and_updates_only_after_success(self):
        activity = (APP / "MainActivity.kt").read_text()
        self.assertIn("onConversationsDelete = { ids -> confirmDeleteConversations(ids) }", activity)
        confirmation = activity.split("private fun confirmDeleteConversations(", 1)[1].split("private fun editRetainedConversation", 1)[0]
        self.assertIn("val owners = ids.toSet()", confirmation)
        self.assertIn("if (owners.isEmpty()) return", confirmation)
        self.assertIn('ConfirmPrompt("批量删除", "", "批量删除", true,', confirmation)
        self.assertIn("deleteRetainedConversations(owners)", confirmation)
        self.assertIn("showCloseButton = false, deletionAppearance = true", confirmation)
        deletion = activity.split("private fun deleteRetainedConversations(", 1)[1].split("private fun confirmDelete(id:", 1)[0]
        self.assertIn("require(ids.isNotEmpty())", deletion)
        self.assertIn("check(conversations.size == ids.size)", deletion)
        self.assertEqual(1, deletion.count("controller.mutateGenerationOwners("))
        self.assertIn("controller.mutateGenerationOwners(ids, completed = { success ->", deletion)
        self.assertIn("if (activityUsable() && success)", deletion)
        self.assertIn("controller.database.deleteConversations(conversations.map { it.id }.toSet())", deletion)
        self.assertIn("if (selected?.id?.value in ids) select(null)", deletion)
        controller = (APP / "ChatController.kt").read_text().split("internal fun mutateGenerationOwners(", 1)[1].split("private fun sealGenerationOwners", 1)[0]
        self.assertLess(controller.index("closeGenerationOutputReaders(selected)"), controller.index("mutation()"))
        self.assertLess(controller.index("mutation()"), controller.index("SELECT 1 FROM conversations"))
        self.assertLess(controller.index("SELECT 1 FROM messages"), controller.index("screenshots.deleteConversation(id)"))

    def test_one_database_transaction_verifies_every_target_before_commit(self):
        database = (APP / "storage/ConversationDatabase.kt").read_text()
        batch = database.split("fun deleteConversations(", 1)[1].split("private fun deleteConversation(", 1)[0]
        self.assertEqual(1, batch.count("transaction { db ->"))
        self.assertIn("require(ids.isNotEmpty())", batch)
        self.assertIn("check(deleteConversation(db, id) == 1)", batch)
        self.assertIn('check(!it.moveToFirst()) { "会话删除未确认" }', batch)
        self.assertIn('check(!it.moveToFirst()) { "消息删除未确认" }', batch)
        self.assertLess(batch.index("deleteConversation(db, id)"), batch.index("SELECT 1 FROM messages"))
        helper = database.split("private fun deleteConversation(", 1)[1].split("internal fun storedMessage(", 1)[0]
        for retained in ("UsageStatisticsStore.retire", "SQLiteMessageVideoStore.deleteConversation",
                         "SQLiteGenerationOutputStore.TABLE", "neutral_assets", "context_archives",
                         'db.delete("messages"', "ConversationModelSelections.delete"):
            self.assertIn(retained, helper)

    def test_real_activity_suite_asserts_db_deletion_and_atomic_failure(self):
        tests = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ConversationBatchDeleteActivityTest.kt").read_text()
        for method in ("testManagementDeletesSelectedConversationsAndMessagesNotLongPressedUnselectedRow",
                       "testManagementBatchDeleteIsDisabledWithNoSelectedConversations",
                       "testManagementFailureRollsBackEverySelectedConversationAndDoesNotReportSuccess",
                       "testBatchTransactionRollsBackWhenSelectedMessageDeleteIsIgnored"):
            self.assertIn("fun " + method + "()", tests)
        for assertion in ("SELECT 1 FROM messages WHERE conversation_id=?", "RAISE(IGNORE)",
                          "assertEquals(before, fixture.database.exportBackup())", "assertIsNotEnabled()",
                          "assertEquals(survivorMessages, fixture.database.messages(survivor.id))"):
            self.assertIn(assertion, tests)

    def test_smoke_discovery_only_reads_two_sources_and_allocates_each_class_once(self):
        import json
        import tempfile
        import test_speech_platform_discovery as discovery
        classes = RECORDER.BATCH_DELETE_PLATFORM_CLASSES
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = discovery.SpeechPlatformDiscoveryTest()
            helper.fixture(root)
            for name in classes:
                path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package app.daiyu.android\nclass " + name.rsplit(".", 1)[1]
                                + " : TestCase() {\n fun testExample() {}\n}\n")
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(classes))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({name: 1 for name in classes}, inventory["all_classes"])
                selected.extend(selection["selected_classes"])
            self.assertEqual(sorted(classes), sorted(selected))

    def test_cloud_scope_is_exact_incremental_and_rejects_partial_duplicate_or_unknown(self):
        classes = RECORDER.BATCH_DELETE_PLATFORM_CLASSES
        modules = RECORDER.BATCH_DELETE_MODULES
        for selected in (classes, tuple(reversed(classes))):
            command, scope = RECORDER.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))
            self.assertEqual("conversation-batch-delete-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *modules, "-v"], command)
        for selected in (classes[:-1], (*classes, classes[0]), (*classes, "app.daiyu.android.OtherTest")):
            self.assertEqual("full", RECORDER.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))[1])
        for mode in ("compile", "daily", "release", "candidate"):
            self.assertEqual("full", RECORDER.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=",".join(classes)))[1])


if __name__ == "__main__":
    unittest.main()
