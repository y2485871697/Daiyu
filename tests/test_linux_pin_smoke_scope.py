"""Exact Linux recovery CHECK selection preserves the default full gate."""
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from test_record_tool_tests_scope import recorder


class LinuxPinSmokeScopeTest(unittest.TestCase):
    def test_exact_classes_run_linux_contracts_and_receipt_guards(self):
        classes = ("app.daiyu.android.terminal.LinuxLeaseRecoveryDeviceTest",
                   "app.daiyu.android.terminal.TerminalEnvironmentHostDeviceTest")
        self.assertEqual(classes, recorder.LINUX_PIN_PLATFORM_CLASSES)
        required = {"test_terminal_geometry_contract", "test_terminal_reference_contract",
                    "test_terminal_sheet_style_contract", "test_chroot_workspace_command_contract",
                    "test_linux_status_pin_contract", "test_record_tool_tests_scope",
                    "test_acceptance_tool_receipt", "test_linux_pin_smoke_scope",
                    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope"}
        self.assertEqual(required, set(recorder.LINUX_PIN_MODULES))
        self.assertEqual(len(required), len(recorder.LINUX_PIN_MODULES))
        for order in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(
                SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(order)))
            self.assertEqual("linux-pins-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.LINUX_PIN_MODULES, "-v"], command)
            self.assertNotIn("discover", command)

    def test_partial_extended_duplicate_and_non_smoke_scopes_keep_full_gate(self):
        classes = recorder.LINUX_PIN_PLATFORM_CLASSES
        raw = ",".join(classes)
        for value in (classes[0], classes[1], raw + "," + classes[0],
                      raw + ",app.daiyu.android.OtherTest", " " + raw, "", "unknown"):
            self.assertEqual("full", recorder.command_for("python3", dict(
                SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        for mode in ("compile", "candidate", "daily", "release", "build", ""):
            self.assertEqual("full", recorder.command_for("python3", dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def test_targeted_receipt_keeps_actual_failure_and_check_identity(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="c" * 40, GITHUB_RUN_ID="27", GITHUB_RUN_ATTEMPT="1",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(recorder.LINUX_PIN_PLATFORM_CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=7) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(7, recorder.record("python3"))
                    result = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("linux-pins-targeted", result["scope"])
                    self.assertEqual(7, result["exit_code"])
                    self.assertEqual(call.call_args.args[0], result["command"])
                    self.assertEqual(dict(sha="c" * 40, run_id=27, attempt=1), result["identity"])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(previous)


class LinuxPinInventoryScopeTest(unittest.TestCase):
    def fixture(self, root):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        for name in recorder.LINUX_PIN_PLATFORM_CLASSES:
            package, _, short = name.rpartition(".")
            path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + package + "\nclass " + short + " : TestCase() {\n" +
                            "".join(" fun testCase" + str(i) + "() {}\n" for i in range(3)) + "}\n")
        return helper

    def test_exact_linux_inventory_covers_both_classes_and_detects_new_methods(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            classes = recorder.LINUX_PIN_PLATFORM_CLASSES
            for expected in (6, 7):
                selected = []
                total = 0
                for shard in range(4):
                    result = helper.select(root, shard, classes=",".join(reversed(classes)))
                    self.assertEqual(0, result.returncode, result.stderr)
                    selection = json.loads((root / "smoke/selection.json").read_text())
                    inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                    self.assertEqual("targeted", inventory["discovery_scope"])
                    self.assertEqual({classes[0]: expected - 3, classes[1]: 3}, inventory["all_classes"])
                    selected.extend(selection["selected_classes"])
                    total += selection["expected_tests"]
                    self.assertEqual("planned" if selection["selected_classes"] else "filtered-empty-shard", selection["status"])
                self.assertCountEqual(classes, selected)
                self.assertEqual(expected, total)
                if expected == 6:
                    path = root / "app/src/androidTest/kotlin" / (classes[0].replace(".", "/") + ".kt")
                    path.write_text(path.read_text().replace(" fun testCase0()", " fun testNewCase() {}\n fun testCase0()", 1))

    def test_requested_missing_or_ambiguous_source_still_fails_closed(self):
        for ambiguous in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper = self.fixture(root)
                classes = recorder.LINUX_PIN_PLATFORM_CLASSES
                path = root / "app/src/androidTest/kotlin" / (classes[0].replace(".", "/") + ".kt")
                if ambiguous:
                    path.write_text(path.read_text() + "class AnotherTest : TestCase() {}\n")
                else:
                    path.unlink()
                result = helper.select(root, classes=",".join(classes))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Ambiguous instrumentation test declaration" if ambiguous else "Unknown instrumentation test class", result.stderr)
                self.assertFalse((root / "smoke/selection.json").exists())

    def test_nonexact_linux_scope_does_not_hide_unrelated_ambiguity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            classes = recorder.LINUX_PIN_PLATFORM_CLASSES
            raw = ",".join(classes)
            for value in (classes[0], raw + "," + classes[0], raw + ",app.daiyu.android.OtherTest"):
                result = helper.select(root, classes=value)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
