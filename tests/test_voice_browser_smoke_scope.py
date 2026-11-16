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


    def test_controller_and_dictation_pair_stays_targeted_in_both_orders(self):
        other = "app.daiyu.android.DictationActivityTest"
        for names in ((CLASS, other), (other, CLASS)):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper = self.fixture(root)
                second = root / SOURCE.with_name("DictationActivityTest.kt")
                second.write_text("package app.daiyu.android\nclass DictationActivityTest : TestCase() {\n fun testOne() {}\n}\n")
                raw = ",".join(names)
                self.assertEqual("voice-browser-targeted", recorder.command_for("python", dict(
                    SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=raw))[1])
                for shard in range(4):
                    result = helper.select(root, shard, classes=raw)
                    self.assertEqual(0, result.returncode, result.stderr)
                    inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                    self.assertEqual("targeted", inventory["discovery_scope"])
                    self.assertEqual(set(names), set(inventory["all_classes"]))
                    self.assertEqual([True, True, False, False], [bool(s["classes"]) for s in inventory["shards"]])
                for source in (second, root / SOURCE):
                    saved = source.read_text(); source.unlink()
                    self.assertNotEqual(0, helper.select(root, classes=raw).returncode)
                    source.write_text(saved)
        for mode, raw in (("build", CLASS + "," + other), ("smoke", other),
                          ("smoke", CLASS + "," + other + "," + other),
                          ("smoke", CLASS + "," + other + ",app.daiyu.android.UnknownTest")):
            self.assertNotEqual("voice-browser-targeted", recorder.command_for("python", dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def test_voice_browser_queue_is_limited_to_the_two_exact_smoke_lists(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        concurrency = workflow.split("\nconcurrency:\n", 1)[1].split("\njobs:\n", 1)[0]
        group = next(line for line in concurrency.splitlines() if line.startswith("  group: "))
        other = "app.daiyu.android.DictationActivityTest"
        suffix = (
            "${{ inputs.mode == 'smoke' && (inputs.smoke_classes == '" + CLASS
            + "' || inputs.smoke_classes == '" + CLASS + "," + other
            + "') && '-voice-browser' || '' }}"
        )
        self.assertTrue(group.endswith(suffix), group)
        self.assertEqual(1, group.count("-voice-browser"))
        original = group[:-len(suffix)]
        self.assertTrue(original.startswith("  group: daiyu-${{ inputs.mode }}-${{ github.ref }}"))
        self.assertNotIn(CLASS, original)
        self.assertNotIn(other, original)
        self.assertIn("  cancel-in-progress: false", concurrency.splitlines())
