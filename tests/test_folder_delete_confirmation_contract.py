"""Folder deletion uses the shared compact glass confirmation and unchanged callbacks."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
COMPONENTS = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/components"

class FolderDeleteConfirmationContractTest(unittest.TestCase):
    def test_sidebar_keeps_original_callbacks(self):
        pane = (COMPONENTS / "ConversationSidePaneScaffold.kt").read_text()
        deletion = pane.split("folderToDelete?.let { folder ->", 1)[1].split("\n    }", 1)[0]
        self.assertIn("FolderDeleteConfirmation(", deletion)
        self.assertIn("onDeleteFolder(folder.id); folderToDelete = null", deletion)
        self.assertIn("onDismiss = { folderToDelete = null }", deletion)

    def test_folder_uses_bodyless_shared_card(self):
        dialog = (COMPONENTS / "FolderDeleteConfirmation.kt").read_text()
        for token in ("DaiyuDeleteConfirmDialog(", "drawer_delete_folder_title", "onConfirm = onConfirm", "onDismiss = onDismiss"):
            self.assertIn(token, dialog)
        for token in ("ChatSelectionSheet", "drawer_delete_folder_message", "Text(", "height(", "padding("):
            self.assertNotIn(token, dialog)

    def test_android_regression_preserves_scaling_geometry_and_all_exit_paths(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/FolderDeleteConfirmationActivityTest.kt").read_text()
        for token in ("testReadableContentAndContentSizedHeight", "listOf(false, true)", "listOf(1f, 1.3f, 1.8f)",
                      "TextLayoutResult", "20.sp", "28.sp", "TextAlign.Center", '"delete-confirm-dialog"',
                      '"cancel", "back", "outside", "confirm"', "KEYCODE_BACK", "fixture.opens.get()", "exportBackup()",
                      'node("folder-delete-message").assertDoesNotExist()'):
            self.assertIn(token, test)

if __name__ == "__main__": unittest.main()
