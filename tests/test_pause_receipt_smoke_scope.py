"""Exact pause receipt regression reuses the signed APK without full discovery."""
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

from test_record_tool_tests_scope import recorder
from test_speech_platform_discovery import SpeechPlatformDiscoveryTest


class PauseReceiptSmokeScopeTest(unittest.TestCase):
    def test_exact_scope_has_explicit_business_contracts_and_never_discovers_all_tests(self):
        for classes in (recorder.PAUSE_RECEIPT_PLATFORM_CLASSES,
                        tuple(reversed(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
            self.assertEqual("pause-receipts-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.PAUSE_RECEIPT_MODULES, "-v"], command)
            self.assertNotIn("discover", command)
        self.assertTrue({"test_tool_receipt_pause_contract", "test_tool_receipt_snapshot_contract",
                         "test_reasoning_flow_stability_contract", "test_pause_receipt_smoke_scope"}
                        <= set(recorder.PAUSE_RECEIPT_MODULES))
        raw = ",".join(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES)
        for value in (raw + "," + recorder.PAUSE_RECEIPT_PLATFORM_CLASSES[0],
                      raw + ",app.daiyu.android.OtherTest", ",".join(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES[:-1])):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="release", SMOKE_TEST_CLASSES=raw))[1])

    def fixture(self, root):
        helper = SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        for index, name in enumerate(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES):
            package, _, short = name.rpartition(".")
            path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + package + "\nclass " + short + " : TestCase() {\n" +
                            "".join(" fun testCase" + str(i) + "() {}\n" for i in range(index + 1)) + "}\n")
        return helper

    def test_four_shards_cover_only_four_requested_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            selected = []
            empty = 0
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(reversed(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES)))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({name: i + 1 for i, name in enumerate(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES)}, inventory["all_classes"])
                self.assertEqual(sum(inventory["all_classes"][name] for name in selection["selected_classes"]), selection["expected_tests"])
                selected.extend(selection["selected_classes"])
                empty += selection["status"] == "filtered-empty-shard"
            self.assertCountEqual(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES, selected)
            self.assertEqual(0, empty)

    def test_missing_or_ambiguous_requested_source_still_rejects(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            raw = ",".join(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES)
            path = root / "app/src/androidTest/kotlin" / (recorder.PAUSE_RECEIPT_PLATFORM_CLASSES[0].replace(".", "/") + ".kt")
            with path.open("a") as output:
                output.write("class OtherTest : TestCase() {}\n")
            result = helper.select(root, classes=raw)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            path.unlink()
            result = helper.select(root, classes=raw)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Unknown instrumentation test class", result.stderr)

    def test_receipt_records_actual_failure_and_check_identity(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="e" * 40, GITHUB_RUN_ID="19", GITHUB_RUN_ATTEMPT="1", SMOKE_MODE="smoke",
                           SMOKE_TEST_CLASSES=",".join(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=9) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record("python3"))
                    result = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("pause-receipts-targeted", result["scope"])
                    self.assertEqual(9, result["exit_code"])
                    self.assertEqual(dict(sha="e" * 40, run_id=19, attempt=1), result["identity"])
                    self.assertEqual(call.call_args.args[0], result["command"])
            finally:
                os.chdir(old)

    def test_eight_shards_preserve_real_empty_selections_and_inventory_digest(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); helper = self.fixture(root)
            selected = []; empty = 0
            raw = ",".join(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES)
            for shard in range(8):
                result = helper.select(root, shard, classes=raw, count=8)
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = root / "smoke/instrumentation-shards.json"
                selection = json.loads((root / "smoke/selection.json").read_text())
                plan = json.loads((root / "smoke/shard-plan.json").read_text())
                self.assertEqual("targeted", plan["allocation_scope"])
                self.assertCountEqual(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES, plan["allocated_classes"])
                self.assertEqual(hashlib.sha256(inventory.read_bytes()).hexdigest(), selection["inventory"]["sha256"])
                selected.extend(selection["selected_classes"])
                if not selection["selected_classes"]:
                    empty += 1
                    self.assertEqual("filtered-empty-shard", selection["status"])
                    self.assertEqual(0, selection["expected_tests"])
            self.assertEqual(4, empty)
            self.assertCountEqual(recorder.PAUSE_RECEIPT_PLATFORM_CLASSES, selected)
