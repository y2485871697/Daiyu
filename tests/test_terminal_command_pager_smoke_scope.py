"""Exact selection and receipt contract for terminal command pager dual-API smoke."""
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from test_record_tool_tests_scope import recorder


class TerminalCommandPagerSmokeScopeTest(unittest.TestCase):
    def test_exact_scope_runs_only_reviewed_terminal_and_selection_contracts(self):
        target = recorder.TERMINAL_COMMAND_PAGER_PLATFORM_CLASS
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=target))
        self.assertEqual("terminal-command-pager-targeted", scope)
        self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.TERMINAL_COMMAND_PAGER_MODULES, "-v"], command)
        self.assertEqual(len(recorder.TERMINAL_COMMAND_PAGER_MODULES), len(set(recorder.TERMINAL_COMMAND_PAGER_MODULES)))
        self.assertIn("test_chroot_workspace_command_contract", recorder.TERMINAL_COMMAND_PAGER_MODULES)
        self.assertIn("test_record_tool_tests_scope", recorder.TERMINAL_COMMAND_PAGER_MODULES)
        self.assertIn("test_acceptance_tool_receipt", recorder.TERMINAL_COMMAND_PAGER_MODULES)

    def test_near_matches_and_non_smoke_modes_keep_full_contracts(self):
        target = recorder.TERMINAL_COMMAND_PAGER_PLATFORM_CLASS
        for classes in ("", target + ",app.daiyu.android.OtherTest", target + "," + target, " " + target, target + " ", "unknown"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))[1])
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=target))[1])

    def test_receipt_preserves_exact_identity_command_and_real_failure(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                target = recorder.TERMINAL_COMMAND_PAGER_PLATFORM_CLASS
                env = dict(GITHUB_SHA="e" * 40, GITHUB_RUN_ID="29", GITHUB_RUN_ATTEMPT="1",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=target)
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=7) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(7, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("terminal-command-pager-targeted", receipt["scope"])
                    self.assertEqual(7, receipt["exit_code"])
                    self.assertEqual(dict(sha="e" * 40, run_id=29, attempt=1), receipt["identity"])
                    self.assertEqual(call.call_args.args[0], receipt["command"])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)


class TerminalCommandPagerInventoryTest(unittest.TestCase):
    def fixture(self, root):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        target = recorder.TERMINAL_COMMAND_PAGER_PLATFORM_CLASS
        path = root / "app/src/androidTest/kotlin" / (target.replace(".", "/") + ".kt")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package app.daiyu.android.terminal\nclass TerminalCommandPagerTest : TestCase() {\n fun testPager() {}\n}\n")
        return helper, path

    def select(self, helper, root, shard=0, count=1):
        with patch.dict(os.environ, {"SMOKE_API": "all"}):
            return helper.select(root, shard, classes=recorder.TERMINAL_COMMAND_PAGER_PLATFORM_CLASS, count=count)

    def test_exact_inventory_tracks_only_target_and_new_methods(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, path = self.fixture(root)
            for expected in (1, 2):
                result = self.select(helper, root)
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / "smoke/selection.json").read_text())
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({recorder.TERMINAL_COMMAND_PAGER_PLATFORM_CLASS: expected}, inventory["all_classes"])
                self.assertEqual(expected, selection["expected_tests"])
                path.write_text(path.read_text().replace(" fun testPager()", " fun testAdded() {}\n fun testPager()", 1))

    def test_missing_or_ambiguous_target_is_rejected(self):
        for ambiguous in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper, path = self.fixture(root)
                if ambiguous:
                    path.write_text(path.read_text() + "class AnotherTest : TestCase() {}\n")
                else:
                    path.unlink()
                result = self.select(helper, root)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((root / "smoke/selection.json").exists())

    def test_multiple_shards_are_rejected_and_dual_api_matrix_is_exact(self):
        import re
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, _ = self.fixture(root)
            self.assertNotEqual(0, self.select(helper, root, count=4).returncode)
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/build.yml").read_text()
        target = recorder.TERMINAL_COMMAND_PAGER_PLATFORM_CLASS
        match = re.search(re.escape("inputs.smoke_classes == '" + target + "' && '") + r"([^']+)", workflow)
        self.assertIsNotNone(match)
        rows = json.loads(match.group(1))
        self.assertEqual([("26", 0), ("37.2", 0)], [(row["api"], row["shard"]) for row in rows])
        self.assertIn("SMOKE_SHARD_COUNT: ${{ (inputs.mode == 'smoke' && inputs.smoke_api == 'all' && inputs.smoke_classes == '" + target + "') && '1'", workflow)

    def test_command_policy_is_shared_without_changing_interactive_setup(self):
        root = Path(__file__).resolve().parents[1]
        main = root / "app/src/main/kotlin/app/daiyu/android/terminal"
        backend = (main / "TerminalBackend.kt").read_text()
        self.assertIn('COMMAND_PAGER_SETUP = "export PAGER=cat GIT_PAGER=cat SYSTEMD_PAGER=cat; "', backend)
        self.assertIn('command != null -> listOf("-c", COMMAND_PAGER_SETUP +', backend)
        self.assertIn('prefix+COMMAND_PAGER_SETUP+command+', (main / "TerminalRegistry.kt").read_text())
        self.assertNotIn('PAGER', (main / "TerminalSessionWorkspace.kt").read_text())
        self.assertNotIn('COMMAND_PAGER_SETUP', (main / "TerminalEnvironmentHost.kt").read_text())
