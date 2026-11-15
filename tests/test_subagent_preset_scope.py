"""Only the exact preset UI pair opts into its incremental tool contracts."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

import test_speech_platform_discovery as discovery

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("preset_test_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)

CLASSES = (
    "app.daiyu.android.SubAgentPresetActionsSheetTest",
    "app.daiyu.android.SubAgentSettingsProfileSheetTest",
)
MODULES = (
    "test_subagent_preset_actions_theme_contract", "test_subagent_profile_delete_contract",
    "test_subagent_add_button_contract", "test_subagent_always_enabled_contract",
    "test_settings_copy_cleanup_contract.SettingsCopyCleanupContractTest.test_subagent_intro_removed_without_hiding_directory_status_or_card_summary",
    "test_subagent_preset_scope", "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


class SubAgentPresetScopeTest(unittest.TestCase):
    def test_exact_pair_and_reversed_pair_keep_all_preset_contracts(self):
        self.assertEqual(recorder.SUBAGENT_PRESET_PLATFORM_CLASSES, CLASSES)
        self.assertEqual(recorder.SUBAGENT_PRESET_MODULES, MODULES)
        for names in (CLASSES, tuple(reversed(CLASSES))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(names)))
            self.assertEqual(command, ["python3", "-B", "-m", "unittest", *MODULES, "-v"])
            self.assertEqual(scope, "subagent-presets-targeted")

    def test_missing_extended_duplicate_and_other_mode_selectors_keep_full_checks(self):
        raw = ",".join(CLASSES)
        invalid = ("", CLASSES[0], CLASSES[1], raw + ",app.daiyu.android.OtherTest",
                   raw + "," + CLASSES[0], raw + "," + CLASSES[1], " " + raw, raw + ",", raw.replace(CLASSES[0], CLASSES[0] + "Extra"))
        for classes in invalid:
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))
            self.assertEqual(scope, "full", classes)
            self.assertEqual(command, ["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"])
        for mode in ("", "compile", "checks", "daily", "release"):
            _, scope = recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))
            self.assertEqual(scope, "full", mode)


class SubAgentPresetDiscoveryTest(unittest.TestCase):
    def fixture(self, root):
        helper = discovery.SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        paths = []
        for index, name in enumerate(CLASSES):
            path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package app.daiyu.android\nclass " + name.rsplit(".", 1)[1] + " : TestCase() {\n"
                            + "".join(" fun testCase" + str(i) + "() {}\n" for i in range(index + 2)) + "}\n")
            paths.append(path)
        return helper, paths

    def test_exact_pair_is_discovered_once_and_two_empty_shards_remain_explicit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, _ = self.fixture(root)
            for names in (CLASSES, tuple(reversed(CLASSES))):
                selected, empty = [], 0
                for shard in range(4):
                    result = helper.select(root, shard, classes=",".join(names))
                    self.assertEqual(0, result.returncode, result.stderr)
                    selection = json.loads((root / "smoke/selection.json").read_text())
                    inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                    self.assertEqual("targeted", inventory["discovery_scope"])
                    self.assertEqual(dict(zip(CLASSES, (2, 3))), inventory["all_classes"])
                    selected.extend(selection["selected_classes"])
                    if not selection["selected_classes"]:
                        empty += 1
                        self.assertEqual("filtered-empty-shard", selection["status"])
                        self.assertEqual(0, selection["expected_tests"])
                self.assertCountEqual(CLASSES, selected)
                self.assertEqual(2, empty)

    def test_requested_source_method_count_changes_and_missing_or_ambiguous_sources_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, paths = self.fixture(root)
            path = paths[0]
            path.write_text(path.read_text().replace(" fun testCase0()", " fun testAdded() {}\n fun testCase0()"))
            result = helper.select(root, classes=",".join(CLASSES))
            self.assertEqual(0, result.returncode, result.stderr)
            inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
            self.assertEqual(3, inventory["all_classes"][CLASSES[0]])
            with path.open("a") as output:
                output.write("class OtherTest : TestCase() {}\n")
            result = helper.select(root, classes=",".join(CLASSES))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            path.unlink()
            result = helper.select(root, classes=",".join(CLASSES))
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Unknown instrumentation test class", result.stderr)


if __name__ == "__main__":
    unittest.main()
