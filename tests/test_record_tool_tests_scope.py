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
