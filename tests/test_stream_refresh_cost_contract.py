from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/kotlin/app/daiyu/android"

class StreamRefreshCostContract(unittest.TestCase):
    def test_control_notifications_coalesce_without_waiting_for_display_vsync(self):
        text = (SOURCE / "ChatController.kt").read_text()
        body = text.split("private fun notifyChanged()", 1)[1].split("/** Real accepted runner events", 1)[0]
        self.assertLess(body.index("changedDispatchPending = true"), body.index("handler.postDelayed"))
        self.assertIn("}, 16L)", body)
        self.assertNotIn("Choreographer", body)
        self.assertLess(body.index("changedDispatchPending = false"), body.index("dispatchChanged()"))
        dispatch = text.split("private fun dispatchChanged()", 1)[1].split("private var changedDispatchPending", 1)[0]
        self.assertIn("if(closed) emptyList()", dispatch)
        self.assertIn("listeners[listener]===registration", dispatch)

    def test_live_owner_updates_before_history_gate(self):
        text = (SOURCE / "MainActivity.kt").read_text()
        body = text.split("private fun refresh()", 1)[1].split("private fun consumeOpenedAttachments", 1)[0]
        self.assertLess(body.index("entry.first to active"), body.index("streamingHistoryRefresh.needsRead"))
        self.assertIn("controller.dataRevision, editorGeneration", body)
        self.assertIn("force = refreshHistoryTailConversation == conversation.id", body)

    def test_read_failure_and_stale_result_guards_remain(self):
        body = (SOURCE / "MainActivity.kt").read_text().split("private fun loadRefreshedHistory", 1)[1].split("private fun loadAcceptedHistory", 1)[0]
        self.assertIn("streamingHistoryRefresh.invalidate()", body)
        self.assertIn("if (refreshed != shown)", body)
        for guard in ("editorGeneration != generation", "acceptedRequest != historyRequestSerial", "content != chatState.contentRevision", "owner != loadedHistoryOwner(id)", "refreshHistoryPending", "Dispatchers.IO"):
            self.assertIn(guard, body)

if __name__ == "__main__":
    unittest.main()
