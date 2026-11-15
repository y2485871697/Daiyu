import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("provider_catalog_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class ProviderCatalogTabsContractTest(unittest.TestCase):
    def test_page_owns_preload_and_tab_uses_the_same_catalog(self):
        source = (ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings/ProviderCatalogSettingsScreen.kt").read_text()
        self.assertIn("remember(providerIdentity, persistence) { LocalCatalogModelState", source)
        self.assertIn("LaunchedEffect(localCatalog, localRevision)", source)
        self.assertEqual(2, source.count("catalog = localCatalog"))
        self.assertLess(source.index("val localCatalog ="), source.index("if (twoTabs && modelTab"))

    def test_unknown_count_is_not_an_empty_result_and_refresh_keeps_snapshot(self):
        source = (ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings/LocalCatalogModelsScreen.kt").read_text()
        self.assertNotIn("正在读取本地模型", source)
        self.assertIn("LaunchedEffect(visit) { reload(force = false) }", source)
        self.assertIn("Lifecycle.Event.ON_RESUME && !resumed", source)
        self.assertIn('else "模型列表"', source)
        self.assertNotIn("snapshot = null", source)
        self.assertNotIn("loaded = false", source)
        state = (ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings/LocalCatalogModelState.kt").read_text()
        self.assertIn("withContext(Dispatchers.IO)", state)
        self.assertIn("reads.withLock", state)
        self.assertIn("if (!force && loaded && loadedInvalidation == invalidation)", state)
        self.assertIn("throw cancelled", state)

    def test_only_exact_closed_class_set_gets_targeted_contracts(self):
        classes = recorder.PROVIDER_CATALOG_PLATFORM_CLASSES
        for value in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(value)))
            self.assertEqual("provider-catalog-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.PROVIDER_CATALOG_MODULES, "-v"], command)
        exact = ",".join(classes)
        for value in (classes[0], exact + "," + classes[0], exact + ",app.daiyu.android.OtherTest"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="compile", SMOKE_TEST_CLASSES=exact))[1])

    def test_targeted_inventory_is_complete_and_unrelated_sources_are_not_scanned(self):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper.fixture(root)
            classes = recorder.PROVIDER_CATALOG_PLATFORM_CLASSES
            for name in classes:
                package, simple = name.rsplit(".", 1)
                path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + package + "\nclass " + simple + " : TestCase() {\n fun testOriginalName() {}\n}\n")
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(classes))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({name: 1 for name in classes}, inventory["all_classes"])
                selected.extend(selection["selected_classes"])
            self.assertCountEqual(classes, selected)
