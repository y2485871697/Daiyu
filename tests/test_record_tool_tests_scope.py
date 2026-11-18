import importlib.util
import io
import itertools
import json
import os
import subprocess
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("speech_test_recorder", ROOT / "scripts/acceptance/record_tool_tests.py")
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class ErrorDetailsToolTestsScopeTest(unittest.TestCase):
    CLASSES = ('app.daiyu.android.PlatformTest', 'app.daiyu.android.ControllerReconnectIntegrationTest', 'app.daiyu.android.ui.screens.chat.conversation.ChatTimelineDividerTest', 'app.daiyu.android.ui.screens.chat.conversation.ReconnectBoundaryPlacementTest', 'app.daiyu.android.storage.SQLiteChildTaskStoreTest')
    MODULES = ('test_error_details_smoke_scope', 'test_chat_timeline_contract', 'test_reasoning_flow_stability_contract', 'test_platform_smoke_scope', 'test_test_refresh_contract', 'test_record_tool_tests_scope', 'test_acceptance_tool_receipt', 'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope')

    def test_exact_permutations_keep_only_explicit_contracts(self):
        self.assertEqual(self.CLASSES, recorder.ERROR_DETAILS_PLATFORM_CLASSES)
        self.assertEqual(self.MODULES, recorder.ERROR_DETAILS_MODULES)
        for selected in itertools.permutations(self.CLASSES):
            command, scope = recorder.command_for("python3", dict(
                SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))
            self.assertEqual("error-details-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *self.MODULES, "-v"], command)
            self.assertNotIn("discover", command)

    def test_inexact_and_other_modes_keep_full_fallback(self):
        raw = ",".join(self.CLASSES)
        invalid = ["", "unknown", " " + raw, raw + " ", raw + ",", "," + raw,
                   raw + ",app.daiyu.android.OtherTest"]
        invalid += [",".join(self.CLASSES[:i] + self.CLASSES[i + 1:])
                    for i in range(len(self.CLASSES))]
        invalid += [raw + "," + name for name in self.CLASSES]
        full = (["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full")
        for selected in invalid:
            self.assertEqual(full, recorder.command_for("python3", dict(
                SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=selected)))
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual(full, recorder.command_for("python3", dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw)))
        for target, scope in ((self.CLASSES[0], "platform-targeted"),
                              (self.CLASSES[2], "timeline-divider-targeted")):
            self.assertEqual(scope, recorder.command_for("python3", dict(
                SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=target))[1])

    def test_receipt_keeps_exit_code_identity_and_frozen_tool_cwd(self):
        old = Path.cwd()
        for code in (0, 9):
            with tempfile.TemporaryDirectory() as directory:
                try:
                    os.chdir(directory)
                    env = dict(GITHUB_SHA="b" * 40, EXPECTED_SHA="a" * 40,
                               GITHUB_RUN_ID="42", GITHUB_RUN_ATTEMPT="1", SMOKE_MODE="smoke",
                               SMOKE_TEST_CLASSES=",".join(self.CLASSES))
                    with patch.dict(os.environ, env, clear=True), patch.object(
                            recorder.subprocess, "check_output", return_value="a" * 40 + "\n"), patch.object(
                            recorder.subprocess, "call", return_value=code) as call, patch.object(
                            recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                        self.assertEqual(code, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual(dict(sha="a" * 40, run_id=42, attempt=1), receipt["identity"])
                    self.assertEqual("error-details-targeted", receipt["scope"])
                    self.assertEqual(code, receipt["exit_code"])
                    self.assertEqual("b" * 40, receipt["validation_tools_sha"])
                    call.assert_called_once()
                    self.assertEqual(receipt["command"], call.call_args.args[0])
                    base = Path(recorder.__file__).resolve().parents[2]
                    self.assertEqual(base, call.call_args.kwargs["cwd"])
                    self.assertEqual(str(base / "tests") + os.pathsep,
                                     call.call_args.kwargs["env"]["PYTHONPATH"])
                finally:
                    os.chdir(old)


class ReceiptSummaryToolTestsScopeTest(unittest.TestCase):
    def test_exact_filter_permutations_keep_all_required_contracts(self):
        classes = recorder.RECEIPT_SUMMARY_PLATFORM_CLASSES
        self.assertEqual(("app.daiyu.android.ToolReceiptPauseActivityTest",
                          "app.daiyu.android.ui.screens.chat.conversation.ToolCardReadabilityTest",
                          "app.daiyu.android.ui.screens.chat.conversation.ReasoningWorkflowTest"), classes)
        modules = tuple(dict.fromkeys(recorder.PAUSE_RECEIPT_MODULES + (
            "test_reasoning_workflow_contract", "test_tool_card_html_contract", "test_streaming_freeze_contract")))
        self.assertEqual(modules, recorder.RECEIPT_SUMMARY_MODULES)
        for selected in itertools.permutations(classes):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))
            self.assertEqual("receipt-summary-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *modules, "-v"], command)

    def test_other_filters_modes_and_existing_scopes_remain_unchanged(self):
        classes = recorder.RECEIPT_SUMMARY_PLATFORM_CLASSES
        raw = ",".join(classes)
        for selected in ("", ",".join(classes[:-1]), raw + ",", " " + raw, raw + " ",
                         raw + "," + classes[0], raw + ",app.daiyu.android.OtherTest"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=selected))[1])
        for mode in ("", "compile", "candidate", "daily", "release", "checks", "build"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])
        for prefix, scope in (("PAUSE_RECEIPT", "pause-receipts-targeted"), ("PANE_RECEIPT", "pane-receipts-targeted")):
            command, actual = recorder.command_for("python3", dict(SMOKE_MODE="smoke",
                SMOKE_TEST_CLASSES=",".join(getattr(recorder, prefix + "_PLATFORM_CLASSES"))))
            self.assertEqual(scope, actual)
            self.assertEqual(["python3", "-B", "-m", "unittest", *getattr(recorder, prefix + "_MODULES"), "-v"], command)

    def test_receipt_keeps_exit_code_identity_and_frozen_tool_cwd(self):
        old = Path.cwd()
        for code in (0, 9):
            with tempfile.TemporaryDirectory() as directory:
                try:
                    os.chdir(directory)
                    env = dict(GITHUB_SHA="b" * 40, EXPECTED_SHA="a" * 40, GITHUB_RUN_ID="42",
                        GITHUB_RUN_ATTEMPT="1", SMOKE_MODE="smoke",
                        SMOKE_TEST_CLASSES=",".join(recorder.RECEIPT_SUMMARY_PLATFORM_CLASSES))
                    with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess,
                            "check_output", return_value="a" * 40 + "\n"), patch.object(recorder.subprocess,
                            "call", return_value=code) as call, patch.object(recorder.sys,
                            "stdout", SimpleNamespace(buffer=io.BytesIO())):
                        self.assertEqual(code, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual(dict(sha="a" * 40, run_id=42, attempt=1), receipt["identity"])
                    self.assertEqual("b" * 40, receipt["workflow_sha"])
                    self.assertEqual("b" * 40, receipt["validation_tools_sha"])
                    self.assertEqual("receipt-summary-targeted", receipt["scope"])
                    self.assertEqual(code, receipt["exit_code"])
                    command = ["python3", "-B", "-m", "unittest", *recorder.RECEIPT_SUMMARY_MODULES, "-v"]
                    self.assertEqual(command, receipt["command"])
                    call.assert_called_once()
                    self.assertEqual(command, call.call_args.args[0])
                    root = Path(recorder.__file__).resolve().parents[2]
                    self.assertEqual(root, call.call_args.kwargs["cwd"])
                    self.assertEqual(str(root / "tests") + os.pathsep, call.call_args.kwargs["env"]["PYTHONPATH"])
                finally:
                    os.chdir(old)


class VideoPickerToolTestsScopeTest(unittest.TestCase):
    CLASSES = ('app.daiyu.android.TextSendActivityTest', 'app.daiyu.android.ChatReferenceEntryActivityTest', 'app.daiyu.android.ui.screens.chat.attachments.VideoAttachmentsUiTest')
    MODULES = ('test_media_send_contract', 'test_image_sidebar_visibility_contract', 'test_message_composer_edit_contract', 'test_tool_receipt_snapshot_contract', 'test_tool_receipt_pause_contract', 'test_chat_submission_preview_contract', 'test_composer_surface_color_contract', 'test_compression_send_gate_contract', 'test_delegation_batch_and_send_contract', 'test_full_regression_event_boundaries_contract.FullRegressionEventBoundariesContractTest.test_video_result_waits_for_exactly_one_picker_launch', 'test_full_regression_event_boundaries_contract.FullRegressionEventBoundariesContractTest.test_first_text_video_menu_advances_compose_before_native_picker_waits', 'test_candidate_window_publication_contract.CandidateWindowPublicationContract.test_video_disposes_only_its_own_publication_and_keeps_callback_fence', 'test_test_refresh_contract', 'test_record_tool_tests_scope', 'test_acceptance_tool_receipt', 'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope')

    def test_exact_permutations_keep_only_explicit_contracts(self):
        self.assertEqual(self.CLASSES, recorder.VIDEO_PICKER_PLATFORM_CLASSES)
        self.assertEqual(self.MODULES, recorder.VIDEO_PICKER_MODULES)
        for selected in itertools.permutations(self.CLASSES):
            command, scope = recorder.command_for("python3", dict(
                SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))
            self.assertEqual("video-picker-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *self.MODULES, "-v"], command)
            self.assertNotIn("discover", command)

    def test_inexact_and_other_modes_keep_full_fallback(self):
        raw = ",".join(self.CLASSES)
        invalid = ["", "unknown", " " + raw, raw + " ", raw + ",", "," + raw,
                   raw + ",app.daiyu.android.OtherTest"]
        invalid += [",".join(self.CLASSES[:i] + self.CLASSES[i + 1:])
                    for i in range(len(self.CLASSES))]
        invalid += [raw + "," + name for name in self.CLASSES]
        full = (["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full")
        for selected in invalid:
            self.assertEqual(full, recorder.command_for("python3", dict(
                SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=selected)))
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual(full, recorder.command_for("python3", dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw)))
        for target, scope in ((recorder.MEDIA_SEND_PLATFORM_CLASSES, "media-send-targeted"),
                              (recorder.USER_MESSAGE_ENTRANCE_PLATFORM_CLASSES, "user-message-entrance-targeted")):
            self.assertEqual(scope, recorder.command_for("python3", dict(
                SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(target)))[1])

    def test_receipt_keeps_exit_code_identity_and_frozen_tool_cwd(self):
        old = Path.cwd()
        for code in (0, 9):
            with tempfile.TemporaryDirectory() as directory:
                try:
                    os.chdir(directory)
                    env = dict(GITHUB_SHA="b" * 40, EXPECTED_SHA="a" * 40,
                               GITHUB_RUN_ID="42", GITHUB_RUN_ATTEMPT="1", SMOKE_MODE="smoke",
                               SMOKE_TEST_CLASSES=",".join(self.CLASSES))
                    with patch.dict(os.environ, env, clear=True), patch.object(
                            recorder.subprocess, "check_output", return_value="a" * 40 + "\n"), patch.object(
                            recorder.subprocess, "call", return_value=code) as call, patch.object(
                            recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                        self.assertEqual(code, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual(dict(sha="a" * 40, run_id=42, attempt=1), receipt["identity"])
                    self.assertEqual("video-picker-targeted", receipt["scope"])
                    self.assertEqual(code, receipt["exit_code"])
                    self.assertEqual("b" * 40, receipt["validation_tools_sha"])
                    call.assert_called_once()
                    self.assertEqual(receipt["command"], call.call_args.args[0])
                    base = Path(recorder.__file__).resolve().parents[2]
                    self.assertEqual(base, call.call_args.kwargs["cwd"])
                    self.assertEqual(str(base / "tests") + os.pathsep,
                                     call.call_args.kwargs["env"]["PYTHONPATH"])
                finally:
                    os.chdir(old)



class RecordToolTestsScopeTest(unittest.TestCase):

    def test_media_send_scope_is_exact_and_preserves_full_fallback(self):
        classes = recorder.MEDIA_SEND_PLATFORM_CLASSES
        self.assertEqual(7, len(classes))
        for selected in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))
            self.assertEqual("media-send-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.MEDIA_SEND_MODULES, "-v"], command)
        for selected in (classes[:-1], (classes[0],), (*classes, classes[0]), (*classes, "app.daiyu.android.OtherTest")):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))[1])
        for mode in ("", "compile", "candidate", "daily", "release", "checks", "build"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=",".join(classes)))[1])

    def test_runtime_stop_scope_is_exact_and_preserves_other_coverage(self):
        classes = recorder.RUNTIME_STOP_PLATFORM_CLASSES
        self.assertEqual(("app.daiyu.android.terminal.TerminalRegistryTest",
                          "app.daiyu.android.ChildStopChoiceActivityTest",
                          "app.daiyu.android.ControllerChildRetentionTest"), classes)
        for selected in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))
            self.assertEqual("runtime-stop-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.RUNTIME_STOP_MODULES, "-v"], command)
        self.assertTrue(set(recorder.CHILD_STOP_MODULES) <= set(recorder.RUNTIME_STOP_MODULES))
        self.assertTrue({"test_terminal_job_status_contract", "test_terminal_reference_contract",
                         "test_chroot_workspace_command_contract", "test_terminal_command_pager_smoke_scope"} <= set(recorder.RUNTIME_STOP_MODULES))
        for selected in (classes[:-1], (classes[0],), (*classes, classes[0]), (*classes, "app.daiyu.android.OtherTest")):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))[1])
        for mode in ("", "compile", "candidate", "daily", "release", "checks", "build"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=",".join(classes)))[1])
        self.assertEqual("child-stop-targeted", recorder.command_for("python3", dict(
            SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(recorder.CHILD_STOP_PLATFORM_CLASSES)))[1])

    def test_timeline_divider_scope_is_exact_and_keeps_full_fallback(self):
        target = "app.daiyu.android.ui.screens.chat.conversation.ChatTimelineDividerTest"
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        concurrency = workflow.split("concurrency:\n", 1)[1].split("\njobs:", 1)[0]
        self.assertIn("group: daiyu-${{ inputs.mode }}-${{ github.ref }}", concurrency)
        self.assertIn("${{ inputs.mode == 'smoke' && inputs.smoke_classes == '" + target + "' && '-timeline-divider' || '' }}", concurrency)
        self.assertIn("cancel-in-progress: false", concurrency)
        modules = ("test_chat_timeline_contract", "test_reasoning_flow_stability_contract",
                   "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
                   "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope")
        for python in ("python", "python3"):
            command, scope = recorder.command_for(python, dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=target))
            self.assertEqual("timeline-divider-targeted", scope)
            self.assertEqual([python, "-B", "-m", "unittest", *modules, "-v"], command)
        for invalid in ("", target + ",", " " + target, target + " ", target + "," + target,
                        target + "Extra", target + ",app.daiyu.android.OtherTest"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=invalid))[1])
        for mode in ("", "compile", "candidate", "daily", "release", "checks", "build"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=target))[1])

    def test_workspace_gesture_scope_is_exact_and_keeps_full_fallback(self):
        target = "app.daiyu.android.ConversationWorkspaceActivityTest"
        command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=target))
        self.assertEqual("workspace-gesture-targeted", scope)
        self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.WORKSPACE_GESTURE_MODULES, "-v"], command)
        self.assertEqual(("test_workspace_picker_contract", "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
                          "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope"),
                         recorder.WORKSPACE_GESTURE_MODULES)
        for invalid in ("", target + ",", " " + target, target + " ", target + "," + target,
                        target + ",app.daiyu.android.OtherTest"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=invalid))[1])
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=target))[1])

    def test_workflow_node_motion_scope_is_exact_and_retains_full_fallback(self):
        classes = recorder.WORKFLOW_NODE_MOTION_PLATFORM_CLASSES
        self.assertEqual(classes, (
            "app.daiyu.android.ui.screens.chat.conversation.WorkflowNodeMotionTest",
            "app.daiyu.android.ui.screens.chat.conversation.WorkTimelineConnectorTest"))
        for selected in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))
            self.assertEqual("workflow-node-motion-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.WORKFLOW_NODE_MOTION_MODULES, "-v"], command)
        for selected in (classes[:-1], (*classes, classes[0]), (*classes, "app.daiyu.android.OtherTest")):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))[1])
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=",".join(classes)))[1])
        self.assertIn("test_reasoning_workflow_contract", recorder.WORKFLOW_NODE_MOTION_MODULES)
        self.assertIn("test_work_timeline_connector_contract", recorder.WORKFLOW_NODE_MOTION_MODULES)
        self.assertIn("test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope", recorder.WORKFLOW_NODE_MOTION_MODULES)

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

    def test_menu_navigation_scope_is_exact_and_keeps_the_original_menu_scope(self):
        classes = recorder.MENU_NAVIGATION_PLATFORM_CLASSES
        for selected in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))
            self.assertEqual("menu-navigation-targeted", scope)
            self.assertEqual(command, ["python3", "-B", "-m", "unittest", *recorder.MENU_NAVIGATION_MODULES, "-v"])
        self.assertEqual(set(classes), {"app.daiyu.android." + name for name in (
            "ChatMenuEntryActivityTest", "ModalRouteBackgroundActivityTest", "ChatChromeActivityTest", "ConversationPaneVisualTest")})
        for selected in (classes[:-1], (*classes, classes[0]), (*classes, "app.daiyu.android.OtherTest")):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected)))[1])
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        self.assertIn("inputs.smoke_classes == '" + ",".join(classes) + "' && '-menu-navigation'", workflow)
        script = (ROOT / "scripts/smoke_boot.sh").read_text()
        target = script.split('menu_navigation_target = {', 1)[1].split('speech_source =', 1)[0]
        for name in classes:
            self.assertIn(name.rsplit(".", 1)[1], target)
        self.assertIn('for name in (requested if menu_only else menu_target)', target)

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


