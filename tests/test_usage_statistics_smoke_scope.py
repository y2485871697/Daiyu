"""Exact selector and real receipt semantics for usage statistics dual-API smoke."""
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from test_record_tool_tests_scope import recorder


class UsageStatisticsSmokeScopeTest(unittest.TestCase):
    def test_exact_scope_runs_usage_and_shared_receipt_contracts(self):
        target = "app.daiyu.android.UsagePresentationActivityTest"
        modules = (
            "test_chat_usage_ready_contract", "test_context_usage_wiring_contract",
            "test_usage_tab_retention_contract", "test_usage_heatmap_contract",
            "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
            "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
        )
        self.assertEqual(target, recorder.USAGE_STATS_PLATFORM_CLASS)
        self.assertEqual(modules, recorder.USAGE_STATS_MODULES)
        self.assertEqual(len(modules), len(set(modules)))
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/build.yml").read_text()
        validation = workflow.split("      - name: 验证冻结补检工具\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn("working-directory: .smoke-validation-tools", validation)
        self.assertIn("test_smoke_boot.SourceTests test_usage_statistics_smoke_scope -v", validation)
        for python in ("python", "python3"):
            command, scope = recorder.command_for(python, dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=target))
            self.assertEqual("usage-statistics-targeted", scope)
            self.assertEqual([python, "-B", "-m", "unittest", *modules, "-v"], command)
            self.assertNotIn("discover", command)

    def test_near_matches_and_non_smoke_modes_keep_the_existing_fallback(self):
        target = recorder.USAGE_STATS_PLATFORM_CLASS
        for classes in ("", target + ",app.daiyu.android.OtherTest", target + "," + target,
                        " " + target, target + " ", target + ",", target + "Extra", "unknown"):
            with self.subTest(classes=classes):
                self.assertEqual((["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full"),
                                 recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes)))
        for mode in ("", "build", "compile", "candidate", "daily", "release", "checks"):
            with self.subTest(mode=mode):
                self.assertEqual("full", recorder.command_for("python3", dict(
                    SMOKE_MODE=mode, SMOKE_TEST_CLASSES=target))[1])

    def test_receipt_keeps_exact_identity_command_and_real_nonzero_exit(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="e" * 40, GITHUB_RUN_ID="29", GITHUB_RUN_ATTEMPT="1",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=recorder.USAGE_STATS_PLATFORM_CLASS)
                with patch.dict(os.environ, env, clear=True), patch.object(
                    recorder.subprocess, "call", return_value=7
                ) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(7, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("usage-statistics-targeted", receipt["scope"])
                    self.assertEqual(7, receipt["exit_code"])
                    self.assertEqual(dict(sha="e" * 40, run_id=29, attempt=1), receipt["identity"])
                    self.assertEqual(call.call_args.args[0], receipt["command"])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)
