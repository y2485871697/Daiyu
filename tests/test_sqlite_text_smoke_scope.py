"""Exact SQLite chunk-read smoke tooling, inventory and real receipt selection."""
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from test_record_tool_tests_scope import recorder
import test_speech_platform_discovery as discovery

CLASS = "app.daiyu.android.storage.SQLiteTextReadDatabaseTest"
SOURCE = Path("app/src/androidTest/kotlin") / (CLASS.replace(".", "/") + ".kt")
MODULES = (
    "test_large_history_storage_contract", "test_checkpoint_encoding_hotpath_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_sqlite_text_smoke_scope", "test_diagnostic_smoke_source",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


class SQLiteTextSmokeScopeTest(unittest.TestCase):
    def test_only_exact_smoke_scope_runs_storage_contracts_and_guards(self):
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=CLASS))
        self.assertEqual(CLASS, recorder.SQLITE_TEXT_PLATFORM_CLASS)
        self.assertEqual(MODULES, recorder.SQLITE_TEXT_MODULES)
        self.assertEqual("sqlite-text-targeted", scope)
        self.assertEqual(["python3", "-B", "-m", "unittest", *MODULES, "-v"], command)
        for raw in ("", CLASS + "," + CLASS, CLASS + ",app.daiyu.android.OtherTest", " " + CLASS, CLASS + "Extra"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=raw))[1])
        for mode in ("", "candidate", "build", "daily", "compile", "release"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=CLASS))[1])

    def fixture(self, root):
        helper = discovery.SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        path = root / SOURCE
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package app.daiyu.android.storage\nclass SQLiteTextReadDatabaseTest : TestCase() {\n"
                        + "".join(" fun testCase" + str(i) + "() {}\n" for i in range(5)) + "}\n")
        return helper, path

    def test_exact_source_is_discovered_once_and_other_shards_stay_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, _ = self.fixture(root)
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=CLASS)
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / "smoke/selection.json").read_text())
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({CLASS: 5}, inventory["all_classes"])
                self.assertEqual(5 if shard == 0 else 0, selection["expected_tests"])
                self.assertEqual("planned" if shard == 0 else "filtered-empty-shard", selection["status"])
                selected.extend(selection["selected_classes"])
            self.assertEqual([CLASS], selected)

    def test_ambiguous_or_missing_requested_source_stays_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, path = self.fixture(root)
            with path.open("a") as output:
                output.write("class OtherTest : TestCase() {}\n")
            result = helper.select(root, classes=CLASS)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            path.unlink()
            self.assertNotEqual(0, helper.select(root, classes=CLASS).returncode)

    def test_unknown_combined_duplicate_and_other_modes_keep_inventory_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, _ = self.fixture(root)
            for raw in (CLASS + "," + CLASS, CLASS + ",app.daiyu.android.OtherTest", CLASS + "Extra"):
                result = helper.select(root, classes=raw)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            self.assertNotEqual(0, helper.select(root, classes=CLASS, mode="candidate").returncode)

    def test_receipt_preserves_actual_failure_identity_command_and_pythonpath(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="c" * 40, GITHUB_RUN_ID="17", GITHUB_RUN_ATTEMPT="2",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=CLASS)
                with patch.dict(os.environ, env, clear=True), patch.object(
                        recorder.subprocess, "call", return_value=9) as call, patch.object(
                        recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("sqlite-text-targeted", receipt["scope"])
                    self.assertEqual(9, receipt["exit_code"])
                    self.assertEqual(dict(sha="c" * 40, run_id=17, attempt=2), receipt["identity"])
                    self.assertEqual(call.call_args.args[0], receipt["command"])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)
