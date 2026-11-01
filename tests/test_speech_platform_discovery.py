"""Execute the production selector only; no emulator or project compilation."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = (ROOT / "scripts/smoke_boot.sh").read_text().split("<<'PYCOUNT'\n", 1)[1].split("\nPYCOUNT", 1)[0]
CLASS = "app.daiyu.android.voice.SpeechAudioFileAndroidTest"
SOURCE = Path("app/src/androidTest/kotlin/app/daiyu/android/voice/SpeechAudioFileAndroidTest.kt")
LEGACY = "package app.daiyu.android.voice\nclass SpeechAudioFileAndroidTest : TestCase() {\n fun testNativeDuration() {}\n}\n"


class SpeechPlatformDiscoveryTest(unittest.TestCase):
    def select(self, root, shard=0, classes=CLASS, mode="smoke", count=4):
        return subprocess.run([sys.executable, "-B", "-c", SCRIPT], cwd=root, text=True, capture_output=True,
                              env=dict(os.environ, SMOKE_SCRIPT_DIR=str(ROOT / "scripts"), SMOKE_MODE=mode,
                                       SMOKE_TEST_CLASSES=classes, SMOKE_SHARD_COUNT=str(count), SMOKE_SHARD_INDEX=str(shard)))

    def fixture(self, root, source=LEGACY):
        (root / "smoke").mkdir()
        path = root / SOURCE
        path.parent.mkdir(parents=True)
        path.write_text(source)
        (path.parent / "UnrelatedTest.kt").write_text(
            "package app.daiyu.android.voice\nclass OneTest : TestCase() {\n fun testOne() {}\n}\nclass TwoTest : TestCase() {}\n")

    def test_exact_speech_scope_ignores_unrelated_inventory_and_keeps_empty_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            for shard in range(4):
                result = self.select(root, shard)
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / "smoke/selection.json").read_text())
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({CLASS: 1}, inventory["all_classes"])
                self.assertEqual(1 if shard == 0 else 0, selection["expected_tests"])
                self.assertEqual([CLASS] if shard == 0 else [], selection["selected_classes"])
                self.assertEqual("planned" if shard == 0 else "filtered-empty-shard", selection["status"])

    def test_ambiguity_in_the_requested_file_still_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, LEGACY + "class OtherTest : TestCase() {}\n")
            result = self.select(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Ambiguous instrumentation test declaration", result.stderr)

    def test_missing_requested_file_does_not_report_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            (root / SOURCE).unlink()
            result = self.select(root)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / "smoke/selection.json").exists())

    def test_other_filtered_scopes_preserve_full_inventory_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            for classes in ("app.daiyu.android.voice.UnknownTest", CLASS + ",app.daiyu.android.voice.UnknownTest"):
                result = self.select(root, classes=classes)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Ambiguous instrumentation test declaration", result.stderr)

    def test_unknown_class_remains_rejected_with_an_otherwise_valid_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            (root / SOURCE.parent / "UnrelatedTest.kt").unlink()
            result = self.select(root, classes="app.daiyu.android.voice.UnknownTest", count=1)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Unknown instrumentation test class", result.stderr)

    def test_full_inventory_and_legacy_class_remain_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            (root / SOURCE.parent / "UnrelatedTest.kt").unlink()
            result = self.select(root, classes="", mode="build", count=1)
            self.assertEqual(0, result.returncode, result.stderr)
            inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
            self.assertEqual("full", inventory["discovery_scope"])
            self.assertEqual({CLASS: 1}, inventory["all_classes"])
