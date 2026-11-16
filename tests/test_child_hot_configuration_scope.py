"""Exact child hot-configuration regression keeps frozen validation and discovery narrow."""
import importlib.util
import inspect
import io
import os
from types import SimpleNamespace
from unittest.mock import patch
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("child_hot_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class ChildHotConfigurationScopeTest(unittest.TestCase):
    CLASSES = ('app.daiyu.android.ChildStopChoiceActivityTest', 'app.daiyu.android.ControllerChildRetentionTest', 'app.daiyu.android.ControllerChildModelsTest', 'app.daiyu.android.storage.SQLiteChildTaskStoreTest', 'app.daiyu.android.model.usage.AttributedUsageDatabaseTest', 'app.daiyu.android.backup.TypedUsageBackupTest', 'app.daiyu.android.ControllerGenerationIntegrationTest', 'app.daiyu.android.integration.tools.RunGenerationToolsDeviceTest', 'app.daiyu.android.storage.SQLiteGenerationAttemptStoreTest', 'app.daiyu.android.storage.SQLiteGenerationOutputStoreTest')

    def test_recorder_requires_the_exact_complete_set(self):
        self.assertEqual(self.CLASSES, recorder.CHILD_HOT_CONFIGURATION_PLATFORM_CLASSES)
        modules = tuple(dict.fromkeys(recorder.CHILD_STOP_MODULES + recorder.GENERATION_PORT_MODULES + ("test_child_hot_configuration_scope", "test_windows_unit_skips")))
        self.assertEqual(modules, recorder.CHILD_HOT_CONFIGURATION_MODULES)
        for values in (self.CLASSES, tuple(reversed(self.CLASSES))):
            command, scope = recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(values)))
            self.assertEqual("child-hot-configuration-targeted", scope)
            self.assertEqual(["python", "-B", "-m", "unittest", *modules, "-v"], command)
        for values in (self.CLASSES[:-1], self.CLASSES + (self.CLASSES[0],), self.CLASSES + ("unknown",)):
            with self.assertRaisesRegex(ValueError, "Exact child hot configuration"):
                recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(values)))
        for mode in ("build", "candidate", "daily", "release"):
            _, scope = recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=",".join(self.CLASSES)))
            self.assertNotEqual("child-hot-configuration-targeted", scope)

    def test_frozen_recorder_uses_the_validation_checkout(self):
        source = inspect.getsource(recorder.record)
        self.assertIn('if scope in ("doubao-motion-targeted", "child-hot-configuration-targeted",', source)
        self.assertIn('validation_cwd = Path(__file__).resolve().parents[2]', source)
        self.assertIn('cwd=validation_cwd', source)

    def test_frozen_receipt_keeps_source_and_tool_revision_and_failure(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="b" * 40, EXPECTED_SHA="a" * 40,
                           GITHUB_RUN_ID="43", GITHUB_RUN_ATTEMPT="1",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(self.CLASSES))
                with patch.dict(os.environ, env, clear=True), \
                        patch.object(recorder.subprocess, "check_output", return_value="a" * 40 + "\n"), \
                        patch.object(recorder.subprocess, "call", return_value=7) as call, \
                        patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(7, recorder.record("python3"))
                result = json.loads(Path("tool-evidence/receipt.json").read_text())
                self.assertEqual("child-hot-configuration-targeted", result["scope"])
                self.assertEqual(7, result["exit_code"])
                self.assertEqual(call.call_args.args[0], result["command"])
                self.assertNotIn("discover", result["command"])
                self.assertEqual("a" * 40, result["identity"]["sha"])
                self.assertEqual("b" * 40, result["validation_tools_sha"])
                self.assertEqual(ROOT, call.call_args.kwargs["cwd"])
            finally:
                os.chdir(old)

    def fixture(self, root):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        for name in self.CLASSES:
            relative = Path("app/src/androidTest/kotlin") / (name.replace(".", "/") + ".kt")
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text((ROOT / relative).read_text())
        return helper

    def test_selector_reads_only_exact_files_and_retains_empty_shards(self):
        for count in (4, 16):
            with self.subTest(shards=count), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper = self.fixture(root)
                seen = []
                empty = 0
                for shard in range(count):
                    result = helper.select(root, shard, classes=",".join(reversed(self.CLASSES)), count=count)
                    self.assertEqual(0, result.returncode, result.stderr)
                    inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                    selection = json.loads((root / "smoke/selection.json").read_text())
                    self.assertEqual("targeted", inventory["discovery_scope"])
                    self.assertEqual(set(self.CLASSES), set(inventory["all_classes"]))
                    self.assertTrue(all(value > 0 for value in inventory["all_classes"].values()))
                    seen.extend(selection["selected_classes"])
                    empty += selection["status"] == "filtered-empty-shard"
                self.assertCountEqual(self.CLASSES, seen)
                if count == 16:
                    self.assertGreaterEqual(empty, 6)

    def test_selector_rejects_missing_duplicate_unknown_and_missing_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            for values in (self.CLASSES[:-1], self.CLASSES + (self.CLASSES[0],), self.CLASSES + ("unknown",)):
                self.assertNotEqual(0, helper.select(root, classes=",".join(values)).returncode)
            (root / "app/src/androidTest/kotlin" / (self.CLASSES[0].replace(".", "/") + ".kt")).unlink()
            self.assertNotEqual(0, helper.select(root, classes=",".join(self.CLASSES)).returncode)
