"""Exact error-details source discovery and four-shard coverage."""
import json
from pathlib import Path
import tempfile
import unittest
import test_speech_platform_discovery as discovery

CLASSES = ('app.daiyu.android.PlatformTest', 'app.daiyu.android.ControllerReconnectIntegrationTest', 'app.daiyu.android.ui.screens.chat.conversation.ChatTimelineDividerTest', 'app.daiyu.android.ui.screens.chat.conversation.ReconnectBoundaryPlacementTest', 'app.daiyu.android.storage.SQLiteChildTaskStoreTest')
TARGET = ",".join(CLASSES)


class ErrorDetailsSmokeScopeTest(unittest.TestCase):
    def fixture(self, root):
        helper = discovery.SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        paths, expected = [], {}
        for index, name in enumerate(CLASSES, 1):
            package, simple = name.rsplit(".", 1)
            path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"package {package}\nclass {simple} : TestCase() {{\n"
                            + "".join(f" fun testCase{i}() {{}}\n" for i in range(index)) + "}\n")
            paths.append(path)
            expected[name] = index
        return helper, paths, expected

    def test_four_shards_only_discover_five_sources_and_cover_each_once(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, _, expected = self.fixture(root)
            seen, total = [], 0
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(reversed(CLASSES)))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual(expected, inventory["all_classes"])
                self.assertEqual("targeted", selection["scope"])
                self.assertEqual(sorted(CLASSES), selection["requested_classes"])
                self.assertGreater(selection["expected_tests"], 0)
                self.assertEqual(sum(expected[n] for n in selection["selected_classes"]),
                                 selection["expected_tests"])
                seen.extend(selection["selected_classes"])
                total += selection["expected_tests"]
            self.assertCountEqual(CLASSES, seen)
            self.assertEqual(sum(expected.values()), total)

    def test_missing_empty_ambiguous_and_duplicate_sources_are_rejected(self):
        for defect in ("missing", "empty", "ambiguous", "duplicate"):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper, paths, _ = self.fixture(root)
                if defect == "missing":
                    paths[0].unlink()
                elif defect == "empty":
                    paths[0].write_text("")
                elif defect == "ambiguous":
                    paths[0].write_text(paths[0].read_text() + "class OtherTest : TestCase() {}\n")
                else:
                    paths[1].write_text(paths[0].read_text())
                result = helper.select(root, classes=TARGET)
                self.assertNotEqual(0, result.returncode)
                message = dict(missing="Unknown instrumentation test class",
                               empty="Unknown instrumentation test class",
                               ambiguous="Ambiguous instrumentation test declaration",
                               duplicate="Duplicate instrumentation test class")[defect]
                self.assertIn(message, result.stderr)
                self.assertFalse((root / "smoke/selection.json").exists())

    def test_unapproved_filters_keep_full_validation_and_unknown_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, _, _ = self.fixture(root)
            for value in (",".join(CLASSES[:-1]), TARGET + "," + CLASSES[0],
                          TARGET + ",app.daiyu.android.UnknownTest"):
                result = helper.select(root, classes=value)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            (root / discovery.SOURCE.parent / "UnrelatedTest.kt").unlink()
            result = helper.select(root, classes=TARGET + ",app.daiyu.android.UnknownTest")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Unknown instrumentation test class", result.stderr)
