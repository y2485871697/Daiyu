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
