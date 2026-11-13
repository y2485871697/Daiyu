import importlib.util
import io
import itertools
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("speech_test_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class RecordToolTestsScopeTest(unittest.TestCase):
    def test_default_keeps_full_command_and_receipt_contract(self):
        command, scope = recorder.command_for("python3", {})
        self.assertEqual(command, ["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"])
        self.assertEqual(scope, "full")

    def test_exact_speech_platform_scope_runs_only_explicit_modules(self):
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=recorder.SPEECH_PLATFORM_CLASS))
        self.assertEqual(command, ["python3", "-B", "-m", "unittest", *recorder.SPEECH_MODULES, "-v"])
        self.assertEqual(scope, "speech-targeted")
        self.assertEqual(len(recorder.SPEECH_MODULES), 5)

    def test_exact_menu_scope_runs_all_menu_contracts_and_selector_checks(self):
        for classes in (recorder.MENU_PLATFORM_CLASSES, tuple(reversed(recorder.MENU_PLATFORM_CLASSES))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
            self.assertEqual(command, ["python3", "-B", "-m", "unittest", *recorder.MENU_MODULES, "-v"])
            self.assertEqual(scope, "menu-targeted")
        self.assertEqual(len(recorder.MENU_PLATFORM_CLASSES), 6)
        self.assertEqual(len(recorder.MENU_MODULES), 9)

    def test_partial_extended_duplicate_and_other_mode_menu_scopes_keep_full_contracts(self):
        menu = ",".join(recorder.MENU_PLATFORM_CLASSES)
        for classes in (",".join(recorder.MENU_PLATFORM_CLASSES[:-1]), menu + ",app.daiyu.android.OtherTest",
                        menu + "," + recorder.MENU_PLATFORM_CLASSES[0], menu + "," + recorder.SPEECH_PLATFORM_CLASS):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))[1])
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=menu))[1])

    def test_other_modes_keep_full_contracts(self):
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=recorder.SPEECH_PLATFORM_CLASS))[1])

    def test_unknown_or_combined_classes_do_not_reduce_coverage(self):
        for classes in ("", "unknown", recorder.SPEECH_PLATFORM_CLASS + ",app.daiyu.android.OtherTest", "$(echo unsafe)"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))[1])

    def test_targeted_receipt_records_actual_failure_and_command(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="a" * 40, GITHUB_RUN_ID="12", GITHUB_RUN_ATTEMPT="1",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=recorder.SPEECH_PLATFORM_CLASS)
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=7) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(7, recorder.record("python3"))
                    result = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("speech-targeted", result["scope"])
                    self.assertEqual(7, result["exit_code"])
                    self.assertEqual(call.call_args.args[0], result["command"])
                    self.assertEqual(12, result["identity"]["run_id"])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)

    def test_menu_receipt_keeps_actual_failure_identity_command_and_pythonpath(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="b" * 40, GITHUB_RUN_ID="13", GITHUB_RUN_ATTEMPT="2",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(recorder.MENU_PLATFORM_CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=9) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record("python3"))
                    result = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("menu-targeted", result["scope"])
                    self.assertEqual(9, result["exit_code"])
                    self.assertEqual(call.call_args.args[0], result["command"])
                    self.assertEqual(dict(sha="b" * 40, run_id=13, attempt=2), result["identity"])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)

    def test_failed_launch_removes_stale_success_without_replacing_it(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                receipt = Path("tool-evidence/receipt.json")
                receipt.parent.mkdir()
                receipt.write_text('{"exit_code":0}')
                env = dict(GITHUB_SHA="a" * 40, GITHUB_RUN_ID="12", GITHUB_RUN_ATTEMPT="1")
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", side_effect=OSError("not started")):
                    with self.assertRaises(OSError):
                        recorder.record("python3")
                self.assertFalse(receipt.exists())
            finally:
                os.chdir(old)


class ColdDisplaySmokeScopeTest(unittest.TestCase):
    def test_exact_display_scope_keeps_union_of_history_storage_context_and_guards(self):
        classes = recorder.COLD_DISPLAY_PLATFORM_CLASSES
        self.assertEqual(8, len(classes))
        self.assertEqual(len(classes), len(set(classes)))
        required = set(recorder.MARKDOWN_ENTRY_MODULES) | set(recorder.SQLITE_TEXT_MODULES) | {
            "test_message_display_contract", "test_context_usage_wiring_contract"}
        self.assertTrue({"test_message_display_contract", "test_context_usage_wiring_contract",
                         "test_markdown_entry_contract", "test_large_history_storage_contract",
                         "test_checkpoint_encoding_hotpath_contract", "test_sqlite_text_smoke_scope"} <= required)
        self.assertEqual(required, set(recorder.COLD_DISPLAY_MODULES))
        self.assertEqual(len(required), len(recorder.COLD_DISPLAY_MODULES))
        for names in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(names)))
            self.assertEqual("cold-display-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.COLD_DISPLAY_MODULES, "-v"], command)
        raw = ",".join(classes)
        for value in (raw + "," + classes[0], raw + ",app.daiyu.android.OtherTest", ",".join(classes[:-1]), " " + raw):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        for mode in ("candidate", "daily", "release", "compile", "build", ""):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def fixture(self, root):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        for index, name in enumerate(recorder.COLD_DISPLAY_PLATFORM_CLASSES):
            package, _, short = name.rpartition(".")
            path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + package + "\nclass " + short + " : TestCase() {\n" +
                            "".join(" fun testCase" + str(i) + "() {}\n" for i in range(index + 1)) + "}\n")
        return helper

    def test_display_inventory_covers_all_eight_classes_once_and_detects_new_methods(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            selected = []
            classes = recorder.COLD_DISPLAY_PLATFORM_CLASSES
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(reversed(classes)))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({name: i + 1 for i, name in enumerate(classes)}, inventory["all_classes"])
                self.assertEqual(sum(inventory["all_classes"][name] for name in selection["selected_classes"]), selection["expected_tests"])
                selected.extend(selection["selected_classes"])
            self.assertCountEqual(classes, selected)
            path = root / "app/src/androidTest/kotlin" / (classes[0].replace(".", "/") + ".kt")
            path.write_text(path.read_text().replace(" fun testCase0() {}", " fun testCase0() {}\n fun testAdded() {}"))
            result = helper.select(root, classes=",".join(classes))
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(2, json.loads((root / "smoke/instrumentation-shards.json").read_text())["all_classes"][classes[0]])

    def test_requested_display_source_ambiguity_missing_and_unapproved_combinations_stay_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            classes = recorder.COLD_DISPLAY_PLATFORM_CLASSES
            raw = ",".join(classes)
            for value in (raw + "," + classes[0], raw + ",app.daiyu.android.OtherTest", ",".join(classes[:-1])):
                result = helper.select(root, classes=value)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            path = root / "app/src/androidTest/kotlin" / (classes[0].replace(".", "/") + ".kt")
            with path.open("a") as output:
                output.write("class OtherTest : TestCase() {}\n")
            result = helper.select(root, classes=raw)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            path.unlink()
            result = helper.select(root, classes=raw)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Unknown instrumentation test class", result.stderr)

    def test_display_receipt_keeps_real_failure_identity_command_and_pythonpath(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="d" * 40, GITHUB_RUN_ID="18", GITHUB_RUN_ATTEMPT="1", SMOKE_MODE="smoke",
                           SMOKE_TEST_CLASSES=",".join(recorder.COLD_DISPLAY_PLATFORM_CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=9) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record("python3"))
                    result = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("cold-display-targeted", result["scope"])
                    self.assertEqual(9, result["exit_code"])
                    self.assertEqual(dict(sha="d" * 40, run_id=18, attempt=1), result["identity"])
                    self.assertEqual(call.call_args.args[0], result["command"])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)