class ChildStopScopeTest(unittest.TestCase):
    def test_exact_stop_and_retention_classes_keep_targeted_contracts(self):
        classes = ("app.daiyu.android.ChildStopChoiceActivityTest", "app.daiyu.android.ControllerChildRetentionTest")
        self.assertEqual(classes, recorder.CHILD_STOP_PLATFORM_CLASSES)
        modules = ("test_child_stop_hot_configuration_contract", "test_run_controls_contract",
                   "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
                   "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope")
        self.assertEqual(modules, recorder.CHILD_STOP_MODULES)
        for ordered in (classes, tuple(reversed(classes))):
            for python in ("python", "python3"):
                command, scope = recorder.command_for(python, dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(ordered)))
                self.assertEqual("child-stop-targeted", scope)
                self.assertEqual([python, "-B", "-m", "unittest", *modules, "-v"], command)
        raw = ",".join(classes)
        for value in (classes[0], raw + ",unknown", raw + "," + classes[0], raw + ",", " " + raw,
                      raw.replace("ChildStopChoiceActivityTest", "OtherTest")):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        for mode in ("compile", "candidate", "daily", "release", "checks"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])


    def test_child_stop_smoke_has_own_concurrency_without_cancelling_other_scopes(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        concurrency = workflow.split("concurrency:\n", 1)[1].split("jobs:\n", 1)[0]
        self.assertIn("inputs.smoke_classes == 'app.daiyu.android.ChildStopChoiceActivityTest,app.daiyu.android.ControllerChildRetentionTest' && '-child-stop'", concurrency)
        self.assertIn("cancel-in-progress: false", concurrency)

    def test_stop_selector_reads_only_exact_requested_files_and_keeps_empty_shards(self):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        classes = recorder.CHILD_STOP_PLATFORM_CLASSES
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
                result = helper.select(root, shard, classes=",".join(reversed(classes)))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertEqual({classes[0]: 8, classes[1]: 17}, inventory["all_classes"])
                seen.extend(selection["selected_classes"])
            self.assertCountEqual(classes, seen)
            for invalid in (classes[0], ",".join((*classes, classes[0])), ",".join((*classes, "unknown"))):
                self.assertNotEqual(0, helper.select(root, classes=invalid).returncode)
            (root / "app/src/androidTest/kotlin" / (classes[0].replace(".", "/") + ".kt")).unlink()
            self.assertNotEqual(0, helper.select(root, classes=",".join(classes)).returncode)


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

    def test_test_refresh_singleton_uses_one_complete_shard_only_for_explicit_zero(self):
        workflow = (ROOT / ".github/workflows/test-refresh-regression.yml").read_text()
        smoke = workflow.split("  smoke:", 1)[1]
        counts = [line.strip() for line in smoke.splitlines()
                  if line.strip().startswith("SMOKE_SHARD_COUNT:")]
        expected = "SMOKE_SHARD_COUNT: ${{ (inputs.smoke_classes == 'app.daiyu.android.VoiceCallFloatingWindowTest' || (inputs.smoke_classes == 'app.daiyu.android.ui.screens.chat.conversation.ToolOperationCardTest' && inputs.smoke_shard == '0')) && '1' || '4' }}"
        self.assertEqual([expected], counts)

    def test_only_tool_operation_card_keeps_exact_targeted_command(self):
        classes = "app.daiyu.android.ui.screens.chat.conversation.ToolOperationCardTest"
        for python in ("python", "python3"):
            command, scope = recorder.command_for(python, dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))
            self.assertEqual("child-task-cards-targeted", scope)
            self.assertEqual([python, "-B", "-m", "unittest", *self.MODULES, "-v"], command)
        for invalid in (classes + ",", " " + classes, classes + " ", classes + "," + classes,
                        classes + ",app.daiyu.android.OtherTest"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=invalid))[1])
        for mode in ("", "build", "compile", "candidate", "daily", "release", "checks"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=classes))[1])

    def test_task_status_scope_adds_storage_and_catalog_without_widening_old_scope(self):
        classes = (*self.CLASSES, "app.daiyu.android.ControllerChildModelsTest",
                   "app.daiyu.android.ControllerModelPreparationTest", "app.daiyu.android.storage.SQLiteChildTaskStoreTest")
        self.assertEqual(classes, recorder.CHILD_TASK_STATUS_PLATFORM_CLASSES)
        for ordered in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(ordered)))
            self.assertEqual("child-task-cards-targeted", scope)
            self.assertEqual(["python3", "-B", "-m", "unittest", *self.MODULES, "-v"], command)

    def test_exact_cards_plus_controller_keep_targeted_checks_and_reject_bad_filters(self):
        classes = (*self.CLASSES, "app.daiyu.android.ControllerChildModelsTest")
        self.assertEqual(classes, recorder.CHILD_DELEGATION_PLATFORM_CLASSES)
        for ordered in itertools.permutations(classes):
            command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(ordered)))
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
        raw = ",".join(classes)
        invalid = [raw + ",unknown", raw + ",", " " + raw, raw + " ", raw.replace(",", ", ", 1)]
        invalid += [raw + "," + name for name in classes]
        invalid += [raw.replace(name, "app.daiyu.android.OtherTest") for name in classes]
        for value in invalid:
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        for mode in ("", "build", "compile", "candidate", "daily", "release", "checks"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def test_exact_cards_plus_inheritance_and_theme_stay_targeted(self):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        for classes in (recorder.CHILD_TASK_CARD_INHERITANCE_CLASSES, recorder.CHILD_TASK_CARD_THEME_CLASSES):
            self.assertEqual((*self.CLASSES, "app.daiyu.android.ConversationSelectionInheritanceActivityTest"), classes[:5])
            self.assertEqual(len(classes), len(set(classes)))
            if len(classes) == 6:
                self.assertEqual("app.daiyu.android.ChatThemeInteractionActivityTest", classes[-1])
            for ordered in (classes, tuple(reversed(classes))):
                command, scope = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(ordered)))
                self.assertEqual("child-task-cards-targeted", scope)
                self.assertEqual(["python3", "-B", "-m", "unittest", *self.MODULES, "-v"], command)
                self.assertNotIn("discover", command)
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

    def test_six_class_duplicates_unknown_and_malformed_filters_still_keep_full(self):
        classes = recorder.CHILD_TASK_CARD_THEME_CLASSES
        raw = ",".join(classes)
        invalid = [raw + ",unknown", raw + ",", " " + raw, raw + " ", raw.replace(",", ", ", 1)]
        invalid += [raw + "," + name for name in classes]
        invalid += [raw.replace(name, "app.daiyu.android.OtherTest") for name in classes]
        for value in invalid:
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        for mode in ("", "build", "compile", "candidate", "daily", "release", "checks"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def test_partial_extended_duplicate_malformed_and_other_modes_keep_full(self):
        raw = ",".join(self.CLASSES)
        invalid = [",".join(classes) for size in range(4)
                   for classes in itertools.combinations(self.CLASSES, size)
                   if classes != ("app.daiyu.android.ui.screens.chat.conversation.ToolOperationCardTest",)]
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


class PopupScrollScopeTest(unittest.TestCase):
    CLASSES = ('app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest', 'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest', 'app.daiyu.android.PreviewDialogStatusBarActivityTest', 'app.daiyu.android.AssistantPickerAdmissionTest', 'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingFollowTest')
    MODULES = ('test_streaming_occlusion_contract', 'test_streaming_follow_contract', 'test_chat_resume_scroll_contract', 'test_preview_dialog_status_bar_contract', 'test_assistant_picker_frame_contract', 'test_chat_menu_scroll_isolation_contract', 'test_popup_performance_contract', 'test_record_tool_tests_scope', 'test_acceptance_tool_receipt', 'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope')

    def test_exact_scope_keeps_all_five_classes_and_only_explicit_contracts(self):
        self.assertEqual(self.CLASSES, recorder.POPUP_SCROLL_PLATFORM_CLASSES)
        self.assertEqual(self.MODULES, recorder.POPUP_SCROLL_MODULES)
        self.assertEqual(len(self.CLASSES), len(set(self.CLASSES)))
        for classes in itertools.permutations(self.CLASSES):
            for python in ("python", "python3"):
                command, scope = recorder.command_for(python, dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
                self.assertEqual("popup-scroll-targeted", scope)
                self.assertEqual([python, "-B", "-m", "unittest", *self.MODULES, "-v"], command)
                self.assertNotIn("discover", command)

    def test_exact_occlusion_singleton_keeps_task_scroll_contracts(self):
        target = "app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest"
        for python in ("python", "python3"):
            command, scope = recorder.command_for(python, dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=target))
            self.assertEqual("task-scroll-targeted", scope)
            self.assertEqual([python, "-B", "-m", "unittest", *recorder.TASK_SCROLL_MODULES, "-v"], command)
            self.assertNotIn("discover", command)

    def test_partial_duplicate_extended_or_other_modes_do_not_reduce_coverage(self):
        full = ",".join(self.CLASSES)
        invalid = [
            ",".join(group)
            for size in range(len(self.CLASSES))
            for group in itertools.combinations(self.CLASSES, size)
            if group != ("app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest",)
        ]
        invalid += [full + ",unknown", full + "," + self.CLASSES[0], " " + full, full + " ", full + ","]
        for classes in invalid:
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))[1])
        for mode in ("", "compile", "candidate", "daily", "release", "checks", "build"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=full))[1])

    def test_real_sources_are_targeted_and_allocated_once_across_all_shards(self):
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
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(self.CLASSES))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertCountEqual(self.CLASSES, inventory["all_classes"])
                self.assertTrue(all(count > 0 for count in inventory["all_classes"].values()))
                self.assertEqual(sum(inventory["all_classes"][name] for name in selection["selected_classes"]), selection["expected_tests"])
                selected.extend(selection["selected_classes"])
            self.assertCountEqual(self.CLASSES, selected)

    def test_receipt_preserves_actual_failure_and_source_identity(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="d" * 40, GITHUB_RUN_ID="42", GITHUB_RUN_ATTEMPT="1", SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(self.CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=9) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("popup-scroll-targeted", receipt["scope"])
                    self.assertEqual(9, receipt["exit_code"])
                    self.assertEqual(dict(sha="d" * 40, run_id=42, attempt=1), receipt["identity"])
                    self.assertEqual(["python3", "-B", "-m", "unittest", *self.MODULES, "-v"], receipt["command"])
                    call.assert_called_once()
                    self.assertEqual(receipt["command"], call.call_args.args[0])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)


