import importlib.util
from pathlib import Path
import unittest
import json
import tempfile

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


class WorkspaceDeleteInventoryTest(unittest.TestCase):
    def fixture(self, root):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        paths = []
        for name in recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES:
            package, simple = name.rsplit(".", 1)
            path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + package + "\nclass " + simple + " : TestCase() {\n fun testOriginalName() {}\n}\n")
            paths.append(path)
        return helper, paths

    def test_exact_inventory_skips_unrelated_ambiguous_sources_and_keeps_empty_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, _ = self.fixture(root)
            selected = []
            total = 0
            classes = ",".join(recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES)
            for shard in range(4):
                result = helper.select(root, shard, classes=classes)
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({name: 1 for name in recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES}, inventory["all_classes"])
                selected.extend(selection["selected_classes"])
                total += selection["expected_tests"]
                self.assertEqual("planned" if selection["selected_classes"] else "filtered-empty-shard", selection["status"])
            self.assertCountEqual(recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES, selected)
            self.assertEqual(2, total)

    def test_missing_requested_source_still_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, paths = self.fixture(root)
            paths[0].unlink()
            result = helper.select(root, classes=",".join(recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Unknown instrumentation test class", result.stderr)
            self.assertFalse((root / "smoke/selection.json").exists())

    def test_ambiguous_requested_source_still_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, paths = self.fixture(root)
            with paths[0].open("a") as stream:
                stream.write("class ExtraTest : TestCase() {}\n")
            result = helper.select(root, classes=",".join(recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            self.assertFalse((root / "smoke/selection.json").exists())

    def test_inexact_filters_do_not_skip_ambiguous_unrelated_sources(self):
        classes = recorder.WORKSPACE_ENTRY_PLATFORM_CLASSES
        exact = ",".join(classes)
        for value, mode in ((exact + "," + classes[0], "smoke"),
                            (exact + "," + classes[1], "smoke"),
                            (exact + ",app.daiyu.android.OtherTest", "smoke"),
                            (exact, "candidate")):
            with self.subTest(value=value, mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper, _ = self.fixture(root)
                result = helper.select(root, classes=value, mode=mode)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((root / "smoke/selection.json").exists())
