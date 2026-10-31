"""Guard the production compression routes and per-request durable admission seams."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
DOMAIN = ROOT / "core-domain/src/main/kotlin/app/daiyu/android/domain"

class SummaryCompressionChainContract(unittest.TestCase):
    def test_confirm_submits_once_without_a_second_confirmation_surface(self):
        dialog = (APP / "ui/screens/chat/context/CompressionConversationDialog.kt").read_text()
        route = (APP / "ui/screens/chat/context/ContextManagementPanel.kt").read_text()
        self.assertIn('"确定", onConfirm', dialog)
        self.assertNotIn("context-compression-confirmation", dialog)
        self.assertIn("adapter.submitCompression(selection)", route)
        self.assertNotIn("adapter.requestCompression()", route)
        self.assertIn("state?.compressionAccepted == true && isCurrent()", route)

    def test_live_manual_request_only_queues_without_cancelling_the_tool_or_stream(self):
        runner = (DOMAIN / "ChatRunner.kt").read_text()
        queued = runner.split("fun requestCompaction(", 1)[1].split("@Volatile private var pauseRequested", 1)[0]
        self.assertIn("pendingCompact = CompactRequest", queued)
        self.assertNotIn("cancel", queued)
        self.assertIn("pendingCompact == null && (!base.automatic || pressure == null)", runner)
        opened = runner.split("fun open(): ModelSession", 1)[1].split("val token =", 1)[0]
        self.assertIn("compactAtBoundary()", opened)

    def test_preparation_chunk_claim_checks_persistence_before_advancing_parent_revision(self):
        code = (APP / "context/SQLitePreparationRuntime.kt").read_text()
        claim = code.split("fun nextPort()", 1)[1].split("}) { supplied, usage", 1)[0]
        self.assertIn("check(db.insertOrThrow(CHUNKS", claim)
        self.assertIn('!= -1L) { "summary_preparation_chunk_admission_not_persisted" }', claim)
        self.assertLess(claim.index("check(db.insertOrThrow"), claim.index("update(db, row"))
        host = (APP / "ChatController.kt").read_text()
        self.assertIn("nextSummaryUsage =", host)

    def test_child_unknown_summary_is_durable_and_not_a_reported_zero(self):
        store = (APP / "model/usage/SQLiteChildUsageStore.kt").read_text()
        self.assertIn("checkpoint.context?.summaries.orEmpty()", store)
        self.assertIn('put("has_usage", if (record.snapshot == null) 0 else 1)', store)
        self.assertIn("record.snapshot?.let", store)
        self.assertIn("require(requestOwners.size == 1)", store)
        self.assertIn("if (missingSummary) missing++", store)
        self.assertIn("child_usage_admission_not_persisted", store)
        self.assertIn("child_usage_report_lost", store)

    def test_old_child_backups_preserve_reported_null_while_new_backups_keep_presence(self):
        codec = (APP / "backup/BackupChildTaskCodec.kt").read_text()
        self.assertIn('put("version", 6)', codec)
        self.assertIn('if (version < 5L) put("has_usage", 1)', codec)
        self.assertIn('val hasUsage = number(row, "has_usage")', codec)
        self.assertIn('WHERE u.has_usage=1', (APP / "storage/ConversationUsageStore.kt").read_text())

    def test_chunk_backup_and_main_child_context_wiring_are_retained(self):
        self.assertIn('read(db, "runtime_preparation_chunks"', (APP / "backup/BackupRuntimeUsageCodec.kt").read_text())
        child = (DOMAIN / "delegation/ToolEnabledChildRun.kt").read_text()
        self.assertIn("contextRuntime = contextRuntime", child)
        self.assertLess(child.index("store.started(record)"), child.index("childRunner.run"))
        ui = (APP / "ui/screens/chat/conversation/ChatMessageList.kt").read_text()
        self.assertIn("activity.agentLabel", ui)
        self.assertIn("activity.id", ui)
        self.assertIn("model?.displayLabel", (APP / "ui/screens/chat/conversation/ChatTimelineDivider.kt").read_text())

if __name__ == "__main__":
    unittest.main()
