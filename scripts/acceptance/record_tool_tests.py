#!/usr/bin/env python3
"""CI-only unittest transcript/receipt capture; not business acceptance.

The final offline entry never invokes this producer. Missing/interrupted
execution must stay missing, not turn into a synthetic success receipt.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


SPEECH_PLATFORM_CLASS = "app.daiyu.android.voice.SpeechAudioFileAndroidTest"
SPEECH_MODULES = (
    "test_chat_voice_feedback_contract", "test_toast_concise_copy_contract",
    "test_message_speech_button_contract", "test_record_tool_tests_scope",
    "test_speech_platform_discovery",
)


MENU_PLATFORM_CLASSES = tuple("app.daiyu.android." + name for name in (
    "BackdropDrawIsolationTest", "ChatGlassMenuSurfaceTest", "ChatGlassBackdropActivityTest",
    "ChatMenuPlacementActivityTest", "ConversationPaneVisualTest", "PreviewDialogStatusBarActivityTest",
))
MENU_MODULES = (
    "test_chat_menu_scroll_isolation_contract", "test_chat_menu_shadow_contract",
    "test_chat_overflow_icon_colors_contract", "test_chat_outside_tap_contract",
    "test_dialog_motion_contract.DialogMotionContractTest.test_production_glass_menu_uses_original_popup_motion_and_exit_retention",
    "test_dialog_motion_contract.DialogMotionContractTest.test_more_button_is_transparent_but_keeps_selection_semantics",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt", "test_speech_platform_discovery",
)


PAGE_BACK_PLATFORM_CLASSES = (
    'app.daiyu.android.ui.navigation.PageBackGesturesActivityTest',
    'app.daiyu.android.ui.navigation.PageBackStackActivityTest',
    'app.daiyu.android.ui.navigation.PageEnterActivityTest',
    'app.daiyu.android.ui.navigation.PageRetainedContentActivityTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest',
)
PAGE_BACK_MODULES = (
    'test_page_back_gesture_contract', 'test_page_back_repeat_contract',
    'test_page_enter_contract',
    'test_chat_timeline_contract', 'test_reasoning_flow_stability_contract',
    'test_diagnostic_smoke_source', 'test_record_tool_tests_scope',
    'test_acceptance_tool_receipt', 'test_page_back_smoke_scope',
    'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope',
)


TASK_SCROLL_PLATFORM_CLASSES = (
    'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingFollowTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatResumeScrollTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageSpacingTest',
    'app.daiyu.android.ui.screens.chat.conversation.ReasoningWorkflowTest',
    'app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningTypingTest',
    'app.daiyu.android.ui.screens.chat.conversation.MarkdownRevealTraversalTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest',
)
TASK_SCROLL_MODULES = (
    'test_streaming_follow_contract',
    'test_streaming_occlusion_contract',
    'test_reasoning_stream_cost_contract',
    'test_workflow_render_isolation_contract',
    'test_markdown_typing_contract',
    'test_chat_resume_scroll_contract',
    'test_diagnostic_smoke_source',
    'test_record_tool_tests_scope',
    'test_acceptance_tool_receipt',
    'test_task_scroll_smoke_scope',
    'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope',
)


PAGE_PERFORMANCE_PLATFORM_CLASSES = TASK_SCROLL_PLATFORM_CLASSES + (
    'app.daiyu.android.UsagePresentationActivityTest',
    'app.daiyu.android.LocalCatalogModelsActivityTest',
    'app.daiyu.android.ModelBulkSelectionActivityTest',
    'app.daiyu.android.AssistantAvatarTest',
    'app.daiyu.android.AssistantNavigationActivityTest',
    'app.daiyu.android.AssistantPickerAdmissionTest',
    'app.daiyu.android.SkillInstallScreenTest',
    'app.daiyu.android.ui.navigation.PageBackGesturesActivityTest',
    'app.daiyu.android.ui.navigation.PageBackStackActivityTest',
    'app.daiyu.android.ui.navigation.PageEnterActivityTest',
    'app.daiyu.android.ui.navigation.PageRetainedContentActivityTest',
)
PAGE_PERFORMANCE_MODULES = TASK_SCROLL_MODULES + (
    'test_model_bulk_selection_contract',
    'test_settings_copy_cleanup_contract',
    'test_page_enter_contract',
    'test_page_back_gesture_contract',
    'test_page_back_repeat_contract',
    'test_sidebar_selection_contract',
    'test_streaming_freeze_contract',
    'test_page_performance_contract',
    'test_page_performance_smoke_scope',
    'test_task_configuration_admission_contract',
    'test_selection_stays_open_contract',
)


MARKDOWN_ENTRY_PLATFORM_CLASSES = (
    'app.daiyu.android.ControllerLifecycleTest',
    'app.daiyu.android.RestoredMarkdownActivityTest',
    'app.daiyu.android.ConversationPaneSwitchActivityTest',
    'app.daiyu.android.AcceptedHistoryActivityTest',
    'app.daiyu.android.ui.screens.chat.conversation.MessageMarkdownTest',
)
MARKDOWN_ENTRY_MODULES = (
    'test_markdown_entry_contract',
    'test_markdown_return_contract',
    'test_markdown_cold_start_contract',
    'test_sidebar_selection_contract',
    'test_streaming_freeze_contract',
    'test_diagnostic_smoke_source',
    'test_record_tool_tests_scope',
    'test_acceptance_tool_receipt',
    'test_markdown_entry_smoke_scope',
    'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope',
)


GUI_PARSER_PLATFORM_CLASS = "app.daiyu.android.gui.GuiVisualWindowParserAndroidTest"
GUI_PARSER_MODULES = (
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_gui_parser_smoke_scope",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


SCREEN_HISTORY_PLATFORM_CLASS = "app.daiyu.android.storage.ScreenProtocolHistoryAndroidTest"
SQLITE_TEXT_PLATFORM_CLASS = "app.daiyu.android.storage.SQLiteTextReadDatabaseTest"
SQLITE_TEXT_MODULES = (
    "test_large_history_storage_contract", "test_checkpoint_encoding_hotpath_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_sqlite_text_smoke_scope", "test_diagnostic_smoke_source",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


COLD_DISPLAY_PLATFORM_CLASSES = MARKDOWN_ENTRY_PLATFORM_CLASSES + (
    SQLITE_TEXT_PLATFORM_CLASS,
    "app.daiyu.android.ChatContextUsageRestoreTest",
    "app.daiyu.android.storage.CloudContextUsageStoreTest",
)
COLD_DISPLAY_MODULES = tuple(dict.fromkeys(MARKDOWN_ENTRY_MODULES + SQLITE_TEXT_MODULES + (
    "test_message_display_contract", "test_context_usage_wiring_contract",
)))

RUNTIME_SCREENSHOT_PLATFORM_CLASSES = (
    "app.daiyu.android.storage.CommitMessageDecodeDatabaseTest",
    "app.daiyu.android.storage.SQLiteRuntimeStoreDatabaseTest",
    "app.daiyu.android.ui.ToolScreenshotReceiptTest",
)
RUNTIME_SCREENSHOT_MODULES = (
    "test_checkpoint_encoding_hotpath_contract", "test_runtime_checkpoint_reuse_contract",
    "test_large_history_storage_contract", "test_runtime_commit_hotpath_contract",
    "test_context_usage_wiring_contract", "test_record_tool_tests_scope",
    "test_acceptance_tool_receipt", "test_runtime_screenshot_smoke_scope",
    "test_diagnostic_smoke_source",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


ROOTLESS_COMMIT_PLATFORM_CLASSES = (
    "app.daiyu.android.gui.GuiRootlessContentAndroidTest",
    "app.daiyu.android.storage.CommitMessageDecodeDatabaseTest",
)
ROOTLESS_COMMIT_MODULES = tuple(dict.fromkeys(RUNTIME_SCREENSHOT_MODULES + (
    "test_rootless_content_smoke_scope", "test_gui_audit_recovery_contract",
)))


SCREEN_CLEANUP_PLATFORM_CLASSES = (
    "app.daiyu.android.ControllerGuiStorageRetentionTest",
    "app.daiyu.android.gui.GuiCaptureDisposalProductionTest",
    "app.daiyu.android.gui.GuiRootlessContentAndroidTest",
    "app.daiyu.android.ui.ToolScreenshotReceiptTest",
)
SCREEN_CLEANUP_MODULES = (
    "test_gui_temporary_screenshot_cleanup_contract", "test_gui_direct_screenshot_contract",
    "test_project_identity_contract", "test_gui_audit_recovery_contract",
    "test_rootless_content_smoke_scope", "test_screen_cleanup_smoke_scope",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


PAUSE_RECEIPT_PLATFORM_CLASSES = (
    "app.daiyu.android.ToolReceiptPauseActivityTest",
    "app.daiyu.android.RetainedToolOutcomesTest",
    "app.daiyu.android.ControllerRunControlTest",
    "app.daiyu.android.QuestionBarrierIntegrationTest",
)
PAUSE_RECEIPT_MODULES = (
    "test_tool_receipt_pause_contract", "test_tool_receipt_snapshot_contract",
    "test_reasoning_flow_stability_contract", "test_pause_receipt_smoke_scope",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)



TERMINAL_SCREEN_PLATFORM_CLASS = "app.daiyu.android.terminal.TerminalScreenTest"
TERMINAL_SCREEN_MODULES = (
    "test_terminal_output_cloud_contract", "test_terminal_geometry_contract",
    "test_terminal_reference_contract", "test_terminal_sheet_style_contract",
    "test_chroot_workspace_command_contract", "test_record_tool_tests_scope",
    "test_acceptance_tool_receipt", "test_terminal_screen_smoke_scope",
    "test_diagnostic_smoke_source",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


LINUX_PIN_PLATFORM_CLASSES = (
    "app.daiyu.android.terminal.LinuxLeaseRecoveryDeviceTest",
    "app.daiyu.android.terminal.TerminalEnvironmentHostDeviceTest",
)
LINUX_PIN_MODULES = (
    "test_terminal_geometry_contract", "test_terminal_reference_contract",
    "test_terminal_sheet_style_contract", "test_chroot_workspace_command_contract",
    "test_linux_status_pin_contract", "test_record_tool_tests_scope",
    "test_acceptance_tool_receipt", "test_linux_pin_smoke_scope",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


TERMINAL_COMMAND_PAGER_PLATFORM_CLASS = "app.daiyu.android.terminal.TerminalCommandPagerTest"
TERMINAL_COMMAND_PAGER_MODULES = (
    "test_chroot_workspace_command_contract", "test_terminal_command_pager_smoke_scope",
    "test_terminal_job_status_contract", "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


COMPOSER_CONTEXT_PLATFORM_CLASSES = (
    'app.daiyu.android.TextSendActivityTest',
    'app.daiyu.android.ChatChromeActivityTest',
    'app.daiyu.android.context.ContextServiceTest',
    'app.daiyu.android.context.TokenTailCompressionTest',
)
COMPOSER_CONTEXT_MODULES = (
    'test_chat_submission_preview_contract', 'test_composer_surface_color_contract',
    'test_compression_send_gate_contract', 'test_delegation_batch_and_send_contract',
    'test_context_usage_wiring_contract', 'test_cloud_context_scope',
    'test_record_tool_tests_scope', 'test_acceptance_tool_receipt',
    'test_composer_context_smoke_scope',
    'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope',
)



CHILD_TASK_CARD_PLATFORM_CLASSES = tuple("app.daiyu.android.ui.screens.chat.conversation." + name for name in (
    "ChildTaskListTest", "ToolOperationCardTest", "ToolCardReadabilityTest", "SubAgentStatusDialogTest",
))
CHILD_TASK_CARD_INHERITANCE_CLASSES = CHILD_TASK_CARD_PLATFORM_CLASSES + (
    "app.daiyu.android.ConversationSelectionInheritanceActivityTest",
)
CHILD_TASK_CARD_THEME_CLASSES = CHILD_TASK_CARD_INHERITANCE_CLASSES + (
    "app.daiyu.android.ChatThemeInteractionActivityTest",
)
CHILD_TASK_CARD_MODULES = (
    "test_delegation_batch_and_send_contract", "test_child_stop_hot_configuration_contract",
    "test_subagent_resume_display_contract", "test_subagent_collaboration_contract",
    "test_sidebar_task_entry_contract", "test_child_task_card_contract", "test_chat_return_refresh_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


POPUP_SCROLL_PLATFORM_CLASSES = ('app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest', 'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest', 'app.daiyu.android.PreviewDialogStatusBarActivityTest', 'app.daiyu.android.AssistantPickerAdmissionTest', 'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingFollowTest')
POPUP_SCROLL_MODULES = ('test_streaming_occlusion_contract', 'test_streaming_follow_contract', 'test_chat_resume_scroll_contract', 'test_preview_dialog_status_bar_contract', 'test_assistant_picker_frame_contract', 'test_chat_menu_scroll_isolation_contract', 'test_popup_performance_contract', 'test_record_tool_tests_scope', 'test_acceptance_tool_receipt', 'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope')

RENDERING_PLATFORM_CLASSES = ('app.daiyu.android.ui.screens.chat.conversation.RenderingRegressionTest', 'app.daiyu.android.ui.screens.chat.conversation.MessageMarkdownTest', 'app.daiyu.android.ui.screens.chat.conversation.MarkdownTableStyleTest', 'app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningMarkdownTest', 'app.daiyu.android.ui.screens.chat.conversation.ReasoningWorkflowTest', 'app.daiyu.android.RestoredMarkdownActivityTest')
RENDERING_MODULES = ('test_reasoning_workflow_contract', 'test_work_timeline_connector_contract', 'test_workflow_render_isolation_contract', 'test_markdown_typing_contract', 'test_markdown_body_visibility_contract', 'test_markdown_table_style_contract', 'test_markdown_code_block_contract', 'test_markdown_entry_contract', 'test_markdown_cold_start_contract', 'test_markdown_return_contract', 'test_markdown_stream_progress_contract', 'test_markdown_stream_optimization_contract', 'test_message_display_contract', 'test_rendering_smoke_scope', 'test_record_tool_tests_scope', 'test_acceptance_tool_receipt')


CHAT_STATUS_BAR_INSETS_PLATFORM_CLASS = "app.daiyu.android.ChatStatusBarInsetsTest"
CHAT_STATUS_BAR_INSETS_MODULES = (
    "test_chat_return_refresh_contract",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


RENAME_PRESENTATION_PLATFORM_CLASS = "app.daiyu.android.RenamePresentationActivityTest"
RENAME_PRESENTATION_MODULES = (
    "test_rename_presentation_contract", "test_workspace_create_folder_contract",
    "test_workspace_entry_mutations_contract", "test_subagent_collaboration_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
)


def command_for(python, env):
    if env.get("SMOKE_MODE") == "smoke" and env.get("SMOKE_TEST_CLASSES") == RENAME_PRESENTATION_PLATFORM_CLASS:
        return [python, "-B", "-m", "unittest", *RENAME_PRESENTATION_MODULES, "-v"], "rename-presentation-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == CHAT_STATUS_BAR_INSETS_PLATFORM_CLASS):
        return [python, "-B", "-m", "unittest", *CHAT_STATUS_BAR_INSETS_MODULES, "-v"], "chat-status-bar-insets-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(POPUP_SCROLL_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *POPUP_SCROLL_MODULES, "-v"], "popup-scroll-targeted"
    if (env.get('SMOKE_MODE') == 'smoke'
            and sorted(env.get('SMOKE_TEST_CLASSES', '').split(',')) == sorted(RENDERING_PLATFORM_CLASSES)):
        return [python, '-B', '-m', 'unittest', *RENDERING_MODULES, '-v'], 'rendering-targeted'
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(COMPOSER_CONTEXT_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *COMPOSER_CONTEXT_MODULES, "-v"], "composer-context-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == TERMINAL_COMMAND_PAGER_PLATFORM_CLASS):
        return [python, "-B", "-m", "unittest", *TERMINAL_COMMAND_PAGER_MODULES, "-v"], "terminal-command-pager-targeted"
    if (env.get('SMOKE_MODE') == 'smoke'
            and sorted(env.get('SMOKE_TEST_CLASSES', '').split(',')) == sorted(PAGE_PERFORMANCE_PLATFORM_CLASSES)):
        return [python, '-B', '-m', 'unittest', *PAGE_PERFORMANCE_MODULES, '-v'], 'page-performance-targeted'
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == TERMINAL_SCREEN_PLATFORM_CLASS):
        return [python, "-B", "-m", "unittest", *TERMINAL_SCREEN_MODULES, "-v"], "terminal-screen-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(LINUX_PIN_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *LINUX_PIN_MODULES, "-v"], "linux-pins-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == "app.daiyu.android.RetainedToolOutcomesTest"):
        return [python, "-B", "-m", "unittest", *PAUSE_RECEIPT_MODULES, "-v"], "retained-outcomes-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(PAUSE_RECEIPT_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *PAUSE_RECEIPT_MODULES, "-v"], "pause-receipts-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) in [sorted(SCREEN_CLEANUP_PLATFORM_CLASSES), sorted((*SCREEN_CLEANUP_PLATFORM_CLASSES,
                'app.daiyu.android.ToolScreenshotPreviewActivityTest'))]):
        return [python, "-B", "-m", "unittest", *SCREEN_CLEANUP_MODULES, "-v"], "screen-cleanup-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(ROOTLESS_COMMIT_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *ROOTLESS_COMMIT_MODULES, "-v"], "rootless-commit-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(RUNTIME_SCREENSHOT_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *RUNTIME_SCREENSHOT_MODULES, "-v"], "runtime-screenshot-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(COLD_DISPLAY_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *COLD_DISPLAY_MODULES, "-v"], "cold-display-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == SQLITE_TEXT_PLATFORM_CLASS):
        return [python, "-B", "-m", "unittest", *SQLITE_TEXT_MODULES, "-v"], "sqlite-text-targeted"
    if env.get("SMOKE_MODE") == "smoke" and env.get("SMOKE_TEST_CLASSES") == SPEECH_PLATFORM_CLASS:
        return [python, "-B", "-m", "unittest", *SPEECH_MODULES, "-v"], "speech-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(MENU_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *MENU_MODULES, "-v"], "menu-targeted"
    if (env.get('SMOKE_MODE') == 'smoke'
            and sorted(env.get('SMOKE_TEST_CLASSES', '').split(',')) in (
                sorted(PAGE_BACK_PLATFORM_CLASSES),
                sorted((*PAGE_BACK_PLATFORM_CLASSES, 'app.daiyu.android.MessageActionsActivityTest')))):
        return [python, '-B', '-m', 'unittest', *PAGE_BACK_MODULES, '-v'], 'page-back-targeted'
    if (env.get('SMOKE_MODE') == 'smoke'
            and sorted(env.get('SMOKE_TEST_CLASSES', '').split(',')) == sorted(TASK_SCROLL_PLATFORM_CLASSES)):
        return [python, '-B', '-m', 'unittest', *TASK_SCROLL_MODULES, '-v'], 'task-scroll-targeted'
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == GUI_PARSER_PLATFORM_CLASS):
        return [python, "-B", "-m", "unittest", *GUI_PARSER_MODULES, "-v"], "gui-parser-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == SCREEN_HISTORY_PLATFORM_CLASS):
        return [python, "-B", "-m", "unittest", "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
                "test_screen_history_smoke_scope",
                "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope", "-v"], "screen-history-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == "app.daiyu.android.gui.GuiRootlessContentAndroidTest"):
        return [python, "-B", "-m", "unittest", "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
                "test_rootless_content_smoke_scope",
                "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope", "-v"], "rootless-content-targeted"
    if (env.get('SMOKE_MODE') == 'smoke'
            and sorted(env.get('SMOKE_TEST_CLASSES', '').split(',')) == sorted(MARKDOWN_ENTRY_PLATFORM_CLASSES)):
        return [python, '-B', '-m', 'unittest', *MARKDOWN_ENTRY_MODULES, '-v'], 'markdown-entry-targeted'
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) in (
                sorted(CHILD_TASK_CARD_PLATFORM_CLASSES), sorted(CHILD_TASK_CARD_INHERITANCE_CLASSES),
                sorted(CHILD_TASK_CARD_THEME_CLASSES))):
        return [python, "-B", "-m", "unittest", *CHILD_TASK_CARD_MODULES, "-v"], "child-task-cards-targeted"
    return [python, "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full"


def record(python):
    sha = os.environ["GITHUB_SHA"]
    if not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", sha):
        raise ValueError("invalid CI SHA")
    identity = dict(sha=sha, run_id=int(os.environ["GITHUB_RUN_ID"]),
                    attempt=int(os.environ["GITHUB_RUN_ATTEMPT"]))
    if identity["run_id"] <= 0 or identity["attempt"] <= 0:
        raise ValueError("invalid CI run selector")
    root = Path("tool-evidence")
    root.mkdir(exist_ok=True)
    receipt = root / "receipt.json"
    # Never leave a stale success receipt when the child cannot start or dies
    # together with its runner. No receipt is written until wait returns.
    receipt.unlink(missing_ok=True)
    command, scope = command_for(python, os.environ)
    child_env = None
    if scope != "full":
        child_env = os.environ.copy()
        child_env["PYTHONPATH"] = str(Path("tests").resolve()) + os.pathsep + child_env.get("PYTHONPATH", "")
    log = root / "unittest.log"
    with log.open("wb") as output:
        code = subprocess.call(command, stdout=output, stderr=subprocess.STDOUT, env=child_env)
    result = dict(identity=identity, command=command, exit_code=code)
    if scope != "full":
        result["scope"] = scope
    receipt.write_text(json.dumps(result) + "\n",
                       encoding="utf-8")
    with log.open("rb") as output:
        shutil.copyfileobj(output, sys.stdout.buffer)
    return code


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python", choices=("python", "python3"), required=True)
    return record(parser.parse_args(argv).python)


if __name__ == "__main__":
    sys.exit(main())
