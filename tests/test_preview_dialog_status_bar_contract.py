"""Window wiring guards; Android window assertions run in the next approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class PreviewDialogStatusBarContractTest(unittest.TestCase):
    def test_dimmed_preview_keeps_black_status_text_on_its_own_window(self):
        source = (SRC / "ui/components/PreviewDialog.kt").read_text()
        effect = source.split("SideEffect {", 1)[1].split("DialogMotionProvider(", 1)[0]
        self.assertIn("import androidx.core.view.WindowCompat", source)
        self.assertIn("(LocalView.current.parent as? DialogWindowProvider)?.window", source)
        self.assertIn("WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)", effect)
        self.assertIn(".isAppearanceLightStatusBars = true", effect)
        self.assertNotIn("isAppearanceLightNavigationBars", source)
        self.assertNotIn("activity.window", source)
        self.assertNotIn("onDispose", effect)

    def test_workspace_and_selection_keep_the_shared_window_path(self):
        workspace = (SRC / "ui/components/ConversationWorkspaceSheet.kt").read_text()
        agent = (SRC / "ui/components/subagent/ConversationAgentStyle.kt").read_text()
        selection = (SRC / "ui/screens/chat/conversation/ChatSelectionSheet.kt").read_text()
        self.assertIn("ConversationAgentSheet(onDismiss", workspace)
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = true)", agent)
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = true,", selection)


if __name__ == "__main__":
    unittest.main()
