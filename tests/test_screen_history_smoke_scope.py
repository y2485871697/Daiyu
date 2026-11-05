"""Exact screen-history-only source selection and receipt scope."""
import json
from pathlib import Path
import tempfile
import unittest
import test_speech_platform_discovery as discovery
from scripts.acceptance import record_tool_tests as recorder

CLASS = "app.daiyu.android.storage.ScreenProtocolHistoryAndroidTest"
SOURCE = Path("app/src/androidTest/kotlin") / (CLASS.replace(".", "/") + ".kt")

class ScreenHistoryScopeTest(unittest.TestCase):
    def test_exact_source_has_one_test_and_empty_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = discovery.SpeechPlatformDiscoveryTest()
            helper.fixture(root)
            path = root / SOURCE
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package app.daiyu.android.storage\nclass ScreenProtocolHistoryAndroidTest : TestCase() {\n fun testLargeHistory() {}\n}\n")
            for shard in range(4):
                result = helper.select(root, shard, classes=CLASS)
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / "smoke/selection.json").read_text())
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({CLASS: 1}, inventory["all_classes"])
                self.assertEqual(1 if shard == 0 else 0, selection["expected_tests"])
            with path.open("a") as output:
                output.write("class ExtraTest : TestCase() {}\n")
            self.assertNotEqual(0, helper.select(root, classes=CLASS).returncode)

    def test_only_exact_smoke_scope_selects_targeted_receipt(self):
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=CLASS))
        self.assertEqual("screen-history-targeted", scope)
        self.assertIn("test_screen_history_smoke_scope", command)
        for raw in (CLASS + "," + CLASS, CLASS + ",app.daiyu.android.OtherTest", " " + CLASS, CLASS + "Extra"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=raw))[1])
        for mode in ("candidate", "build", "daily", "compile"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=CLASS))[1])
