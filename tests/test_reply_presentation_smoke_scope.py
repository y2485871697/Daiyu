"""Exact reply playback smoke selection and raw failure receipt guards."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "reply_presentation_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)

CLASSES = (
    "app.daiyu.android.RunControlActivityTest",
    "app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningTypingTest",
)
MODULES = (
    "test_chat_submission_preview_contract", "test_composer_surface_color_contract",
    "test_compression_send_gate_contract", "test_delegation_batch_and_send_contract",
    "test_run_controls_contract", "test_markdown_typing_contract",
    "test_reasoning_typewriter_contract", "test_reasoning_workflow_contract",
    "test_reasoning_stream_cost_contract", "test_workflow_render_isolation_contract",
    "test_workflow_narration_contract", "test_reported_reasoning_markdown_contract",
    "test_reply_presentation_smoke_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)
SCOPE = "reply-presentation-targeted"


def _environment(**overrides):
    env = dict(GITHUB_SHA="b" * 40, EXPECTED_SHA="a" * 40,
               GITHUB_RUN_ID="43", GITHUB_RUN_ATTEMPT="2", SMOKE_MODE="smoke",
               SMOKE_TEST_CLASSES=",".join(CLASSES))
    env.update(overrides)
    return env


class ReplyPresentationSmokeScopeTest(unittest.TestCase):
    def test_exact_ordered_pair_runs_only_explicit_contracts_and_source(self):
        self.assertEqual(CLASSES, recorder.REPLY_PRESENTATION_PLATFORM_CLASSES)
        self.assertEqual(MODULES, recorder.REPLY_PRESENTATION_MODULES)
        self.assertEqual(len(MODULES), len(set(MODULES)))
        for python in ("python", "python3"):
            with self.subTest(python=python):
                self.assertEqual(
                    ([python, "-B", "-m", "unittest", *MODULES, "-v"], SCOPE),
                    recorder.command_for(python, _environment()))

    def test_near_misses_and_non_smoke_keep_full_fallback(self):
        raw = ",".join(CLASSES)
        invalid = ("", ",".join(reversed(CLASSES)), CLASSES[0], CLASSES[1],
                   raw + ",app.daiyu.android.OtherTest", raw + "," + CLASSES[0],
                   raw + "," + CLASSES[1], CLASSES[0] + "," + CLASSES[0],
                   " " + raw, raw + " ", "," + raw, raw + ",",
                   raw.replace(",", ", ", 1), raw + "Extra")
        environments = [_environment(SMOKE_TEST_CLASSES=value) for value in invalid]
        environments += [_environment(SMOKE_MODE=mode) for mode in
                         ("", "compile", "candidate", "daily", "release", "checks", "build")]
        environments += [{"SMOKE_TEST_CLASSES": raw}, {"SMOKE_MODE": "smoke"}, {}]
        for python in ("python", "python3"):
            full = ([python, "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full")
            for env in environments:
                with self.subTest(python=python, env=env):
                    self.assertEqual(full, recorder.command_for(python, env))

    def test_refresh_exact_pair_has_an_isolated_concurrency_group(self):
        workflow = (ROOT / ".github/workflows/test-refresh-regression.yml").read_text()
        group, = (line for line in workflow.splitlines() if line.startswith("  group: "))
        suffix = ("${{ inputs.smoke_classes == '" + ",".join(CLASSES) +
                  "' && '-reply-presentation' || '' }}")
        self.assertEqual(1, group.count(suffix))
        self.assertTrue(group.endswith(suffix + "${{ inputs.test_method != '' && format('-diagnostic-{0}-{1}-{2}', inputs.smoke_classes, inputs.test_method, inputs.smoke_api) || '' }}"))
        self.assertTrue(group.startswith("  group: daiyu-test-refresh-${{ github.ref }}"))
        self.assertIn("\n  cancel-in-progress: false\n", workflow)

    def test_failure_receipt_keeps_exit_identity_and_raw_digest(self):
        raw = b"reply presentation fixture FAIL\r\nraw stderr\x00\n"
        expected = ["python3", "-B", "-m", "unittest", *MODULES, "-v"]
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                console = io.BytesIO()

                def child(command, stdout, stderr, env=None, cwd=None):
                    self.assertEqual(expected, command)
                    self.assertEqual(subprocess.STDOUT, stderr)
                    self.assertEqual(ROOT, cwd)
                    self.assertEqual(str(ROOT / "tests") + os.pathsep, env["PYTHONPATH"])
                    stdout.write(raw)
                    return 7

                with patch.dict(os.environ, _environment(), clear=True), \
                        patch.object(recorder.subprocess, "check_output", return_value="a" * 40 + "\n"), \
                        patch.object(recorder.subprocess, "call", side_effect=child) as call, \
                        patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=console)):
                    self.assertEqual(7, recorder.record("python3"))
                receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                self.assertEqual(dict(identity=dict(sha="a" * 40, run_id=43, attempt=2),
                                      workflow_sha="b" * 40, validation_tools_sha="b" * 40,
                                      command=expected, exit_code=7, scope=SCOPE), receipt)
                call.assert_called_once()
                self.assertEqual(expected, call.call_args.args[0])
                log = Path("tool-evidence/unittest.log").read_bytes()
                self.assertEqual(raw, log)
                self.assertEqual(raw, console.getvalue())
                self.assertEqual(hashlib.sha256(raw).hexdigest(), hashlib.sha256(log).hexdigest())
            finally:
                os.chdir(previous)
