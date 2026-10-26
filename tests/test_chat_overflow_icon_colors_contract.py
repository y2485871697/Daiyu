"""Keep the richer icon palette scoped to the existing overflow-menu entries."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMenus.kt"


class ChatOverflowIconColorsContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text()
        cls.overflow = cls.source.split("internal fun ChatOverflowMenu(", 1)[1].split("private fun ChatMenuItem(", 1)[0]
        cls.item = cls.source.split("private fun ChatMenuItem(", 1)[1].split("internal fun ChatAttachmentSheet(", 1)[0]

    def test_existing_ten_entries_and_routes_are_unchanged(self):
        self.assertEqual([
            "NEW_CHAT", "SEARCH", "INTERACTIVE_MODE", "OPEN_TERMINAL", "OPEN_BROWSER",
            "OPEN_WORKSPACE", "SUB_AGENT", "CONTEXT_STATS", "COMPRESS_CONTEXT", "AUTO_COMPRESS",
        ], re.findall(r'MenuEntry\("[^"\n]+", [^,\n]+, ChatMenuAction\.(\w+)\)', self.overflow))
        self.assertIn("checked = checked, enabled = enabled, previewStyle = true", self.overflow)

    def test_palette_only_changes_preview_icon_tint_and_preserves_disabled_alpha(self):
        self.assertIn("tint = (if (previewStyle)\n            chatOverflowIconColor(entry.action, dark, colors.accent) else daiyuIconColor(entry.icon))", self.item)
        self.assertIn(".copy(alpha = if (enabled) .9f else .38f)", self.item)
        self.assertEqual(2, self.source.count("chatOverflowIconColor("))
        self.assertIn("val dark = scheme.background.luminance() < .3f", self.item)

    def test_labels_switches_and_dismiss_behavior_do_not_follow_feature_colors(self):
        self.assertIn("if (isChecked) colors.accent else Color(0x2E9B8CB9)", self.item)
        self.assertIn("if (previewStyle) colors.text else scheme.onSurface", self.item)
        self.assertIn("if (entry.action != ChatMenuAction.INTERACTIVE_MODE && entry.action != ChatMenuAction.AUTO_COMPRESS) onDismiss()", self.item)
        self.assertIn("onAction(entry.action)", self.item)
        self.assertIn(".size(17.dp)", self.item)
        self.assertIn(".heightIn(min = 38.dp)", self.item)


if __name__ == "__main__":
    unittest.main()
