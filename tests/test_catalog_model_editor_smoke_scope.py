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


class SearchBrowserSmokeScopeTest(unittest.TestCase):
    def test_exact_three_class_selector_is_targeted_without_broadening(self):
        classes = recorder.SEARCH_BROWSER_PLATFORM_CLASSES
        self.assertEqual((
            "app.daiyu.android.ui.screens.settings.CatalogModelEditorLayoutTest",
            "app.daiyu.android.storage.ModelSettingsCatalogBindingTest",
            "app.daiyu.android.browser.BrowserProductionCompositionTest",
        ), classes)
        target = ",".join(classes)
        for selector in (target, ",".join(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=selector))
            self.assertEqual("browser-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.SEARCH_BROWSER_MODULES, "-v"], command)
            self.assertNotIn("discover", command)
            self.assertEqual(len(command), len(set(command)))
        for selector in ("", " " + target, target + "," + classes[0],
                         ",".join(classes[:2]), target + ",app.daiyu.android.OtherTest"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=selector))[1])
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=target))[1])

    def fixture(self, root):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        paths = []
        for name in recorder.SEARCH_BROWSER_PLATFORM_CLASSES:
            package, simple = name.rsplit(".", 1)
            path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"package {package}\nclass {simple} : TestCase() {{\n fun testTarget() {{}}\n}}\n")
            paths.append(path)
        # Unrelated malformed sources must not enter the targeted inventory.
        unrelated = root / "app/src/androidTest/kotlin/Unrelated.kt"
        unrelated.write_text("package app.daiyu.android\nclass UnrelatedTest : TestCase() {\n fun testUnrelated() {}\n}\nclass OtherTest : TestCase() {}\n")
        return helper, paths, ",".join(recorder.SEARCH_BROWSER_PLATFORM_CLASSES)

    def test_discovers_only_three_sources_and_preserves_empty_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, paths, target = self.fixture(root)
            seen = []
            counts = []
            for shard in range(4):
                result = helper.select(root, shard, classes=target)
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({name: 1 for name in recorder.SEARCH_BROWSER_PLATFORM_CLASSES}, inventory["all_classes"])
                seen.extend(selection["selected_classes"])
                counts.append(selection["expected_tests"])
            self.assertCountEqual(recorder.SEARCH_BROWSER_PLATFORM_CLASSES, seen)
            self.assertEqual([0, 1, 1, 1], sorted(counts))

    def test_missing_or_ambiguous_target_source_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, paths, target = self.fixture(root)
            paths[0].write_text(paths[0].read_text() + "class OtherTest : TestCase() {}\n")
            self.assertNotEqual(0, helper.select(root, classes=target).returncode)
            paths[0].unlink()
            self.assertNotEqual(0, helper.select(root, classes=target).returncode)
