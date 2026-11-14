"""Platform-only migration fixture refresh keeps the original production APK."""
from pathlib import Path
import importlib.util
import json
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CLASS = "app.daiyu.android.PlatformTest"
SOURCE = Path("app/src/androidTest/kotlin/app/daiyu/android/PlatformTest.kt")


class PlatformSmokeScopeTest(unittest.TestCase):
    def fixture(self, root):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        (root / "smoke").mkdir()
        path = root / SOURCE
        path.parent.mkdir(parents=True)
        path.write_text((ROOT / SOURCE).read_text())
        (path.parent / "UnrelatedTest.kt").write_text(
            "package app.daiyu.android\nclass OneTest : TestCase() {\n fun testOne() {}\n}\nclass TwoTest : TestCase() {}\n")
        return SpeechPlatformDiscoveryTest()

    def test_exact_platform_scope_keeps_every_method_in_shard_zero(self):
        expected = len(re.findall(r"^\s*fun test\w+\(", (ROOT / SOURCE).read_text(), re.M))
        self.assertGreater(expected, 0)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            for shard in range(4):
                result = helper.select(root, shard=shard, classes=CLASS)
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / "smoke/selection.json").read_text())
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({CLASS: expected}, inventory["all_classes"])
                self.assertEqual(expected if shard == 0 else 0, selection["expected_tests"])
                self.assertEqual([CLASS] if shard == 0 else [], selection["selected_classes"])

    def test_missing_ambiguous_or_non_exact_targets_still_fail(self):
        for case in ("missing", "ambiguous", "extra", "duplicate", "malformed", "candidate"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper = self.fixture(root)
                path = root / SOURCE
                classes, mode = CLASS, "smoke"
                if case == "missing":
                    path.unlink()
                elif case == "ambiguous":
                    path.write_text(path.read_text() + "\nclass OtherTest : TestCase() {}\n")
                elif case == "extra":
                    classes += ",app.daiyu.android.UnknownTest"
                elif case == "duplicate":
                    classes += "," + CLASS
                elif case == "malformed":
                    classes = " " + CLASS
                else:
                    mode = "candidate"
                result = helper.select(root, classes=classes, mode=mode)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((root / "smoke/selection.json").exists())

    def test_cloud_tool_scope_is_targeted_and_keeps_source_binding_contracts(self):
        spec = importlib.util.spec_from_file_location("platform_receipt", ROOT / "scripts/acceptance/record_tool_tests.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        command, scope = module.command_for("python3", {"SMOKE_MODE": "smoke", "SMOKE_TEST_CLASSES": CLASS})
        self.assertEqual("platform-targeted", scope)
        self.assertIn("test_platform_smoke_scope", command)
        self.assertIn("test_test_refresh_contract", command)
        self.assertNotIn("discover", command)
