"""Producer tests use mocked children, never execute the unittest command."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "tool_receipt_producer", ROOT / "scripts/acceptance/record_tool_tests.py")
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)


class ChildStopReceiptScopeTest(unittest.TestCase):
    def test_stop_scope_is_explicit_and_preserves_single_source_gate(self):
        classes = "app.daiyu.android.ChildStopChoiceActivityTest,app.daiyu.android.ControllerChildRetentionTest"
        command, scope = producer.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))
        self.assertEqual("child-stop-targeted", scope)
        self.assertNotIn("discover", command)
        self.assertIn("test_run_controls_contract", command)
        self.assertIn("test_child_stop_hot_configuration_contract", command)
        self.assertEqual(1, command.count("test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope"))


    def test_frozen_stop_scope_checks_current_tools_without_rebuilding_apk(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                for code in (0, 3):
                    env = dict(GITHUB_SHA="b" * 40, GITHUB_RUN_ID="43", GITHUB_RUN_ATTEMPT="2",
                               EXPECTED_SHA="a" * 40, SMOKE_MODE="smoke",
                               SMOKE_TEST_CLASSES=",".join(producer.CHILD_STOP_PLATFORM_CLASSES))
                    def child(command, stdout, stderr, env=None, cwd=None):
                        self.assertEqual(ROOT, cwd)
                        self.assertTrue(env["PYTHONPATH"].startswith(str(ROOT / "tests")))
                        self.assertNotIn("discover", command)
                        stdout.write(b"frozen stop scope raw output\n")
                        return code
                    console = io.BytesIO()
                    with patch.dict(os.environ, env, clear=True), \
                            patch.object(producer.subprocess, "check_output", return_value="a" * 40 + "\n"), \
                            patch.object(producer.subprocess, "call", side_effect=child), \
                            patch.object(producer.sys, "stdout", SimpleNamespace(buffer=console)):
                        self.assertEqual(code, producer.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual(code, receipt["exit_code"])
                    self.assertEqual("a" * 40, receipt["identity"]["sha"])
                    self.assertEqual("b" * 40, receipt["validation_tools_sha"])
                    self.assertEqual("child-stop-targeted", receipt["scope"])
                    self.assertEqual(b"frozen stop scope raw output\n", Path("tool-evidence/unittest.log").read_bytes())
            finally:
                os.chdir(previous)


PROVIDER_SAVE_CLASSES = (
    "app.daiyu.android.ProviderSavePositionActivityTest",
    "app.daiyu.android.ControllerProviderSaveNoticeTest",
    "app.daiyu.android.ControllerModelSettingsTest",
    "app.daiyu.android.ui.screens.settings.ProviderInlineEditorsTest",
    "app.daiyu.android.ui.screens.settings.ProviderSaveValidationTest",
    "app.daiyu.android.RequestBodyFieldsActivityTest",
)
PROVIDER_SAVE_CHECKS = (
    "test_provider_save_position_contract", "test_provider_save_validation_contract",
    "test_provider_search_save_contract", "test_provider_directory_selection_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


class ToolReceiptTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "tool-evidence"
        self.env = dict(GITHUB_SHA="b" * 40, GITHUB_RUN_ID="43", GITHUB_RUN_ATTEMPT="2")

    def test_exact_command_identity_raw_bytes_and_real_exit_for_both_interpreters(self):
        raw = b"testExample (fixture.Suite.testExample) ... FAIL\r\nraw stderr\x00\n"
        for python in ("python", "python3"):
            for code in (0, 1, 7):
                with self.subTest(python=python, code=code):
                    def child(command, stdout, stderr, env=None):
                        self.assertEqual(command, [python, "-B", "-m", "unittest", "discover", "-s", "tests", "-v"])
                        self.assertEqual(stderr, subprocess.STDOUT)
                        self.assertIsNone(env)
                        stdout.write(raw)
                        return code
                    console = io.BytesIO()
                    with patch.dict(os.environ, self.env, clear=True), patch.object(producer, "Path", return_value=self.root), \
                            patch.object(producer.subprocess, "call", side_effect=child), \
                            patch.object(producer.sys, "stdout", SimpleNamespace(buffer=console)):
                        self.assertEqual(producer.main(["--python", python]), code)
                    receipt = json.loads((self.root / "receipt.json").read_text(encoding="utf-8"))
                    self.assertEqual(receipt, dict(identity=dict(sha="b" * 40, run_id=43, attempt=2),
                        command=[python, "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], exit_code=code))
                    self.assertEqual((self.root / "unittest.log").read_bytes(), raw)
                    self.assertEqual(console.getvalue(), raw)

    def test_interruption_or_spawn_failure_cannot_leave_a_success_receipt(self):
        for error in (OSError("synthetic spawn failure"), KeyboardInterrupt()):
            with self.subTest(error=type(error).__name__):
                self.root.mkdir(exist_ok=True)
                (self.root / "receipt.json").write_text('{"exit_code":0}')
                with patch.dict(os.environ, self.env, clear=True), patch.object(producer, "Path", return_value=self.root), \
                        patch.object(producer.subprocess, "call", side_effect=error):
                    with self.assertRaises(type(error)):
                        producer.record("python3")
                self.assertFalse((self.root / "receipt.json").exists())

    def test_workflow_wires_current_check_tools_and_keeps_smoke_exit_missing_on_timeout(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        checks = workflow.split("  checks:\n", 1)[1].split("  build:\n", 1)[0]
        build = workflow.split("  build:\n", 1)[1].split("  smoke:\n", 1)[0]
        smoke = workflow.split("  smoke:\n", 1)[1]
        self.assertIn("python scripts/acceptance/record_tool_tests.py --python python", checks)
        self.assertIn("python3 scripts/acceptance/record_tool_tests.py --python python3", build)
        self.assertNotIn("unittest discover -s tests -q", workflow)
        for job in (checks, build):
            self.assertIn("tool-evidence/", job)
            self.assertIn("if: always()", job)
        self.assertIn("if: inputs.mode == 'smoke'", smoke)
        self.assertIn("python3 .smoke-validation-tools/scripts/acceptance/record_tool_tests.py --python python3", smoke)
        self.assertIn("代鱼工具证据-smoke-API${{ matrix.api }}", smoke)
        self.assertIn("path: tool-evidence/", smoke)
        self.assertIn('code=$?; printf "%s\\n" "$code" > smoke/exit-code.txt; exit "$code"', smoke)
        self.assertNotIn("echo 0", smoke)
        self.assertIn('script: bash -c \'set +e; smoke_script=scripts/smoke_boot.sh; if test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "app.daiyu.android.SearchHistoryDialogTest,app.daiyu.android.SearchHostActivityTest,app.daiyu.android.RetainedSearchDataTest,app.daiyu.android.SearchSQLiteCancellationTest"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi; if test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "app.daiyu.android.GenerationInputImportTest,app.daiyu.android.storage.SQLiteGenerationAttemptStoreTest,app.daiyu.android.storage.SQLiteGenerationOutputStoreTest,app.daiyu.android.integration.tools.RunGenerationToolsDeviceTest,app.daiyu.android.ControllerGenerationResourcesTest,app.daiyu.android.ControllerGenerationIntegrationTest,app.daiyu.android.storage.GenerationProtocolMigrationTest"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi; if test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "app.daiyu.android.ui.screens.chat.conversation.ChildTaskListTest,app.daiyu.android.ChatThemeInteractionActivityTest,app.daiyu.android.ui.screens.chat.conversation.WorkflowDisclosureDirectionTest"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi; if test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "app.daiyu.android.ChildStopChoiceActivityTest,app.daiyu.android.ControllerChildRetentionTest"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi; if test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "app.daiyu.android.ui.screens.chat.conversation.ChatMessageSpacingTest,app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningTypingTest,app.daiyu.android.ui.screens.chat.conversation.MarkdownRevealTraversalTest"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi; test -s smoke/prelaunch-ready.txt && bash "$smoke_script"; code=$?; printf "%s\\n" "$code" > smoke/exit-code.txt; exit "$code"\'', smoke)
        self.assertIn("name: 上传启动证据\n        if: always()", smoke)
        self.assertNotIn("final_delivery.py", workflow)


    def test_exact_gui_parser_scope_keeps_explicit_source_and_receipt_checks(self):
        modules = ["test_record_tool_tests_scope", "test_acceptance_tool_receipt",
                   "test_gui_parser_smoke_scope",
                   "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope"]
        for python in ("python", "python3"):
            command, scope = producer.command_for(python, dict(SMOKE_MODE="smoke",
                SMOKE_TEST_CLASSES="app.daiyu.android.gui.GuiVisualWindowParserAndroidTest"))
            self.assertEqual(scope, "gui-parser-targeted")
            self.assertEqual(command, [python, "-B", "-m", "unittest", *modules, "-v"])

    def test_gui_parser_mixed_duplicate_similar_and_other_modes_keep_full_checks(self):
        cls = "app.daiyu.android.gui.GuiVisualWindowParserAndroidTest"
        other = "app.daiyu.android.OtherTest"
        invalid = ("", other, cls + "," + other, other + "," + cls, cls + "," + cls,
                   " " + cls, cls + " ", cls + "Extra", "prefix." + cls)
        environments = [dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value) for value in invalid]
        environments += [dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=cls)
                         for mode in ("", "build", "compile", "candidate", "daily", "release", "checks")]
        environments += [dict(SMOKE_TEST_CLASSES=cls), dict(SMOKE_MODE="smoke")]
        for env in environments:
            self.assertEqual(producer.command_for("python3", env),
                (["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full"))

    def test_gui_parser_receipt_preserves_raw_bytes_real_exit_and_identity(self):
        raw = b"synthetic FAIL\r\nraw stderr\x00\n"
        for python in ("python", "python3"):
            for code in (0, 1, 7):
                env = dict(self.env, SMOKE_MODE="smoke",
                           SMOKE_TEST_CLASSES="app.daiyu.android.gui.GuiVisualWindowParserAndroidTest")
                expected, _ = producer.command_for(python, env)
                def child(command, stdout, stderr, env=None):
                    self.assertEqual(command, expected)
                    self.assertEqual(stderr, subprocess.STDOUT)
                    self.assertTrue(env["PYTHONPATH"].startswith(str((ROOT / "tests").resolve())))
                    stdout.write(raw)
                    return code
                console = io.BytesIO()
                with patch.dict(os.environ, env, clear=True), \
                        patch.object(producer, "Path", side_effect=lambda value: self.root if value == "tool-evidence" else Path(value)), \
                        patch.object(producer.subprocess, "call", side_effect=child), \
                        patch.object(producer.sys, "stdout", SimpleNamespace(buffer=console)):
                    self.assertEqual(producer.record(python), code)
                receipt = json.loads((self.root / "receipt.json").read_text())
                self.assertEqual(receipt, dict(identity=dict(sha="b" * 40, run_id=43, attempt=2),
                    command=expected, exit_code=code, scope="gui-parser-targeted"))
                self.assertEqual((self.root / "unittest.log").read_bytes(), raw)
                self.assertEqual(console.getvalue(), raw)

    def test_gui_parser_interruption_cannot_leave_a_stale_success_receipt(self):
        for failure in (OSError("fixture"), KeyboardInterrupt()):
            self.root.mkdir(exist_ok=True)
            (self.root / "receipt.json").write_text('{"exit_code":0}')
            env = dict(self.env, SMOKE_MODE="smoke",
                       SMOKE_TEST_CLASSES="app.daiyu.android.gui.GuiVisualWindowParserAndroidTest")
            with patch.dict(os.environ, env, clear=True), patch.object(producer, "Path", return_value=self.root), \
                    patch.object(producer.subprocess, "call", side_effect=failure):
                with self.assertRaises(type(failure)):
                    producer.record("python3")
            self.assertFalse((self.root / "receipt.json").exists())

    def test_exact_provider_save_scope_preserves_all_four_business_contracts(self):
        self.assertEqual(producer.PROVIDER_SAVE_PLATFORM_CLASSES, PROVIDER_SAVE_CLASSES)
        self.assertEqual(producer.PROVIDER_SAVE_MODULES, PROVIDER_SAVE_CHECKS)
        for python in ("python", "python3"):
            for names in (PROVIDER_SAVE_CLASSES, tuple(reversed(PROVIDER_SAVE_CLASSES))):
                command, scope = producer.command_for(python, dict(SMOKE_MODE="smoke",
                    SMOKE_TEST_CLASSES=",".join(names)))
                self.assertEqual(scope, "provider-save-targeted")
                self.assertEqual(command, [python, "-B", "-m", "unittest", *PROVIDER_SAVE_CHECKS, "-v"])
                self.assertNotIn("discover", command)

    def test_inexact_provider_save_selectors_and_other_modes_keep_full_checks(self):
        raw = ",".join(PROVIDER_SAVE_CLASSES)
        invalid = ["", raw + ",", " " + raw, raw + " ", raw + ",app.daiyu.android.OtherTest"]
        invalid += [",".join(PROVIDER_SAVE_CLASSES[:i] + PROVIDER_SAVE_CLASSES[i + 1:])
                    for i in range(len(PROVIDER_SAVE_CLASSES))]
        invalid += [raw + "," + name for name in PROVIDER_SAVE_CLASSES]
        invalid += [raw.replace(name, name + "Extra") for name in PROVIDER_SAVE_CLASSES]
        environments = [dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value) for value in invalid]
        environments += [dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw)
                         for mode in ("", "build", "compile", "candidate", "daily", "release", "checks")]
        environments += [dict(SMOKE_TEST_CLASSES=raw), dict(SMOKE_MODE="smoke")]
        for env in environments:
            with self.subTest(env=env):
                self.assertEqual(producer.command_for("python3", env),
                    (["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full"))

    def test_provider_save_receipt_keeps_frozen_source_tool_identity_and_real_exit(self):
        raw = b"provider fixture FAIL\r\nraw stderr\x00\n"
        for python in ("python", "python3"):
            for code in (0, 1, 7):
                env = dict(self.env, SMOKE_MODE="smoke", EXPECTED_SHA="a" * 40,
                           SMOKE_TEST_CLASSES=",".join(PROVIDER_SAVE_CLASSES))
                expected = [python, "-B", "-m", "unittest", *PROVIDER_SAVE_CHECKS, "-v"]
                def child(command, stdout, stderr, env=None):
                    self.assertEqual(command, expected)
                    self.assertEqual(stderr, subprocess.STDOUT)
                    self.assertTrue(env["PYTHONPATH"].startswith(str((ROOT / "tests").resolve())))
                    stdout.write(raw)
                    return code
                console = io.BytesIO()
                with patch.dict(os.environ, env, clear=True), \
                        patch.object(producer, "Path", side_effect=lambda value: self.root if value == "tool-evidence" else Path(value)), \
                        patch.object(producer.subprocess, "check_output", return_value="a" * 40 + "\n"), \
                        patch.object(producer.subprocess, "call", side_effect=child), \
                        patch.object(producer.sys, "stdout", SimpleNamespace(buffer=console)):
                    self.assertEqual(producer.record(python), code)
                receipt = json.loads((self.root / "receipt.json").read_text())
                self.assertEqual(receipt, dict(identity=dict(sha="a" * 40, run_id=43, attempt=2),
                    workflow_sha="b" * 40, validation_tools_sha="b" * 40,
                    command=expected, exit_code=code, scope="provider-save-targeted"))
                self.assertEqual((self.root / "unittest.log").read_bytes(), raw)
                self.assertEqual(console.getvalue(), raw)

    def test_provider_save_interruption_cannot_retain_a_success_receipt(self):
        for failure in (OSError("provider fixture"), KeyboardInterrupt()):
            self.root.mkdir(exist_ok=True)
            (self.root / "receipt.json").write_text('{"exit_code":0}')
            env = dict(self.env, SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(PROVIDER_SAVE_CLASSES))
            with patch.dict(os.environ, env, clear=True), patch.object(producer, "Path", return_value=self.root), \
                    patch.object(producer.subprocess, "call", side_effect=failure):
                with self.assertRaises(type(failure)):
                    producer.record("python3")
            self.assertFalse((self.root / "receipt.json").exists())
