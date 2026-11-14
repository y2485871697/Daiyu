"""Linux settings removal uses an exact cloud scope without weakening defaults."""
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from test_record_tool_tests_scope import recorder

CLASS = "app.daiyu.android.ui.screens.settings.LinuxSettingsScreenTest"
SOURCE = Path("app/src/androidTest/kotlin") / (CLASS.replace(".", "/") + ".kt")


class LinuxSettingsSmokeScopeTest(unittest.TestCase):
    def test_only_the_exact_smoke_class_uses_the_linux_contracts(self):
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=CLASS))
        self.assertEqual("linux-settings-targeted", scope)
        self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.LINUX_SETTINGS_MODULES, "-v"], command)
        self.assertIn("test_linux_install_button_layout_contract", command)
        self.assertIn("test_linux_install_choice_contract", command)
        self.assertIn("test_linux_files_style_contract", command)
        self.assertNotIn("discover", command)
        for raw in ("", CLASS + "," + CLASS, CLASS + ",app.daiyu.android.OtherTest", " " + CLASS):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=raw))[1])
        for mode in ("compile", "candidate", "daily", "release", "build", ""):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=CLASS))[1])

    def test_receipt_preserves_failure_and_exact_cloud_identity(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="d" * 40, GITHUB_RUN_ID="42", GITHUB_RUN_ATTEMPT="1",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=CLASS)
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=9) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("linux-settings-targeted", receipt["scope"])
                    self.assertEqual(9, receipt["exit_code"])
                    self.assertEqual(call.call_args.args[0], receipt["command"])
                    self.assertEqual(dict(sha="d" * 40, run_id=42, attempt=1), receipt["identity"])
            finally:
                os.chdir(previous)

    def fixture(self, root):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        path = root / SOURCE
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package app.daiyu.android.ui.screens.settings\n"
                        "class LinuxSettingsScreenTest : TestCase() {\n fun testBasic() {}\n fun testFiles() {}\n}\n")
        return helper, path

    def test_all_shards_cover_the_whole_class_and_discover_new_methods(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, path = self.fixture(root)
            for expected in (2, 3):
                selected, total = [], 0
                for shard in range(4):
                    result = helper.select(root, shard, classes=CLASS)
                    self.assertEqual(0, result.returncode, result.stderr)
                    inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                    selection = json.loads((root / "smoke/selection.json").read_text())
                    self.assertEqual("targeted", inventory["discovery_scope"])
                    self.assertEqual({CLASS: expected}, inventory["all_classes"])
                    selected.extend(selection["selected_classes"])
                    total += selection["expected_tests"]
                    self.assertEqual("planned" if selection["selected_classes"] else "filtered-empty-shard", selection["status"])
                self.assertEqual([CLASS], selected)
                self.assertEqual(expected, total)
                path.write_text(path.read_text().replace(" fun testBasic()", " fun testNew() {}\n fun testBasic()", 1))

    def test_missing_or_ambiguous_target_is_rejected(self):
        for ambiguous in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper, path = self.fixture(root)
                if ambiguous:
                    path.write_text(path.read_text() + "class OtherTest : TestCase() {}\n")
                else:
                    path.unlink()
                result = helper.select(root, classes=CLASS)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Ambiguous instrumentation test declaration" if ambiguous else "No instrumentation tests found", result.stderr)
                self.assertFalse((root / "smoke/selection.json").exists())
