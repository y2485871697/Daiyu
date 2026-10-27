"""Source wiring; real picker interactions remain in the Android regression batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class ConversationFolderPickerContractTest(unittest.TestCase):
    def picker(self):
        path = SRC / "ui/components/ConversationFolderPicker.kt"
        self.assertTrue(path.is_file(), "moving uses a dedicated existing-folder picker")
        return path.read_text()

    def test_move_uses_persisted_folders_and_rechecks_destination_before_writing(self):
        host = (SRC / "MainActivity.kt").read_text()
        edit = host.split("private fun editConversation(id: String, folder: Boolean) {", 1)[1].split(
            "private fun searchConversations()", 1)[0]
        self.assertIn("FolderPrompt(retainedData.folders()", edit)
        self.assertIn("current.folder.takeIf { it.isNotEmpty() }", edit)
        self.assertIn("retainedData.folders().single { it.id == folderId }.name", edit)
        self.assertIn("controller.database.organize(latest.id, destination, latest.pinned)", edit)
        self.assertIn('InputPrompt("重命名", current.title, "名称", "保存"', edit)
        self.assertNotIn('if (folder) "移动到文件夹"', edit)
        self.assertIn("if (selected?.id == latest.id) selected = retainedConversation(id)", edit)
        self.assertIn("updatePane(); refresh()", edit)

    def test_folder_prompt_retains_the_one_shot_host_guard(self):
        host = (SRC / "MainActivity.kt").read_text()
        self.assertIn("is FolderPrompt ->", host)
        render = host.split("is FolderPrompt ->", 1)[1].split("is ChoicePrompt ->", 1)[0]
        self.assertIn("ConversationFolderPicker(current.folders, current.initialFolderId", render)
        self.assertIn("if (valid()) { prompt = null; current.action(folderId) }", render)
        self.assertIn("onDismiss = { if (valid()) prompt = null }", render)
        self.assertIn("fun valid() = activityUsable() && prompt === current", host)

    def test_picker_reuses_selection_rows_and_only_this_sheet_hides_close(self):
        picker = self.picker()
        for token in ("ChatSelectionSheet(", 'title = "移动到文件夹"', "showCloseButton = false",
                      "OptionSelectionItem(", "items(folders, key = { it.id })",
                      "selectableGroup()", "weight(1f, fill = false)",
                      'text = "未分类"', "selectedFolderId = null", "selectedFolderId = folder.id",
                      'text = "取消"', 'text = "保存"', "onClick = onDismiss",
                      "onConfirm(selectedFolderId)", "folders.isEmpty()"):
            self.assertIn(token, picker)
        for token in ("TextField", "AssistantPreviewField", "createFolder", "database", "WindowDialog"):
            self.assertNotIn(token, picker)
        shared = (SRC / "ui/screens/chat/conversation/ChatSelectionSheet.kt").read_text()
        self.assertIn("showCloseButton: Boolean = true", shared)


if __name__ == "__main__":
    unittest.main()
