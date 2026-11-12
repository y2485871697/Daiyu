"""Exact selection and receipt contract for terminal screen dual-API smoke."""
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from test_record_tool_tests_scope import recorder


class TerminalScreenSmokeScopeTest(unittest.TestCase):
    def test_exact_scope_runs_only_reviewed_terminal_and_selection_contracts(self):
        target = recorder.TERMINAL_SCREEN_PLATFORM_CLASS
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=target))
        self.assertEqual("terminal-screen-targeted", scope)
        self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.TERMINAL_SCREEN_MODULES, "-v"], command)
        self.assertEqual(len(recorder.TERMINAL_SCREEN_MODULES), len(set(recorder.TERMINAL_SCREEN_MODULES)))
        self.assertIn("test_terminal_output_cloud_contract", recorder.TERMINAL_SCREEN_MODULES)
        self.assertIn("test_record_tool_tests_scope", recorder.TERMINAL_SCREEN_MODULES)
        self.assertIn("test_acceptance_tool_receipt", recorder.TERMINAL_SCREEN_MODULES)

    def test_near_matches_and_non_smoke_modes_keep_full_contracts(self):
        target = recorder.TERMINAL_SCREEN_PLATFORM_CLASS
        for classes in ("", target + ",app.daiyu.android.OtherTest", target + "," + target, " " + target, target + " ", "unknown"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))[1])
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=target))[1])

    def test_receipt_preserves_exact_identity_command_and_real_failure(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                target = recorder.TERMINAL_SCREEN_PLATFORM_CLASS
                env = dict(GITHUB_SHA="e" * 40, GITHUB_RUN_ID="29", GITHUB_RUN_ATTEMPT="1",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=target)
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=7) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(7, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("terminal-screen-targeted", receipt["scope"])
                    self.assertEqual(7, receipt["exit_code"])
                    self.assertEqual(dict(sha="e" * 40, run_id=29, attempt=1), receipt["identity"])
                    self.assertEqual(call.call_args.args[0], receipt["command"])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)
