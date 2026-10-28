"""Window wiring guards; actual composited-pixel regressions await the approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class PreviewDialogStatusBarContractTest(unittest.TestCase):
    def test_preview_does_not_force_status_text_or_navigation_bar_appearance(self):
        source = (SRC / "ui/components/PreviewDialog.kt").read_text()
        self.assertNotIn("isAppearanceLightStatusBars", source)
        self.assertNotIn("isAppearanceLightNavigationBars", source)
        self.assertNotIn("WindowCompat", source)
        self.assertNotIn("activity.window", source)

    def test_default_scrim_reuses_original_window_dimming_without_double_darkening(self):
        source = (SRC / "ui/components/PreviewDialog.kt").read_text()
        self.assertIn("val dimAmount = remember(window) { window?.attributes?.dimAmount ?: 0f }", source)
        self.assertLess(source.index("val dimAmount ="), source.index("window?.setDimAmount(0f)"))
        self.assertIn("scrimColor ?: Color.Black.copy(alpha = dimAmount)", source)
        self.assertIn(".previewDialogDimBackground(dialogDimStack,", source)
        self.assertIn("Color.Black.copy(alpha = dimAmount), motion)", source)
        self.assertNotIn("0x2620172d", source)
        self.assertNotIn("0x55000000", source)

    def test_workspace_and_selection_keep_the_shared_window_path(self):
        workspace = (SRC / "ui/components/ConversationWorkspaceSheet.kt").read_text()
        agent = (SRC / "ui/components/subagent/ConversationAgentStyle.kt").read_text()
        selection = (SRC / "ui/screens/chat/conversation/ChatSelectionSheet.kt").read_text()
        self.assertIn("ConversationAgentSheet(onDismiss", workspace)
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = true)", agent)
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = true,", selection)


if __name__ == "__main__":
    unittest.main()