class ScrollPopupScopeTest(unittest.TestCase):
    CLASSES = ('app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest', 'app.daiyu.android.ui.screens.chat.conversation.MessageMarkdownTest', 'app.daiyu.android.OrdinaryDialogLifecycleActivityTest', 'app.daiyu.android.ui.screens.chat.conversation.ChatModelPickerLayoutTest')
    MODULES = tuple(dict.fromkeys(recorder.POPUP_SCROLL_MODULES + recorder.RENDERING_MODULES + ('test_model_switch_feedback_contract', 'test_selection_stays_open_contract', 'test_model_picker_caption_contract', 'test_model_selection_switch_state_contract', 'test_voice_settings_parity_contract', 'test_chat_gpt_speed_contract', 'test_model_picker_balance_contract', 'test_conversation_model_binding_contract')))

    def test_exact_scope_keeps_all_four_classes_and_only_explicit_contracts(self):
        self.assertEqual(self.CLASSES, recorder.SCROLL_POPUP_PLATFORM_CLASSES)
        self.assertEqual(self.MODULES, recorder.SCROLL_POPUP_MODULES)
        self.assertEqual(len(self.CLASSES), len(set(self.CLASSES)))
        for classes in itertools.permutations(self.CLASSES):
            for python in ("python", "python3"):
                command, scope = recorder.command_for(python, dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
                self.assertEqual("scroll-popup-targeted", scope)
                self.assertEqual([python, "-B", "-m", "unittest", *self.MODULES, "-v"], command)
                self.assertNotIn("discover", command)

    def test_partial_duplicate_extended_or_other_modes_do_not_reduce_coverage(self):
        full = ",".join(self.CLASSES)
        invalid = [",".join(group) for size in range(len(self.CLASSES)) for group in itertools.combinations(self.CLASSES, size)]
        invalid += [full + ",unknown", full + "," + self.CLASSES[0], " " + full, full + " ", full + ","]
        for classes in invalid:
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=classes))[1])
        for mode in ("", "compile", "candidate", "daily", "release", "checks", "build"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=full))[1])

    def test_real_sources_are_targeted_and_allocated_once_across_all_shards(self):
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
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=",".join(self.CLASSES))
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                selection = json.loads((root / "smoke/selection.json").read_text())
                self.assertEqual("targeted", inventory["discovery_scope"])
                self.assertCountEqual(self.CLASSES, inventory["all_classes"])
                self.assertTrue(all(count > 0 for count in inventory["all_classes"].values()))
                self.assertEqual(sum(inventory["all_classes"][name] for name in selection["selected_classes"]), selection["expected_tests"])
                selected.extend(selection["selected_classes"])
            self.assertCountEqual(self.CLASSES, selected)

    def test_receipt_preserves_actual_failure_and_source_identity(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="d" * 40, GITHUB_RUN_ID="42", GITHUB_RUN_ATTEMPT="1", SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(self.CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=9) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("scroll-popup-targeted", receipt["scope"])
                    self.assertEqual(9, receipt["exit_code"])
                    self.assertEqual(dict(sha="d" * 40, run_id=42, attempt=1), receipt["identity"])
                    self.assertEqual(["python3", "-B", "-m", "unittest", *self.MODULES, "-v"], receipt["command"])
                    call.assert_called_once()
                    self.assertEqual(receipt["command"], call.call_args.args[0])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
            finally:
                os.chdir(old)



class ChatStatusBarInsetsScopeTest(unittest.TestCase):
    def test_exact_singleton_runs_only_recovery_contract_and_source_selector(self):
        expected = ("test_chat_return_refresh_contract",
                    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope")
        self.assertEqual(expected, recorder.CHAT_STATUS_BAR_INSETS_MODULES)
        for python in ("python", "python3"):
            command, scope = recorder.command_for(python, dict(SMOKE_MODE="smoke",
                SMOKE_TEST_CLASSES="app.daiyu.android.ChatStatusBarInsetsTest"))
            self.assertEqual([python, "-B", "-m", "unittest", *expected, "-v"], command)
            self.assertEqual("chat-status-bar-insets-targeted", scope)
            self.assertNotIn("discover", command)

    def test_non_exact_singletons_and_other_modes_keep_full_scope(self):
        target = "app.daiyu.android.ChatStatusBarInsetsTest"
        for value in (target + "," + target, target + ",app.daiyu.android.OtherTest", target + ",",
                      " " + target, target + " "):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value))[1])
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=target))[1])

    def test_receipt_keeps_targeted_command_real_failure_and_source_identity(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="c" * 40, GITHUB_RUN_ID="14", GITHUB_RUN_ATTEMPT="1",
                           SMOKE_MODE="smoke", SMOKE_TEST_CLASSES="app.daiyu.android.ChatStatusBarInsetsTest")
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=11) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(11, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("chat-status-bar-insets-targeted", receipt["scope"])
                    self.assertEqual(11, receipt["exit_code"])
                    self.assertEqual(dict(sha="c" * 40, run_id=14, attempt=1), receipt["identity"])
                    self.assertEqual(["python3", "-B", "-m", "unittest", *recorder.CHAT_STATUS_BAR_INSETS_MODULES, "-v"], receipt["command"])
                    self.assertEqual(receipt["command"], call.call_args.args[0])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
                    call.assert_called_once()
            finally:
                os.chdir(old)


class SearchScopeTest(unittest.TestCase):
    CLASSES = (
        "app.daiyu.android.SearchHistoryDialogTest", "app.daiyu.android.SearchHostActivityTest",
        "app.daiyu.android.RetainedSearchDataTest", "app.daiyu.android.SearchSQLiteCancellationTest",
    )
    MODULES = (
        "test_search_history_sheet_contract", "test_search_host_start_contract",
        "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
        "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
    )

    def test_exact_search_scope_uses_only_reviewed_contracts(self):
        self.assertEqual(self.CLASSES, recorder.SEARCH_PLATFORM_CLASSES)
        self.assertEqual(self.MODULES, recorder.SEARCH_MODULES)
        for classes in itertools.permutations(self.CLASSES):
            for python in ("python", "python3"):
                command, scope = recorder.command_for(python, dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
                self.assertEqual("search-targeted", scope)
                self.assertEqual([python, "-B", "-m", "unittest", *self.MODULES, "-v"], command)
                self.assertNotIn("discover", command)

    def test_inexact_search_scope_and_other_modes_keep_full(self):
        exact = ",".join(self.CLASSES)
        invalid = [",".join(group) for size in range(4) for group in itertools.combinations(self.CLASSES, size)]
        invalid += [exact + "," + name for name in self.CLASSES]
        invalid += [exact + ",app.daiyu.android.OtherTest", exact + ",", " " + exact, exact + " ", exact.replace(",", ", ", 1)]
        full = (["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full")
        for value in invalid:
            self.assertEqual(full, recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value)))
        for mode in ("", "compile", "candidate", "daily", "release", "build", "checks"):
            self.assertEqual(full, recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=exact)))

    def test_receipt_keeps_actual_failure_command_and_identity(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="c" * 40, GITHUB_RUN_ID="14", GITHUB_RUN_ATTEMPT="1", SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(self.CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "call", return_value=11) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(11, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual("search-targeted", receipt["scope"])
                    self.assertEqual(11, receipt["exit_code"])
                    self.assertEqual(dict(sha="c" * 40, run_id=14, attempt=1), receipt["identity"])
                    self.assertEqual(["python3", "-B", "-m", "unittest", *self.MODULES, "-v"], receipt["command"])
                    self.assertEqual(receipt["command"], call.call_args.args[0])
                    self.assertTrue(call.call_args.kwargs["env"]["PYTHONPATH"].startswith(str(Path("tests").resolve())))
                    call.assert_called_once()
            finally:
                os.chdir(old)


class SearchInventoryTest(unittest.TestCase):
    CLASSES = SearchScopeTest.CLASSES

    def fixture(self, root):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        paths = []
        for i, name in enumerate(self.CLASSES):
            package, simple = name.rsplit(".", 1)
            path = root / "app/src/androidTest/kotlin" / (name.replace(".", "/") + ".kt")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + package + "\nclass " + simple + " : TestCase() {\n" + "".join(" fun testCase" + str(n) + "() {}\n" for n in range(i + 1)) + "}\n")
            paths.append(path)
        return helper, paths

    def test_exact_inventory_and_four_shards_count_real_methods(self):
        for classes in (self.CLASSES, tuple(reversed(self.CLASSES))):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper, paths = self.fixture(root)
                paths[0].write_text(paths[0].read_text().removesuffix("}\n") + " fun testAdded() {}\n}\n")
                expected = dict(zip(self.CLASSES, (2, 2, 3, 4)))
                selected = []
                total = 0
                for shard in range(4):
                    result = helper.select(root, shard, classes=",".join(classes))
                    self.assertEqual(0, result.returncode, result.stderr)
                    inventory = json.loads((root / "smoke/instrumentation-shards.json").read_text())
                    selection = json.loads((root / "smoke/selection.json").read_text())
                    self.assertEqual("targeted", inventory["discovery_scope"])
                    self.assertEqual(expected, inventory["all_classes"])
                    self.assertEqual(sum(expected[name] for name in selection["selected_classes"]), selection["expected_tests"])
                    selected.extend(selection["selected_classes"])
                    total += selection["expected_tests"]
                self.assertCountEqual(self.CLASSES, selected)
                self.assertEqual(sum(expected.values()), total)

    def test_non_exact_classes_and_other_modes_do_not_skip_unrelated_ambiguity(self):
        exact = ",".join(self.CLASSES)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper, _ = self.fixture(root)
            for value in (",".join(self.CLASSES[:-1]), exact + "," + self.CLASSES[0], exact + ",app.daiyu.android.OtherTest"):
                result = helper.select(root, classes=value)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Ambiguous instrumentation test declaration", result.stderr)
            for mode in ("candidate", "daily"):
                result = helper.select(root, classes=exact, mode=mode)
                self.assertNotEqual(0, result.returncode)

    def test_missing_zero_method_and_ambiguous_requested_sources_fail_closed(self):
        for failure in ("missing", "zero", "ambiguous"):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper, paths = self.fixture(root)
                if failure == "missing":
                    paths[0].unlink()
                elif failure == "zero":
                    paths[0].write_text("package app.daiyu.android\nclass SearchHistoryDialogTest : TestCase() {}\n")
                else:
                    paths[0].write_text(paths[0].read_text() + "class ExtraTest : TestCase() {}\n")
                result = helper.select(root, classes=",".join(self.CLASSES))
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((root / "smoke/selection.json").exists())

    def test_search_workflow_uses_frozen_selector_and_independent_concurrency(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        canonical = ",".join(self.CLASSES)
        self.assertIn("inputs.mode == 'smoke' && inputs.smoke_classes == '" + canonical + "' && '-search-history' || ''", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn('test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "' + canonical + '"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi', workflow)
        self.assertIn('bash "$smoke_script"', workflow)


class ReturnChatHandoffScopeTest(unittest.TestCase):
    CLASSES = recorder.PAGE_BACK_PLATFORM_CLASSES + (
        "app.daiyu.android.RetainedToolOutcomesTest",
        "app.daiyu.android.ToolReceiptPauseActivityTest",
        "app.daiyu.android.ToolScreenshotPreviewActivityTest",
        "app.daiyu.android.ui.ToolScreenshotReceiptTest",
        "app.daiyu.android.ui.screens.chat.conversation.ChildTaskListTest",
        "app.daiyu.android.ControllerGenerationResourcesTest",
        "app.daiyu.android.ControllerGenerationIntegrationTest",
    )
    MODULES = tuple(dict.fromkeys(
        recorder.PAGE_BACK_MODULES + recorder.PAUSE_RECEIPT_MODULES + recorder.SCREEN_CLEANUP_MODULES
        + recorder.CHILD_TASK_CARD_MODULES + recorder.GENERATION_PORT_MODULES
    ))

    def test_exact_scope_has_its_own_non_cancelling_build_queue(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        concurrency = workflow.split("\nconcurrency:\n", 1)[1].split("\njobs:\n", 1)[0]
        group = next(line for line in concurrency.splitlines() if line.startswith("  group:"))
        self.assertTrue(group.startswith("  group: daiyu-${{ inputs.mode }}-${{ github.ref }}"))
        suffix = (
            "${{ inputs.mode == 'smoke' && inputs.smoke_classes == '"
            + ",".join(self.CLASSES) + "' && '-return-chat-handoff' || '' }}"
        )
        self.assertIn(suffix, group)
        self.assertIn("  cancel-in-progress: false", concurrency.splitlines())

    def test_exact_scope_has_its_own_non_cancelling_test_refresh_queue(self):
        workflow = (ROOT / ".github/workflows/test-refresh-regression.yml").read_text()
        concurrency = workflow.split("\nconcurrency:\n", 1)[1].split("\nenv:\n", 1)[0]
        group = next(line for line in concurrency.splitlines() if line.startswith("  group:"))
        self.assertTrue(group.startswith("  group: daiyu-test-refresh-${{ github.ref }}"))
        suffix = (
            "${{ inputs.smoke_classes == '" + ",".join(self.CLASSES)
            + "' && '-return-chat-handoff' || '' }}"
        )
        self.assertIn(suffix, group)
        self.assertIn("  cancel-in-progress: false", concurrency.splitlines())

    def test_exact_scope_uses_stable_deduplicated_union(self):
        self.assertEqual(self.CLASSES, recorder.RETURN_CHAT_HANDOFF_PLATFORM_CLASSES)
        self.assertEqual(12, len(set(self.CLASSES)))
        self.assertEqual(self.MODULES, recorder.RETURN_CHAT_HANDOFF_MODULES)
        self.assertEqual(len(self.MODULES), len(set(self.MODULES)))
        self.assertIn("test_tool_receipt_pause_contract", self.MODULES)
        self.assertIn("test_tool_receipt_snapshot_contract", self.MODULES)
        for classes in (self.CLASSES, tuple(reversed(self.CLASSES)), tuple(sorted(self.CLASSES))):
            for python in ("python", "python3"):
                command, scope = recorder.command_for(python, dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
                self.assertEqual("return-chat-handoff-targeted", scope)
                self.assertEqual([python, "-B", "-m", "unittest", *self.MODULES, "-v"], command)
                self.assertNotIn("discover", command)

    def test_inexact_filters_and_other_modes_keep_full_fallback(self):
        selected = ",".join(self.CLASSES)
        invalid = [",".join(self.CLASSES[:i] + self.CLASSES[i + 1:]) for i in range(len(self.CLASSES))]
        invalid += [selected + "," + self.CLASSES[0], selected + ",app.daiyu.android.OtherTest",
                    " " + selected, selected + " ", selected.replace(",", ", ", 1), selected + ","]
        for value in invalid:
            self.assertEqual((["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full"),
                             recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value)))
        for mode in ("", "compile", "candidate", "daily", "release"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=selected))[1])

    def test_existing_targeted_scopes_are_unchanged(self):
        for prefix, scope in (("PAGE_BACK", "page-back-targeted"), ("PAUSE_RECEIPT", "pause-receipts-targeted"),
                              ("GENERATION_PORT", "generation-port-targeted")):
            classes = getattr(recorder, prefix + "_PLATFORM_CLASSES")
            modules = getattr(recorder, prefix + "_MODULES")
            command, actual = recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(classes)))
            self.assertEqual(scope, actual)
            self.assertEqual(["python3", "-B", "-m", "unittest", *modules, "-v"], command)

    def test_receipt_keeps_failure_identity_and_validation_cwd(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA="b" * 40, EXPECTED_SHA="a" * 40, GITHUB_RUN_ID="42",
                           GITHUB_RUN_ATTEMPT="1", SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(self.CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, "check_output", return_value="a" * 40 + "\n"), patch.object(recorder.subprocess, "call", return_value=9) as call, patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record("python3"))
                    receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                    self.assertEqual(dict(sha="a" * 40, run_id=42, attempt=1), receipt["identity"])
                    self.assertEqual("b" * 40, receipt["workflow_sha"])
                    self.assertEqual("b" * 40, receipt["validation_tools_sha"])
                    self.assertEqual("return-chat-handoff-targeted", receipt["scope"])
                    self.assertEqual(9, receipt["exit_code"])
                    self.assertEqual(["python3", "-B", "-m", "unittest", *self.MODULES, "-v"], receipt["command"])
                    call.assert_called_once()
                    self.assertEqual(receipt["command"], call.call_args.args[0])
                    self.assertEqual(ROOT, call.call_args.kwargs["cwd"])
                    self.assertEqual(str(ROOT / "tests"), call.call_args.kwargs["env"]["PYTHONPATH"].split(os.pathsep)[0])
            finally:
                os.chdir(old)

