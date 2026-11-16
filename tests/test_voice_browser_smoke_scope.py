"""Voice/browser controller integration has one exact, isolated smoke scope."""
import json
from pathlib import Path
import tempfile
import unittest

from test_record_tool_tests_scope import recorder
import test_speech_platform_discovery as speech_discovery

ROOT = Path(__file__).resolve().parents[1]
CLASS = "app.daiyu.android.ControllerModelPreparationTest"
SOURCE = Path("app/src/androidTest/kotlin/app/daiyu/android/ControllerModelPreparationTest.kt")


class VoiceBrowserSmokeScopeTest(unittest.TestCase):
    def fixture(self, root):
        helper = speech_discovery.SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        target = root / SOURCE
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text((ROOT / SOURCE).read_text())
        return helper

    def test_controller_discovery_ignores_unrelated_sources_and_retains_empty_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            nonempty = []
            for shard in range(4):
                result = helper.select(root, shard, classes=CLASS)
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({CLASS}, set(inventory["all_classes"]))
                self.assertGreater(inventory["all_classes"][CLASS], 0)
                if selection["expected_tests"]:
                    nonempty.append(shard)
                    self.assertEqual([CLASS], selection["selected_classes"])
                else:
                    self.assertEqual("filtered-empty-shard", selection["status"])
            self.assertEqual([0], nonempty)

    def test_scope_is_exact_and_does_not_silence_unknown_or_ambiguous_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            (root / SOURCE).unlink()
            self.assertNotEqual(0, helper.select(root, classes=CLASS).returncode)
            self.assertNotEqual(0, helper.select(root, classes=CLASS + ",app.daiyu.android.UnknownTest").returncode)

    def test_recorder_selects_incremental_checks_only_for_the_exact_smoke_class(self):
        command, scope = recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=CLASS))
        self.assertEqual("voice-browser-targeted", scope)
        self.assertEqual(["python", "-B", "-m", "unittest", "test_voice_call_contract", "test_voice_browser_smoke_scope",
                          "test_browser_navigation_defaults_contract", "test_windows_unit_skips", "-v"], command)
        for mode, classes in (("build", CLASS), ("smoke", CLASS + "," + CLASS)):
            _, other = recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=classes))
            self.assertNotEqual(scope, other)