class RootlessCommitScopeTest(unittest.TestCase):
    def test_exact_pair_uses_union_and_keeps_existing_single_class_scope(self):
        for classes in (recorder.ROOTLESS_COMMIT_PLATFORM_CLASSES,
                        tuple(reversed(recorder.ROOTLESS_COMMIT_PLATFORM_CLASSES))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
            self.assertEqual("rootless-commit-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.ROOTLESS_COMMIT_MODULES, "-v"], command)
            self.assertNotIn("discover", command)
        self.assertEqual(len(recorder.ROOTLESS_COMMIT_MODULES), len(set(recorder.ROOTLESS_COMMIT_MODULES)))
        self.assertTrue(set(recorder.RUNTIME_SCREENSHOT_MODULES) <= set(recorder.ROOTLESS_COMMIT_MODULES))
        self.assertIn("test_rootless_content_smoke_scope", recorder.ROOTLESS_COMMIT_MODULES)
        gui, storage = recorder.ROOTLESS_COMMIT_PLATFORM_CLASSES
        self.assertEqual("rootless-content-targeted", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=gui))[1])
        raw = ",".join(recorder.ROOTLESS_COMMIT_PLATFORM_CLASSES)
        for invalid in (storage, raw + "," + gui, raw + ",unknown", " " + raw, raw + ","):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=invalid))[1])
        for mode in ("compile", "candidate", "daily", "release", ""):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def test_exact_source_pair_is_discovered_once_and_keeps_two_empty_shards(self):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = SpeechPlatformDiscoveryTest()
            helper.fixture(root)  # Includes unrelated ambiguous declarations: they must not be scanned.
            counts = {}
            for n, name in enumerate(recorder.ROOTLESS_COMMIT_PLATFORM_CLASSES):
                package, _, short = name.rpartition(".")
                path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + package + "\nclass " + short + " : TestCase() {\n" +
                    "".join(" fun testCase" + str(i) + "() {}\n" for i in range(n + 1)) + "}\n")
                counts[name] = n + 1
            selected, empty = [], 0
            raw = ",".join(reversed(recorder.ROOTLESS_COMMIT_PLATFORM_CLASSES))
            for shard in range(4):
                result = helper.select(root, shard, classes=raw)
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual(counts, inventory["all_classes"])
                self.assertEqual(sum(counts[n] for n in selection["selected_classes"]), selection["expected_tests"])
                selected.extend(selection["selected_classes"])
                empty += selection["status"] == "filtered-empty-shard"
            self.assertCountEqual(counts, selected)
            self.assertEqual(2, empty)
            path.unlink()
            self.assertNotEqual(0, helper.select(root, classes=raw).returncode)

    def test_pair_receipt_preserves_real_failure_and_check_identity(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="f" * 40, GITHUB_RUN_ID="23", GITHUB_RUN_ATTEMPT="1",
                    SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(recorder.ROOTLESS_COMMIT_PLATFORM_CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=9) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record("python3"))
                    result = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("rootless-commit-targeted", result["scope"])
                    self.assertEqual(9, result["exit_code"])
                    self.assertEqual(dict(sha="f" * 40, run_id=23, attempt=1), result["identity"])
                    self.assertEqual(call.call_args.args[0], result["command"])
            finally:
                os.chdir(old)


