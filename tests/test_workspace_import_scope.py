import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("workspace_import_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class WorkspaceImportScopeTest(unittest.TestCase):
    def test_exact_import_classes_use_reviewed_workspace_contracts(self):
        expected = {"app.daiyu.android.workspace.PrivateWorkspaceFilesTest", "app.daiyu.android.PrivateWorkspacePanelTest"}
        self.assertEqual(expected, set(recorder.WORKSPACE_IMPORT_PLATFORM_CLASSES))
        for classes in (recorder.WORKSPACE_IMPORT_PLATFORM_CLASSES, tuple(reversed(recorder.WORKSPACE_IMPORT_PLATFORM_CLASSES))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
            self.assertEqual("workspace-import-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.WORKSPACE_IMPORT_MODULES, "-v"], command)
        self.assertIn("test_workspace_files_entry_contract", recorder.WORKSPACE_IMPORT_MODULES)
        self.assertIn("test_workspace_entry_mutations_contract", recorder.WORKSPACE_IMPORT_MODULES)
        self.assertIn("test_workspace_import_scope", recorder.WORKSPACE_IMPORT_MODULES)
        for module in recorder.WORKSPACE_IMPORT_MODULES:
            self.assertTrue((ROOT / "tests" / (module.split(".")[0] + ".py")).is_file(), module)

    def test_inexact_scope_and_other_modes_keep_full_coverage(self):
        classes = recorder.WORKSPACE_IMPORT_PLATFORM_CLASSES
        exact = ",".join(classes)
        self.assertEqual(len(recorder.WORKSPACE_IMPORT_MODULES), len(set(recorder.WORKSPACE_IMPORT_MODULES)))
        for value in (*classes, exact + "," + classes[0], exact + "," + classes[1],
                      exact + ",app.daiyu.android.OtherTest", exact + ",app.daiyu.android.WorkspaceEntryActionsActivityTest",
                      exact + ",", " " + exact):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        for mode in ("", "compile", "candidate", "daily", "release", "build"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=exact))[1])

    def test_existing_workspace_entry_scope_is_unchanged(self):
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke",
            SMOKE_TEST_CLASSES=",".join(recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES)))
        self.assertEqual("workspace-entry-targeted", scope)
        self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.WORKSPACE_ENTRY_MODULES, "-v"], command)
