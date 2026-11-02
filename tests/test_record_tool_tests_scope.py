import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("speech_test_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class RecordToolTestsScopeTest(unittest.TestCase):
    def test_default_keeps_full_command_and_receipt_contract(self):
        command, scope = recorder.command_for("python3", {})
        self.assertEqual(command, ["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"])
        self.assertEqual(scope, "full")

    def test_exact_speech_platform_scope_runs_only_explicit_modules(self):
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=recorder.SPEECH_PLATFORM_CLASS))
        self.assertEqual(command, ["python3", "-B", "-m", "unittest", *recorder.SPEECH_MODULES, "-v"])
        self.assertEqual(scope, "speech-targeted")
        self.assertEqual(len(recorder.SPEECH_MODULES), 5)

    def test_exact_menu_scope_runs_all_menu_contracts_and_selector_checks(self):
        for classes in (recorder.MENU_PLATFORM_CLASSES, tuple(reversed(recorder.MENU_PLATFORM_CLASSES))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
            self.assertEqual(command, ["python3", "-B", "-m", "unittest", *recorder.MENU_MODULES, "-v"])
            self.assertEqual(scope, "menu-targeted")
        self.assertEqual(len(recorder.MENU_PLATFORM_CLASSES), 6)
        self.assertEqual(len(recorder.MENU_MODULES), 9)

    def test_partial_extended_duplicate_and_other_mode_menu_scopes_keep_full_contracts(self):
        menu = ",".join(recorder.MENU_PLATFORM_CLASSES)
        for classes in (",".join(recorder.MENU_PLATFORM_CLASSES[:-1]), menu + ",app.daiyu.android.OtherTest",
                        menu + "," + recorder.MENU_PLATFORM_CLASSES[0], menu + "," + recorder.SPEECH_PLATFORM_CLASS):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))[1])
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=menu))[1])

    def test_other_modes_keep_full_contracts(self):
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=recorder.SPEECH_PLATFORM_CLASS))[1])

    def test_unknown_or_combined_classes_do_not_reduce_coverage(self):
        for classes in ("", "unknown", recorder.SPEECH_PLATFORM_CLASS + ",app.daiyu.android.OtherTest", "$(echo unsafe)"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))[1])

    def test_targeted_receipt_records_actual_failure_and_command(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="a" * 40, GITHUB_RUN_ID="12", GITHUB_RUN_ATTEMPT="1",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=recorder.SPEECH_PLATFORM_CLASS)
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=7) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(7, recorder.record("python3"))
                    result = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("speech-targeted", result["scope"])
                    self.assertEqual(7, result["exit_code"])
                    self.assertEqual(call.call_args.args[0], result["command"])
                    self.assertEqual(12, result["identity"]["run_id"])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)

    def test_menu_receipt_keeps_actual_failure_identity_command_and_pythonpath(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="b" * 40, GITHUB_RUN_ID="13", GITHUB_RUN_ATTEMPT="2",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(recorder.MENU_PLATFORM_CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=9) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record("python3"))
                    result = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("menu-targeted", result["scope"])
                    self.assertEqual(9, result["exit_code"])
                    self.assertEqual(call.call_args.args[0], result["command"])
                    self.assertEqual(dict(sha="b" * 40, run_id=13, attempt=2), result["identity"])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)

    def test_failed_launch_removes_stale_success_without_replacing_it(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                receipt = Path("tool-evidence/receipt.json")
                receipt.parent.mkdir()
                receipt.write_text('{"exit_code":0}')
                env = dict(GITHUB_SHA="a" * 40, GITHUB_RUN_ID="12", GITHUB_RUN_ATTEMPT="1")
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", side_effect=OSError("not started")):
                    with self.assertRaises(OSError):
                        recorder.record("python3")
                self.assertFalse(receipt.exists())
            finally:
                os.chdir(old)
