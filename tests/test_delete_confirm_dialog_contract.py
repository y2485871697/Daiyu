"""The bodyless deletion card is explicit, centered, and preserves production admissions."""
from pathlib import Path
import importlib.util
import json
import tempfile
import unittest
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
def source(path): return (APP / path).read_text()

class DeleteConfirmDialogContractTest(unittest.TestCase):
    def test_card_is_centered_glass_with_no_description_or_close_control(self):
        card = source("ui/components/DaiyuDeleteConfirmDialog.kt")
        for token in ("PreviewDialog(", "align(Alignment.Center)", "widthIn(max = 360.dp)",
                      "RoundedCornerShape(28.dp)", "size(58.dp)", "size(26.dp)",
                      "radius = 30f, saturation = 1.45f", "scrimBlurRadius = 3f",
                      "LocalAppearanceDark.current", "fontSize = 20.sp", "TextAlign.Center",
                      "0xFFBB5358", "0xFFCB6B70", "Color.White", "heightIn(min = 48.dp)",
                      "height(IntrinsicSize.Min)", 'fillMaxHeight().testTag("dialog-cancel")',
                      'fillMaxHeight().testTag("dialog-confirm")',
                      'testTag("delete-confirm-title")', 'testTag("dialog-cancel")', 'testTag("dialog-confirm")'):
            self.assertIn(token, card)
        for token in ("message:", "Text(message", "ChatSelectionSheet", "WindowDialog", "bottomSheet = true", "DaiyuIcons.Close"):
            self.assertNotIn(token, card)
        self.assertIn("onOutsideDismiss = onOutsideDismiss", card)
        self.assertIn("enabled = confirmEnabled", card)
        self.assertNotIn("onConfirm(); onDismiss()", card)

    def test_shared_deletion_branch_precedes_nondelete_rendering(self):
        dialog = source("ui/components/DaiyuDialogs.kt").split("internal fun DaiyuConfirmDialog(", 1)[1]
        deletion = dialog.index("if (deletionAppearance)")
        self.assertLess(deletion, dialog.index("if (preview)"))
        self.assertIn("DaiyuDeleteConfirmDialog(title = title, onConfirm = onConfirm, onDismiss = onDismiss, confirmText = confirmText)", dialog)
        self.assertIn("summary = message", dialog)
        self.assertIn("if (message.isNotBlank()) Text(message", dialog)

    def test_every_preexisting_deletion_surface_uses_the_shared_card(self):
        for path in ("ui/components/FolderDeleteConfirmation.kt", "ui/components/WorkspaceEntryDialogs.kt",
                     "ui/screens/assistants/AssistantPickerDialogs.kt", "ui/screens/chat/ManageChatsScreen.kt",
                     "VoiceLocalRemovalSheet.kt", "ui/SubAgentSettingsScreen.kt", "ui/screens/settings/SkillManagementScreen.kt"):
            self.assertIn("DaiyuDeleteConfirmDialog(", source(path), path)
        self.assertEqual(2, source("ui/SubAgentSettingsScreen.kt").count("DaiyuDeleteConfirmDialog("))
        assistant = source("ui/screens/assistants/AssistantPickerDialogs.kt").split("internal fun AssistantDeleteDialog(", 1)[1].split("\n@Composable", 1)[0]
        self.assertNotIn("AssistantDialogIdentity(", assistant)
        self.assertIn("confirmEnabled = canDelete", assistant)
        self.assertIn("onOutsideDismiss = onOutsideDismiss", assistant)
        skills = source("ui/screens/settings/SkillManagementScreen.kt")
        removal = skills.split("controller.removal?.let", 1)[1].split("\n    }", 1)[0]
        self.assertNotIn("Text(", removal)
        self.assertIn("RetainedSkillRemovalCancel", removal)
        self.assertIn("controller.remove(removal)", removal)
        self.assertIn("confirmEnabled = !controller.busy", removal)

    def test_titles_identify_object_types_and_frozen_batch_counts(self):
        main = source("MainActivity.kt")
        self.assertIn('ConfirmPrompt("删除这个会话？", "", "删除", true,', main)
        self.assertIn('ConfirmPrompt("删除选中的 ${owners.size} 个会话？", "", "删除", true,', main)
        file_dialog = source("ui/components/WorkspaceEntryDialogs.kt")
        self.assertIn('if (directory) "删除这个文件夹？" else "删除这个文件？"', file_dialog)
        self.assertIn('if (enabled()) { stage = "submitted"; onDelete() }', file_dialog)
        models = source("ui/screens/settings/LocalCatalogModelsScreen.kt")
        self.assertIn('"删除这个本地模型？"', models)
        self.assertIn('"删除选中的 ${selections.size} 个本地模型？"', models)
        self.assertIn('deleting === selection && current() && matches(selection)', models)
        provider = source("ui/screens/settings/ProviderManagementRoute.kt")
        self.assertIn('"删除这个提供商？"', provider)
        self.assertIn('"删除选中的 ${selected.size} 个提供商？"', provider)
        self.assertIn('if (confirm.deletionAppearance) "删除" else "确认"', provider)
        self.assertIn('"删除这个服务器？"', source("ui/screens/settings/McpSettingsScreen.kt"))
        self.assertIn('"删除这个参考声音？", "", "删除", true, deletionAppearance = true', source("VoiceSettingsPanel.kt"))

    def test_nondelete_authorizations_and_regeneration_keep_their_original_presentation(self):
        main = source("MainActivity.kt")
        regenerate = main.split("onRegenerate = { id ->", 1)[1].split("}, onSpeak =", 1)[0]
        self.assertNotIn("deletionAppearance", regenerate)
        deletion = main.split("onDeleteTurn = { id ->", 1)[1].split("}, onRetryStep =", 1)[0]
        self.assertIn("deletionAppearance = true", deletion)
        self.assertNotIn("deletionAppearance", source("ui/screens/settings/WorkspaceSettingsScreen.kt"))
        self.assertIn('else DaiyuConfirmDialog("确认本次操作", pending.label, "确认", false,', source("VoiceAccountPanel.kt"))

class DeleteConfirmScopeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("delete_dialog_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
        cls.recorder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.recorder)

    def test_only_complete_unique_list_gets_targeted_tool_scope(self):
        r = self.recorder
        target = r.DELETE_CONFIRM_PLATFORM_CLASSES
        for classes in (target, tuple(reversed(target))):
            cmd, scope = r.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
            self.assertEqual("delete-confirm-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *r.DELETE_CONFIRM_MODULES, "-v"], cmd)
        self.assertEqual(len(target), len(set(target)))
        exact = ",".join(target)
        invalid = [exact + ",app.daiyu.android.OtherTest", exact + "," + target[0], exact + ",", " " + exact]
        invalid += [",".join(target[:i] + target[i+1:]) for i in range(len(target))]
        for classes in invalid:
            self.assertEqual("full", r.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))[1])
        for mode in ("", "compile", "candidate", "daily", "release", "checks"):
            self.assertEqual("full", r.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=exact))[1])

    def test_inventory_discovers_only_requested_sources_and_assigns_each_once(self):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        classes = self.recorder.DELETE_CONFIRM_PLATFORM_CLASSES
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper.fixture(root)
            for name in classes:
                package, simple = name.rsplit(".", 1)
                path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + package + "\nclass " + simple + " : TestCase() {\n fun testExample() {}\n}\n")
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(classes))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({name: 1 for name in classes}, inventory["all_classes"])
                selected.extend(selection["selected_classes"])
            self.assertCountEqual(classes, selected)

if __name__ == "__main__": unittest.main()
