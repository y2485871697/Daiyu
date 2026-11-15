"""Exact catalog editor smoke selection keeps the change incremental."""
import json
from pathlib import Path
import tempfile
import unittest

from test_record_tool_tests_scope import recorder


class CatalogModelEditorSmokeScopeTest(unittest.TestCase):
    def test_only_the_exact_editor_selector_uses_targeted_contracts(self):
        target = recorder.CATALOG_MODEL_EDITOR_PLATFORM_CLASS
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=target))
        self.assertEqual("catalog-model-editor-targeted", scope)
        self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.CATALOG_MODEL_EDITOR_MODULES, "-v"], command)
        self.assertIn("test_model_editor_defaults_contract", command)
        for value in ("", " " + target, target + "," + target, target + ",app.daiyu.android.OtherTest"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=target))[1])

    def fixture(self, root):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        target = recorder.CATALOG_MODEL_EDITOR_PLATFORM_CLASS
        path = root / "app/src/androidTest/kotlin" / (target.replace(".", "/") + ".kt")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package app.daiyu.android.ui.screens.settings\nclass CatalogModelEditorLayoutTest : TestCase() {\n fun testEditor() {}\n}\n")
        return helper, path, target

    def test_discovery_is_targeted_and_empty_shards_remain_explicit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, path, target = self.fixture(root)
            for shard in range(4):
                result = helper.select(root, shard, classes=target)
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({target: 1}, inventory["all_classes"])
                self.assertEqual(1 if shard == 0 else 0, selection["expected_tests"])
                self.assertEqual([target] if shard == 0 else [], selection["selected_classes"])

    def test_missing_or_ambiguous_source_fails_instead_of_passing_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, path, target = self.fixture(root)
            path.write_text(path.read_text() + "class OtherTest : TestCase() {}\n")
            self.assertNotEqual(0, helper.select(root, classes=target).returncode)
            path.unlink()
            self.assertNotEqual(0, helper.select(root, classes=target).returncode)