class ChildTaskCardScopeTest(unittest.TestCase):
    CLASSES = tuple("app.daiyu.android.ui.screens.chat.conversation." + name for name in (
        "ChildTaskListTest", "ToolOperationCardTest", "ToolCardReadabilityTest", "SubAgentStatusDialogTest",
    ))
    MODULES = (
        "test_delegation_batch_and_send_contract", "test_child_stop_hot_configuration_contract",
        "test_subagent_resume_display_contract", "test_subagent_collaboration_contract",
        "test_sidebar_task_entry_contract", "test_child_task_card_contract", "test_chat_return_refresh_contract",
        "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
        "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
    )

    def test_exact_four_classes_keep_business_contracts_and_selector_guards(self):
        self.assertEqual(self.CLASSES, recorder.CHILD_TASK_CARD_PLATFORM_CLASSES)
        self.assertEqual(self.MODULES, recorder.CHILD_TASK_CARD_MODULES)
        for classes in itertools.permutations(self.CLASSES):
            for python in ("python", "python3"):
                command, scope = recorder.command_for(python, dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
                self.assertEqual("child-task-cards-targeted", scope)
                self.assertEqual([python, "-B", "-m", "unittest", *self.MODULES, "-v"], command)

    def test_exact_cards_plus_new_conversation_inheritance_stays_targeted(self):
        classes = (*self.CLASSES, "app.daiyu.android.ConversationSelectionInheritanceActivityTest")
        self.assertEqual(classes, recorder.CHILD_TASK_CARD_INHERITANCE_CLASSES)
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(reversed(classes))))
        self.assertEqual("child-task-cards-targeted", scope)
        self.assertEqual(["python3", "-B", "-m", "unittest", *self.MODULES, "-v"], command)
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper.fixture(root)
            for name in classes:
                relative = Path("app/src/androidTest/kotlin") / (name.replace(".", "/") + ".kt")
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text((ROOT / relative).read_text())
            seen = []
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(classes))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertCountEqual(classes, inventory["all_classes"])
                seen.extend(selection["selected_classes"])
            self.assertCountEqual(classes, seen)

    def test_partial_extended_duplicate_malformed_and_other_modes_keep_full(self):
        raw = ",".join(self.CLASSES)
        invalid = [",".join(classes) for size in range(4) for classes in itertools.combinations(self.CLASSES, size)]
        invalid += [raw + ",unknown", raw + "," + recorder.SPEECH_PLATFORM_CLASS, " " + raw, raw + " ", raw + ",", raw.replace(",", ", ", 1)]
        invalid += [raw + "," + name for name in self.CLASSES]
        invalid += [raw.replace(name, "app.daiyu.android.OtherTest") for name in self.CLASSES]
        for classes in invalid:
            self.assertEqual((["python", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full"),
                             recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes)))
        for mode in ("", "build", "compile", "candidate", "daily", "release", "checks"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])
        self.assertEqual("full", recorder.command_for("python", dict(SMOKE_TEST_CLASSES=raw))[1])

    def test_four_class_discovery_is_isolated_and_allocated_once(self):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper.fixture(root)
            counts = {}
            for n, name in enumerate(self.CLASSES):
                path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + name.rpartition(".")[0] + "\nclass " + name.rpartition(".")[2] + " : TestCase() {\n" +
                                "".join(" fun testCase" + str(i) + "() {}\n" for i in range(n + 1)) + "}\n")
                counts[name] = n + 1
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(reversed(self.CLASSES)))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual(counts, inventory["all_classes"])
                self.assertEqual(sum(counts[name] for name in selection["selected_classes"]), selection["expected_tests"])
                selected.extend(selection["selected_classes"])
            self.assertCountEqual(self.CLASSES, selected)
            for invalid in (",".join(self.CLASSES[:-1]), ",".join((*self.CLASSES, self.CLASSES[0])), ",".join((*self.CLASSES, "unknown"))):
                self.assertNotEqual(0, helper.select(root, classes=invalid).returncode)
            self.assertNotEqual(0, helper.select(root, classes=",".join(self.CLASSES), mode="candidate").returncode)
            path.unlink()
            self.assertNotEqual(0, helper.select(root, classes=",".join(self.CLASSES)).returncode)

    def test_actual_card_sources_are_discovered_without_unrelated_inventory(self):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper.fixture(root)
            for name in self.CLASSES:
                relative = Path("app/src/androidTest/kotlin") / (name.replace(".", "/") + ".kt")
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text((ROOT / relative).read_text())
            result = helper.select(root, classes=",".join(self.CLASSES))
            self.assertEqual(0, result.returncode, result.stderr)
            inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
            self.assertEqual("targeted", inventory["discovery_scope"])
            self.assertCountEqual(self.CLASSES, inventory["all_classes"])
            self.assertTrue(all(count > 0 for count in inventory["all_classes"].values()))

    def test_card_receipt_keeps_real_failure_identity_command_and_pythonpath(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="c" * 40, GITHUB_RUN_ID="31", GITHUB_RUN_ATTEMPT="2", SMOKE_MODE="smoke",
                           SMOKE_TEST_CLASSES=",".join(self.CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=9) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("child-task-cards-targeted", receipt["scope"])
                    self.assertEqual(9, receipt["exit_code"])
                    self.assertEqual(dict(sha="c" * 40, run_id=31, attempt=2), receipt["identity"])
                    self.assertEqual(["python3", "-B", "-m", "unittest", *self.MODULES, "-v"], receipt["command"])
                    call.assert_called_once()
                    self.assertEqual(receipt["command"], call.call_args.args[0])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)
