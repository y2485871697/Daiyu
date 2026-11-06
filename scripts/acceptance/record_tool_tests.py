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
    'test_chat_timeline_contract', 'test_reasoning_flow_stability_contract',
    'test_diagnostic_smoke_source', 'test_record_tool_tests_scope',
    'test_acceptance_tool_receipt', 'test_page_back_smoke_scope',
    'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope',
)


TASK_SCROLL_PLATFORM_CLASSES = (
    'app.daiyu.android.ui.screens.chat.conversation.ReasoningWorkflowTest',
    'app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningTypingTest',
    'app.daiyu.android.ui.screens.chat.conversation.MarkdownRevealTraversalTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest',
)
TASK_SCROLL_MODULES = (
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

def command_for(python, env):
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == SQLITE_TEXT_PLATFORM_CLASS):
        return [python, "-B", "-m", "unittest", *SQLITE_TEXT_MODULES, "-v"], "sqlite-text-targeted"
    if env.get("SMOKE_MODE") == "smoke" and env.get("SMOKE_TEST_CLASSES") == SPEECH_PLATFORM_CLASS:
        return [python, "-B", "-m", "unittest", *SPEECH_MODULES, "-v"], "speech-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(MENU_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *MENU_MODULES, "-v"], "menu-targeted"
    if (env.get('SMOKE_MODE') == 'smoke'
            and sorted(env.get('SMOKE_TEST_CLASSES', '').split(',')) == sorted(PAGE_BACK_PLATFORM_CLASSES)):
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
