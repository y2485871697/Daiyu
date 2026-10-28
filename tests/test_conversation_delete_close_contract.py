"""Only explicitly scoped confirmations opt out of their redundant cross."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"

class ConversationDeleteCloseContractTest(unittest.TestCase):
    def test_conversation_deletion_opts_out_at_its_prompt(self):
        main = (APP / "MainActivity.kt").read_text()
        deletion = main.split("private fun confirmDelete(", 1)[1].split("private fun editRetainedConversation", 1)[0]
        self.assertIn("showCloseButton = false", deletion)
        self.assertIn("deleteRetainedConversation(id)", deletion)
        prompt = main.split("private class ConfirmPrompt(", 1)[1].split("private var prompt", 1)[0]
        self.assertIn("val showCloseButton: Boolean = true", prompt)
        for start, end in (("onRegenerate = { id ->", "}, onSpeak ="),
                           ("onDeleteTurn = { id ->", "}, onRetryStep =")):
            scoped = main.split(start, 1)[1].split(end, 1)[0]
            self.assertIn("showCloseButton = false", scoped)
        self.assertEqual(3, main.count("showCloseButton = false"))

    def test_prompt_and_preview_forward_close_visibility_without_changing_callbacks(self):
        main = (APP / "MainActivity.kt").read_text()
        rendering = main.split("is ConfirmPrompt ->", 1)[1].split("private fun dismissPrompt", 1)[0]
        self.assertIn("showCloseButton = current.showCloseButton", rendering)
        self.assertIn("confirmPrompt(current)", rendering)
        self.assertIn("if (valid()) dismissPrompt(current)", rendering)
        dialog = (APP / "ui/components/DaiyuDialogs.kt").read_text().split("internal fun DaiyuConfirmDialog(", 1)[1].split("internal val RetainedChoiceSelect", 1)[0]
        self.assertIn("showCloseButton: Boolean = true", dialog)
        self.assertIn("showCloseButton = showCloseButton", dialog)
        self.assertIn('AssistantPreviewButton("取消", onDismiss', dialog)
        self.assertIn("AssistantPreviewButton(confirmText, onConfirm", dialog)
        self.assertNotIn("dismissEnabled = false", dialog)

    def test_shared_sheet_still_has_its_default_cross_and_dismissal_behavior(self):
        sheet = (APP / "ui/screens/chat/conversation/ChatSelectionSheet.kt").read_text()
        self.assertIn("showCloseButton: Boolean = true", sheet)
        self.assertIn("if (showCloseButton) Box", sheet)
        self.assertIn("dismissEnabled: Boolean = true", sheet)
        self.assertIn("dismissEnabled = dismissEnabled", sheet)

    def test_real_sidebar_regression_covers_all_four_exit_paths(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ConversationPaneManagementActivityTest.kt").read_text()
        body = test.split("fun testConversationDeleteHasNoCrossAndKeepsCancelBackOutsideAndConfirm()", 1)[1].split("fun testManageSelects", 1)[0]
        for token in ('longClick()', 'R.string.action_delete', '"cancel", "back", "outside", "confirm"', 'assertDoesNotExist()', 'KEYCODE_BACK', 'exportBackup()', 'it.id == survivor.id', 'fixture.opens.get()'):
            self.assertIn(token, body)

if __name__ == "__main__":
    unittest.main()
