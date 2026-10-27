"""Folder deletion keeps its production callback and a content-sized confirmation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
COMPONENTS = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/components"


class FolderDeleteConfirmationContractTest(unittest.TestCase):
    def confirmation(self):
        return (COMPONENTS / "FolderDeleteConfirmation.kt").read_text()

    def test_sidebar_uses_the_scoped_confirmation_and_original_callbacks(self):
        pane = (COMPONENTS / "ConversationSidePaneScaffold.kt").read_text()
        deletion = pane.split("folderToDelete?.let { folder ->", 1)[1].split("\n    }", 1)[0]
        self.assertIn("FolderDeleteConfirmation(", deletion)
        self.assertIn("onDeleteFolder(folder.id); folderToDelete = null", deletion)
        self.assertIn("onDismiss = { folderToDelete = null }", deletion)
        self.assertNotIn("DaiyuConfirmDialog", deletion)

    def test_folder_sheet_hides_close_without_imposing_a_fixed_height(self):
        dialog = self.confirmation()
        self.assertIn("ChatSelectionSheet(", dialog)
        self.assertIn("showCloseButton = false", dialog)
        self.assertNotIn("height =", dialog)
        self.assertNotIn("fillMaxHeight", dialog)
        self.assertIn("weight(1f, fill = false)", dialog)
        self.assertIn("verticalScroll(rememberScrollState())", dialog)
        self.assertIn("drawer_delete_folder_title", dialog)
        self.assertIn("drawer_delete_folder_message", dialog)

    def test_body_is_readable_left_aligned_and_spaced_from_actions(self):
        dialog = self.confirmation()
        self.assertIn("fontSize = 15.sp", dialog)
        self.assertIn("lineHeight = 23.sp", dialog)
        self.assertIn("textAlign = TextAlign.Start", dialog)
        self.assertIn("color = colors.muted", dialog)
        self.assertIn("padding(top = 6.dp, bottom = 20.dp)", dialog)
        self.assertEqual(2, dialog.count("heightIn(min = 48.dp)"))
        self.assertIn("danger = true", dialog)
        self.assertIn("onClick = onConfirm", dialog)
        self.assertIn("onClick = onDismiss", dialog)

    def test_android_regression_covers_geometry_scaling_and_all_dismissal_paths(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/FolderDeleteConfirmationActivityTest.kt").read_text()
        for token in ("testReadableContentAndContentSizedHeight", "listOf(false, true)",
                      "listOf(1f, 1.3f, 1.8f)", "TextLayoutResult", "15.sp", "23.sp",
                      '"cancel", "back", "outside", "confirm"', "KEYCODE_BACK",
                      'fixture.opens.get()', "exportBackup()"):
            self.assertIn(token, test)


if __name__ == "__main__":
    unittest.main()