class TaskScrollFrozenCheckToolsTest(unittest.TestCase):
    def test_exact_scope_uses_current_tools_and_preserves_raw_failure_and_both_shas(self):
        classes = recorder.TASK_SCROLL_PLATFORM_CLASSES
        raw = b"synthetic frozen-check failure\r\nraw stderr\x00\n"
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                Path("tests").mkdir()
                Path("tests/test_acceptance_tool_receipt.py").write_text("raise AssertionError('stale checkout')\n")
                for python in ("python", "python3"):
                    for selected in (classes, tuple(reversed(classes))):
                        for code in (0, 9):
                            env = dict(GITHUB_SHA="b" * 40, EXPECTED_SHA="a" * 40,
                                       GITHUB_RUN_ID="42", GITHUB_RUN_ATTEMPT="1",
                                       SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=",".join(selected))
                            def child(command, stdout, stderr, env=None, cwd=None):
                                self.assertEqual(ROOT, cwd)
                                self.assertEqual(str(ROOT / "tests"), env["PYTHONPATH"].split(os.pathsep)[0])
                                self.assertEqual(subprocess.STDOUT, stderr)
                                stdout.write(raw)
                                return code
                            console = io.BytesIO()
                            with patch.dict(os.environ, env, clear=True), \
                                    patch.object(recorder.subprocess, "check_output", return_value="a" * 40 + "\n"), \
                                    patch.object(recorder.subprocess, "call", side_effect=child) as call, \
                                    patch.object(recorder.sys, "stdout", SimpleNamespace(buffer=console)):
                                self.assertEqual(code, recorder.record(python))
                            receipt = json.loads(Path("tool-evidence/receipt.json").read_text())
                            self.assertEqual(dict(sha="a" * 40, run_id=42, attempt=1), receipt["identity"])
                            self.assertEqual("b" * 40, receipt["workflow_sha"])
                            self.assertEqual("b" * 40, receipt["validation_tools_sha"])
                            self.assertEqual("task-scroll-targeted", receipt["scope"])
                            self.assertEqual(code, receipt["exit_code"])
                            self.assertEqual([python, "-B", "-m", "unittest", *recorder.TASK_SCROLL_MODULES, "-v"], receipt["command"])
                            self.assertEqual(receipt["command"], call.call_args.args[0])
                            self.assertNotIn("discover", receipt["command"])
                            self.assertEqual(raw, Path("tool-evidence/unittest.log").read_bytes())
                            self.assertEqual(raw, console.getvalue())
                            call.assert_called_once()
            finally:
                os.chdir(old)

    def test_inexact_scope_and_other_modes_keep_full_fallback(self):
        selected = ",".join(recorder.TASK_SCROLL_PLATFORM_CLASSES)
        invalid = (",".join(recorder.TASK_SCROLL_PLATFORM_CLASSES[:-1]),
                   selected + "," + recorder.TASK_SCROLL_PLATFORM_CLASSES[0],
                   selected + ",app.daiyu.android.OtherTest", " " + selected,
                   selected + " ", selected.replace(",", ", ", 1))
        for value in invalid:
            self.assertEqual((["python3", "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full"),
                             recorder.command_for("python3", dict(SMOKE_MODE="smoke", SMOKE_TEST_CLASSES=value)))
        for mode in ("", "compile", "candidate", "daily", "release", "checks"):
            self.assertEqual("full", recorder.command_for("python3", dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=selected))[1])

    def test_interruption_removes_stale_success_without_creating_a_receipt(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                receipt = Path("tool-evidence/receipt.json")
                receipt.parent.mkdir()
                env = dict(GITHUB_SHA="b" * 40, EXPECTED_SHA="a" * 40,
                           GITHUB_RUN_ID="42", GITHUB_RUN_ATTEMPT="1", SMOKE_MODE="smoke",
                           SMOKE_TEST_CLASSES=",".join(recorder.TASK_SCROLL_PLATFORM_CLASSES))
                for failure in (OSError("fixture"), KeyboardInterrupt()):
                    receipt.write_text('{"exit_code":0}')
                    with patch.dict(os.environ, env, clear=True), \
                            patch.object(recorder.subprocess, "check_output", return_value="a" * 40 + "\n"), \
                            patch.object(recorder.subprocess, "call", side_effect=failure):
                        with self.assertRaises(type(failure)):
                            recorder.record("python3")
                    self.assertFalse(receipt.exists())
            finally:
                os.chdir(old)

class UserMessageEntranceToolTestsScopeTest(unittest.TestCase):
    CLASSES = (
        'app.daiyu.android.ui.screens.chat.conversation.UserMessageEntranceTest',
        'app.daiyu.android.TextSendActivityTest',
        'app.daiyu.android.ChatSubmissionActivityTest',
        'app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest',
    )
    MODULES = (
        'test_chat_submission_preview_contract', 'test_composer_surface_color_contract',
        'test_compression_send_gate_contract', 'test_delegation_batch_and_send_contract',
        'test_streaming_follow_contract', 'test_streaming_occlusion_contract',
        'test_reasoning_stream_cost_contract', 'test_workflow_render_isolation_contract',
        'test_markdown_typing_contract', 'test_chat_resume_scroll_contract',
        'test_diagnostic_smoke_source', 'test_record_tool_tests_scope',
        'test_acceptance_tool_receipt', 'test_task_scroll_smoke_scope',
        'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope',
    )

    def test_exact_permutations_keep_send_and_scroll_contracts(self):
        self.assertEqual(self.CLASSES, recorder.USER_MESSAGE_ENTRANCE_PLATFORM_CLASSES)
        self.assertEqual(self.MODULES, recorder.USER_MESSAGE_ENTRANCE_MODULES)
        for selected in itertools.permutations(self.CLASSES):
            command, scope = recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(selected)))
            self.assertEqual('user-message-entrance-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *self.MODULES, '-v'], command)
            self.assertNotIn('discover', command)

    def test_inexact_filters_and_other_modes_keep_full_fallback(self):
        raw = ','.join(self.CLASSES)
        invalid = ['', 'unknown', ' ' + raw, raw + ' ', raw + ',',
                   raw + ',app.daiyu.android.OtherTest']
        invalid += [','.join(self.CLASSES[:i] + self.CLASSES[i + 1:]) for i in range(len(self.CLASSES))]
        invalid += [raw + ',' + name for name in self.CLASSES]
        full = (['python3', '-B', '-m', 'unittest', 'discover', '-s', 'tests', '-v'], 'full')
        for selected in invalid:
            self.assertEqual(full, recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=selected)))
        for mode in ('', 'compile', 'candidate', 'daily', 'release', 'checks', 'build', 'synthetic', 'performance'):
            self.assertEqual(full, recorder.command_for('python3', dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw)))

    def test_receipt_keeps_exit_code_checked_revision_and_frozen_tool_cwd(self):
        old = Path.cwd()
        for code in (0, 9):
            with tempfile.TemporaryDirectory() as directory:
                try:
                    os.chdir(directory)
                    env = dict(GITHUB_SHA='b' * 40, EXPECTED_SHA='a' * 40,
                               GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='1', SMOKE_MODE='smoke',
                               SMOKE_TEST_CLASSES=','.join(self.CLASSES))
                    with patch.dict(os.environ, env, clear=True), patch.object(
                            recorder.subprocess, 'check_output', return_value='a' * 40 + '\n'), patch.object(
                            recorder.subprocess, 'call', return_value=code) as call, patch.object(
                            recorder.sys, 'stdout', SimpleNamespace(buffer=io.BytesIO())):
                        self.assertEqual(code, recorder.record('python3'))
                    receipt = json.loads(Path('tool-evidence/receipt.json').read_text())
                    self.assertEqual(dict(sha='a' * 40, run_id=42, attempt=1), receipt['identity'])
                    self.assertEqual('user-message-entrance-targeted', receipt['scope'])
                    self.assertEqual(code, receipt['exit_code'])
                    self.assertEqual('b' * 40, receipt['validation_tools_sha'])
                    call.assert_called_once()
                    self.assertEqual(receipt['command'], call.call_args.args[0])
                    base = Path(recorder.__file__).resolve().parents[2]
                    self.assertEqual(base, call.call_args.kwargs['cwd'])
                    self.assertEqual(str(base / 'tests') + os.pathsep,
                                     call.call_args.kwargs['env']['PYTHONPATH'])
                finally:
                    os.chdir(old)

    def test_frozen_smoke_gate_runs_this_scope_contract(self):
        source = (ROOT / '.github/workflows/build.yml').read_text()
        step = source.split('      - name: 验证冻结补检工具\n', 1)[1].split('      - name:', 1)[0]
        command = next(line.strip()[len('run: '):] for line in step.splitlines()
                       if line.strip().startswith('run: '))
        self.assertIn('test_record_tool_tests_scope.UserMessageEntranceToolTestsScopeTest', command.split())
