"""Run the real inventory selector with fixtures, not an emulator or Kotlin compiler."""
import json
from pathlib import Path
import tempfile
import unittest

import test_speech_platform_discovery as discovery

CLASS = "app.daiyu.android.gui.GuiVisualWindowParserAndroidTest"
SOURCE = Path("app/src/androidTest/kotlin") / (CLASS.replace(".", "/") + ".kt")


class GuiParserSmokeScopeTest(unittest.TestCase):
    def fixture(self, root):
        helper = discovery.SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        path = root / SOURCE
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package app.daiyu.android.gui\nclass GuiVisualWindowParserAndroidTest : TestCase() {\n" +
                        "".join(" fun testCase" + str(i) + "() {}\n" for i in range(5)) + "}\n")
        return helper

    def test_exact_parser_inventory_is_targeted_with_five_tests_and_three_empty_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            for shard in range(4):
                result = helper.select(root, shard, classes=CLASS)
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / "smoke/selection.json").read_text())
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({CLASS: 5}, inventory["all_classes"])
                self.assertEqual([CLASS], selection["requested_classes"])
                self.assertEqual([CLASS] if shard == 0 else [], selection["selected_classes"])
                self.assertEqual(5 if shard == 0 else 0, selection["expected_tests"])
                self.assertEqual("planned" if shard == 0 else "filtered-empty-shard", selection["status"])

    def test_requested_parser_source_ambiguity_is_not_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            with (root / SOURCE).open("a") as output:
                output.write("class OtherTest : TestCase() {}\n")
            result = helper.select(root, classes=CLASS)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            self.assertFalse((root / "smoke/selection.json").exists())

    def test_missing_parser_source_never_produces_a_success_selection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            (root / SOURCE).unlink()
            result = helper.select(root, classes=CLASS)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / "smoke/selection.json").exists())

    def test_mixed_duplicate_and_other_mode_filters_do_not_narrow_discovery(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            for raw in (CLASS + ",app.daiyu.android.UnknownTest", CLASS + "," + CLASS):
                result = helper.select(root, classes=raw)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            for mode in ("build", "candidate"):
                result = helper.select(root, classes=CLASS, mode=mode)
                self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / "smoke/selection.json").exists())

    def test_unknown_class_is_rejected_in_an_otherwise_valid_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            (root / discovery.SOURCE.parent / "UnrelatedTest.kt").unlink()
            result = helper.select(root, classes=CLASS + "Unknown", count=1)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Unknown instrumentation test class", result.stderr)

    def test_full_mode_keeps_both_valid_source_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            (root / discovery.SOURCE.parent / "UnrelatedTest.kt").unlink()
            result = helper.select(root, classes="", mode="build", count=1)
            self.assertEqual(0, result.returncode, result.stderr)
            inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
            self.assertEqual("full", inventory["discovery_scope"])
            self.assertEqual({CLASS: 5, discovery.CLASS: 1}, inventory["all_classes"])
