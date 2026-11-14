import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("workspace_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class WorkspaceDeleteScopeTest(unittest.TestCase):
    def test_exact_workspace_classes_use_only_reviewed_contracts(self):
        for classes in (recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES, tuple(reversed(recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
            self.assertEqual("workspace-entry-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.WORKSPACE_ENTRY_MODULES, "-v"], command)
        self.assertEqual({"app.daiyu.android.WorkspaceEntryActionsActivityTest", "app.daiyu.android.PrivateWorkspacePanelTest"},
                         set(recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES))
        self.assertIn("test_linux_files_style_contract", recorder.WORKSPACE_ENTRY_MODULES)
        self.assertIn("test_workspace_entry_mutations_contract", recorder.WORKSPACE_ENTRY_MODULES)

    def test_partial_extra_duplicate_and_other_modes_keep_full_coverage(self):
        classes = recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES
        exact = ",".join(classes)
        for value in (classes[0], exact + "," + classes[0], exact + ",app.daiyu.android.OtherTest", " " + exact):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        for mode in ("", "compile", "candidate", "daily", "release", "build"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=exact))[1])
