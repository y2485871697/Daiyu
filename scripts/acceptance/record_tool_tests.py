#!/usr/bin/env python3
"""在 CI 中运行 unittest 并记录原始输出和回执。

离线交付审查不调用此脚本；执行未完成时不生成成功回执。
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


BROWSER_PLATFORM_CLASSES = (
    "app.daiyu.android.BrowserPageLifecycleActivityTest",
    "app.daiyu.android.browser.BrowserProductionCompositionTest",
    "app.daiyu.android.browser.BrowserUiInputTest",
    "app.daiyu.android.browser.CatalogBrowserBindingAndroidTest",
)
BROWSER_LIFECYCLE_PLATFORM_CLASSES = (*BROWSER_PLATFORM_CLASSES,
    "app.daiyu.android.ControllerBrowserVisualTest",
    "app.daiyu.android.BrowserVisualLayoutTest",
)
BROWSER_MODULES = (
    "test_browser_pause_lifecycle_contract",
    "test_browser_navigation_defaults_contract", "test_browser_port_contract",
    "test_browser_manual_state_contract", "test_browser_download_cleanup_contract",
    "test_browser_gesture_resume_contract", "test_browser_original_ui_contract",
    "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


DELETE_CONFIRM_PLATFORM_CLASSES = tuple("app.daiyu.android." + name for name in (
    'DeleteConfirmDialogActivityTest',
    'FolderDeleteConfirmationActivityTest',
    'VoiceLocalRemovalSheetActivityTest',
    'ConversationBatchDeleteActivityTest',
    'WorkspaceEntryActionsActivityTest',
    'DeletionButtonAppearanceTest',
    'ConversationPaneManagementActivityTest',
    'SharedSheetSpacingActivityTest',
    'MessageConfirmationActivityTest',
    'ProviderDirectorySelectionActivityTest',
    'AssistantPickerVisualTest',
))
DELETE_CONFIRM_MODULES = (
    'test_delete_confirm_dialog_contract',
    'test_folder_delete_confirmation_contract',
    'test_voice_local_removal_sheet_contract',
    'test_deletion_vivid_red_contract',
    'test_conversation_batch_delete_contract',
    'test_conversation_delete_close_contract',
    'test_shared_sheet_spacing_contract',
    'test_message_confirmation_contract',
    'test_assistant_picker_frame_contract',
    'test_popup_title_contract',
    'test_workspace_entry_mutations_contract',
    'test_deleted_model_session_contract',
    'test_model_bulk_selection_contract',
    'test_record_tool_tests_scope',
    'test_acceptance_tool_receipt',
    'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope',
)


BATCH_DELETE_PLATFORM_CLASSES = (
    "app.daiyu.android.ConversationBatchDeleteActivityTest",
    "app.daiyu.android.SharedSheetSpacingActivityTest",
)
BATCH_DELETE_MODULES = (
    "test_conversation_batch_delete_contract", "test_conversation_export_selection_contract",
    "test_conversation_delete_close_contract", "test_shared_sheet_spacing_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


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


MENU_NAVIGATION_PLATFORM_CLASSES = tuple("app.daiyu.android." + name for name in (
    "ChatMenuEntryActivityTest", "ModalRouteBackgroundActivityTest", "ChatChromeActivityTest",
    "ConversationPaneVisualTest",
))
MENU_NAVIGATION_MODULES = MENU_MODULES + (
    "test_modal_route_background_contract", "test_sidebar_selection_contract",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


PAGE_BACK_PLATFORM_CLASSES = (
    'app.daiyu.android.ui.navigation.PageBackGesturesActivityTest',
    'app.daiyu.android.ui.navigation.PageBackStackActivityTest',
    'app.daiyu.android.ui.navigation.PageEnterActivityTest',
    'app.daiyu.android.ui.navigation.PageRetainedContentActivityTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest',
)
PAGE_BACK_GESTURE_RETAINED_CLASSES = (
    'app.daiyu.android.ui.navigation.PageBackGesturesActivityTest',
    'app.daiyu.android.ui.navigation.PageRetainedContentActivityTest',
)
PAGE_BACK_MODULES = (
    'test_page_back_gesture_contract', 'test_page_back_repeat_contract',
    'test_page_enter_contract', 'test_chat_return_refresh_contract',
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
TASK_SCROLL_COMPLEMENT_CLASSES = (
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageSpacingTest',
    'app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningTypingTest',
    'app.daiyu.android.ui.screens.chat.conversation.MarkdownRevealTraversalTest',
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


SESSION_ENTRY_PLATFORM_CLASSES = MARKDOWN_ENTRY_PLATFORM_CLASSES + (
    'app.daiyu.android.ui.screens.chat.conversation.ChatInitialPositionTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatViewportFrameTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatTimelineDividerTest',
)
SESSION_ENTRY_MODULES = MARKDOWN_ENTRY_MODULES + (
    'test_chat_initial_position_contract', 'test_chat_timeline_contract', 'test_message_display_contract',
)


USAGE_STATS_PLATFORM_CLASS = "app.daiyu.android.UsagePresentationActivityTest"
USAGE_STATS_MODULES = (
    "test_chat_usage_ready_contract", "test_context_usage_wiring_contract",
    "test_usage_tab_retention_contract", "test_usage_heatmap_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
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


PANE_RECEIPT_PLATFORM_CLASSES = (
    "app.daiyu.android.ConversationPaneSwitchActivityTest",
    "app.daiyu.android.ToolReceiptPauseActivityTest",
)
PANE_RECEIPT_MODULES = (
    "test_sidebar_selection_contract", "test_tool_receipt_snapshot_contract", "test_message_display_contract",
    "test_tool_receipt_pause_contract", "test_pause_receipt_smoke_scope", "test_test_refresh_contract",
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

RECEIPT_SUMMARY_PLATFORM_CLASSES = (
    "app.daiyu.android.ToolReceiptPauseActivityTest",
    "app.daiyu.android.ui.screens.chat.conversation.ToolCardReadabilityTest",
    "app.daiyu.android.ui.screens.chat.conversation.ReasoningWorkflowTest",
)
RECEIPT_SUMMARY_MODULES = tuple(dict.fromkeys(PAUSE_RECEIPT_MODULES + (
    "test_reasoning_workflow_contract", "test_tool_card_html_contract",
    "test_streaming_freeze_contract",
)))



TERMINAL_SCREEN_PLATFORM_CLASS = "app.daiyu.android.terminal.TerminalScreenTest"
TERMINAL_SCREEN_MODULES = (
    "test_terminal_output_cloud_contract", "test_terminal_geometry_contract",
    "test_terminal_reference_contract", "test_terminal_sheet_style_contract",
    "test_chroot_workspace_command_contract", "test_record_tool_tests_scope",
    "test_acceptance_tool_receipt", "test_terminal_screen_smoke_scope",
    "test_diagnostic_smoke_source",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


LINUX_SETTINGS_PLATFORM_CLASS = "app.daiyu.android.ui.screens.settings.LinuxSettingsScreenTest"
LINUX_SETTINGS_MODULES = (
    "test_linux_install_button_layout_contract", "test_linux_install_choice_contract",
    "test_linux_files_style_contract", "test_record_tool_tests_scope",
    "test_acceptance_tool_receipt", "test_linux_settings_smoke_scope",
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
CHILD_DELEGATION_PLATFORM_CLASSES = CHILD_TASK_CARD_PLATFORM_CLASSES + (
    "app.daiyu.android.ControllerChildModelsTest",
)
CHILD_TASK_STATUS_PLATFORM_CLASSES = CHILD_DELEGATION_PLATFORM_CLASSES + (
    "app.daiyu.android.ControllerModelPreparationTest",
    "app.daiyu.android.storage.SQLiteChildTaskStoreTest",
)
CHILD_TASK_CARD_INHERITANCE_CLASSES = CHILD_TASK_CARD_PLATFORM_CLASSES + (
    "app.daiyu.android.ConversationSelectionInheritanceActivityTest",
)
CHILD_TASK_CARD_THEME_CLASSES = CHILD_TASK_CARD_INHERITANCE_CLASSES + (
    "app.daiyu.android.ChatThemeInteractionActivityTest",
)
CHILD_TASK_CARD_READ_ONLY_CLASSES = (
    "app.daiyu.android.ui.screens.chat.conversation.ChildTaskListTest",
    "app.daiyu.android.ChatThemeInteractionActivityTest",
    "app.daiyu.android.ui.screens.chat.conversation.WorkflowDisclosureDirectionTest",
)
CHILD_TASK_CARD_MODULES = (
    "test_delegation_batch_and_send_contract", "test_child_stop_hot_configuration_contract",
    "test_subagent_resume_display_contract", "test_subagent_collaboration_contract",
    "test_sidebar_task_entry_contract", "test_child_task_card_contract", "test_chat_return_refresh_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


CHILD_STOP_PLATFORM_CLASSES = (
    "app.daiyu.android.ChildStopChoiceActivityTest",
    "app.daiyu.android.ControllerChildRetentionTest",
)
CHILD_STOP_MODULES = (
    "test_child_stop_hot_configuration_contract", "test_run_controls_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)

RUNTIME_STOP_PLATFORM_CLASSES = ("app.daiyu.android.terminal.TerminalRegistryTest",) + CHILD_STOP_PLATFORM_CLASSES
RUNTIME_STOP_MODULES = tuple(dict.fromkeys((
    "test_terminal_job_status_contract", "test_terminal_reference_contract",
    "test_chroot_workspace_command_contract", "test_terminal_command_pager_smoke_scope",
) + CHILD_STOP_MODULES))


POPUP_SCROLL_PLATFORM_CLASSES = ('app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest', 'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest', 'app.daiyu.android.PreviewDialogStatusBarActivityTest', 'app.daiyu.android.AssistantPickerAdmissionTest', 'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingFollowTest')
POPUP_SCROLL_MODULES = ('test_streaming_occlusion_contract', 'test_streaming_follow_contract', 'test_chat_resume_scroll_contract', 'test_preview_dialog_status_bar_contract', 'test_assistant_picker_frame_contract', 'test_chat_menu_scroll_isolation_contract', 'test_popup_performance_contract', 'test_record_tool_tests_scope', 'test_acceptance_tool_receipt', 'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope')

RENDERING_PLATFORM_CLASSES = ('app.daiyu.android.ui.screens.chat.conversation.RenderingRegressionTest', 'app.daiyu.android.ui.screens.chat.conversation.MessageMarkdownTest', 'app.daiyu.android.ui.screens.chat.conversation.MarkdownTableStyleTest', 'app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningMarkdownTest', 'app.daiyu.android.ui.screens.chat.conversation.ReasoningWorkflowTest', 'app.daiyu.android.RestoredMarkdownActivityTest')
RENDERING_MODULES = ('test_reasoning_workflow_contract', 'test_work_timeline_connector_contract', 'test_workflow_render_isolation_contract', 'test_markdown_typing_contract', 'test_markdown_body_visibility_contract', 'test_markdown_table_style_contract', 'test_markdown_code_block_contract', 'test_markdown_entry_contract', 'test_markdown_cold_start_contract', 'test_markdown_return_contract', 'test_markdown_stream_progress_contract', 'test_markdown_stream_optimization_contract', 'test_message_display_contract', 'test_rendering_smoke_scope', 'test_record_tool_tests_scope', 'test_acceptance_tool_receipt')


SCROLL_POPUP_PLATFORM_CLASSES = ('app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest', 'app.daiyu.android.ui.screens.chat.conversation.MessageMarkdownTest', 'app.daiyu.android.OrdinaryDialogLifecycleActivityTest', 'app.daiyu.android.ui.screens.chat.conversation.ChatModelPickerLayoutTest')
SCROLL_POPUP_MODULES = tuple(dict.fromkeys(POPUP_SCROLL_MODULES + RENDERING_MODULES + ('test_model_switch_feedback_contract', 'test_selection_stays_open_contract', 'test_model_picker_caption_contract', 'test_model_selection_switch_state_contract', 'test_voice_settings_parity_contract', 'test_chat_gpt_speed_contract', 'test_model_picker_balance_contract', 'test_conversation_model_binding_contract')))

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


POPUP_TITLE_PLATFORM_CLASSES = tuple("app.daiyu.android." + name for name in (
    "PopupTitleGeometryTest", "AssistantPickerVisualTest", "SharedSheetSpacingActivityTest", "CollaborationEmptyStateTest",
))
POPUP_TITLE_MODULES = (
    "test_test_refresh_contract",
    "test_popup_title_contract", "test_shared_sheet_spacing_contract", "test_assistant_picker_frame_contract",
    "test_chat_attachment_sizing_contract", "test_voice_tone_selection_contract",
    "test_bottom_sheet_edge_contract", "test_voice_personal_menu_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


COMPRESSION_DIALOG_PLATFORM_CLASSES = tuple("app.daiyu.android." + name for name in (
    "ContextManagementPanelTest", "CompressionConversationDialogTest",
    "ContextManagementEntryActivityTest", "ControllerContextManagementEntryTest",
))
COMPRESSION_DIALOG_MODULES = (
    "test_context_dialog_initialization_contract", "test_compression_presentation_resume_contract",
    "test_context_route_lifecycle_contract", "test_compression_protocol_help_contract",
    "test_summary_protocol_preference_contract", "test_record_tool_tests_scope",
    "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


WORKSPACE_ENTRY_PLATFORM_CLASSES = (
    "app.daiyu.android.WorkspaceEntryActionsActivityTest", "app.daiyu.android.PrivateWorkspacePanelTest",
)
WORKSPACE_ENTRY_MODULES = (
    "test_workspace_entry_mutations_contract", "test_linux_files_style_contract",
    "test_workspace_picker_contract", "test_workspace_delete_scope",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


WORKSPACE_IMPORT_PLATFORM_CLASSES = (
    "app.daiyu.android.workspace.PrivateWorkspaceFilesTest", "app.daiyu.android.PrivateWorkspacePanelTest",
)
WORKSPACE_IMPORT_MODULES = (
    "test_workspace_files_entry_contract", "test_workspace_entry_mutations_contract",
    "test_linux_files_style_contract",
    "test_workspace_import_scope", "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


WORKFLOW_BODY_PLATFORM_CLASSES = (
    "app.daiyu.android.ui.screens.chat.conversation.ReasoningWorkflowTest",
    "app.daiyu.android.ui.screens.chat.conversation.ReconnectBoundaryPlacementTest",
)
WORKFLOW_BODY_MODULES = (
    "test_reasoning_workflow_contract", "test_workflow_narration_contract",
    "test_workflow_render_isolation_contract", "test_workflow_body_scope",
    "test_markdown_typing_contract", "test_chat_resume_scroll_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


WORKFLOW_DISCLOSURE_PLATFORM_CLASSES = tuple("app.daiyu.android.ui.screens.chat.conversation." + name for name in (
    "WorkflowDisclosureDirectionTest", "ReasoningWorkflowTest", "ChatStreamingOcclusionTest",
    "ChatStreamingFollowTest", "ChatMessageListMeasurePhaseTest", "ChatResumeScrollTest",
))
WORKFLOW_COLLAPSE_PLATFORM_CLASSES = tuple("app.daiyu.android.ui.screens.chat.conversation." + name for name in (
    "WorkflowDisclosureDirectionTest", "ChatMessageListMeasurePhaseTest",
))
WORKFLOW_DISCLOSURE_MODULES = WORKFLOW_BODY_MODULES + (
    "test_reasoning_flow_stability_contract", "test_streaming_occlusion_contract",
    "test_streaming_follow_contract", "test_workflow_disclosure_scope", "test_workflow_disclosure_coverage_contract",
    "test_disclosure_consistency_contract",
)


DOUBAO_MOTION_PLATFORM_CLASSES = tuple("app.daiyu.android.ui.screens.chat.conversation." + name for name in (
    "DoubaoCallOverlayTest", "DoubaoCallOrbPixelsTest", "DoubaoCallOverlayZeroDurationTest"))
DOUBAO_MOTION_REASONING_PLATFORM_CLASSES = DOUBAO_MOTION_PLATFORM_CLASSES + ("app.daiyu.android.ControllerReasoningFreezeTest",)
DOUBAO_MOTION_VOICE_PLATFORM_CLASSES = DOUBAO_MOTION_REASONING_PLATFORM_CLASSES + ("app.daiyu.android.storage.ConversationModelBindingDatabaseTest",)
DOUBAO_MOTION_FLOATING_PLATFORM_CLASSES = ("app.daiyu.android.VoiceCallFloatingWindowTest",) + DOUBAO_MOTION_VOICE_PLATFORM_CLASSES
DOUBAO_MOTION_MODULES = ("test_doubao_call_sheet_contract", "test_doubao_motion_scope", "test_voice_call_contract",
    "test_smoke_boot.SourceTests.test_call_clock_fixture_keeps_strict_raster_assertions",
    "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope")


VOICE_REFRESH_PLATFORM_CLASSES = (
    "app.daiyu.android.VoiceSettingsStructureActivityTest",
    "app.daiyu.android.VoiceQuotaSelectionActivityTest",
)
VOICE_REFRESH_MODULES = (
    "test_voice_recognition_layout_contract", "test_voice_settings_parity_contract",
    "test_voice_settings_palette_contract", "test_voice_refresh_smoke_scope",
    "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


SUBAGENT_PRESET_PLATFORM_CLASSES = (
    "app.daiyu.android.SubAgentPresetActionsSheetTest",
    "app.daiyu.android.SubAgentPresetDetailPresentationTest",
)
SUBAGENT_PRESET_MODULES = (
    "test_subagent_preset_actions_theme_contract", "test_subagent_profile_delete_contract",
    "test_subagent_add_button_contract", "test_subagent_always_enabled_contract",
    "test_settings_copy_cleanup_contract.SettingsCopyCleanupContractTest.test_subagent_intro_removed_without_hiding_directory_status_or_card_summary",
    "test_subagent_preset_scope", "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


PROVIDER_SAVE_PLATFORM_CLASSES = (
    "app.daiyu.android.ProviderSavePositionActivityTest",
    "app.daiyu.android.ControllerProviderSaveNoticeTest",
    "app.daiyu.android.ControllerModelSettingsTest",
    "app.daiyu.android.ui.screens.settings.ProviderInlineEditorsTest",
    "app.daiyu.android.ui.screens.settings.ProviderSaveValidationTest",
    "app.daiyu.android.RequestBodyFieldsActivityTest",
)
PROVIDER_SAVE_BINDING_PLATFORM_CLASSES = (*PROVIDER_SAVE_PLATFORM_CLASSES,
    "app.daiyu.android.storage.ModelSettingsCatalogBindingTest")
PROVIDER_SAVE_MODULES = (
    "test_provider_save_position_contract", "test_provider_save_validation_contract",
    "test_provider_search_save_contract", "test_provider_directory_selection_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


CATALOG_MODEL_EDITOR_PLATFORM_CLASS = "app.daiyu.android.ui.screens.settings.CatalogModelEditorLayoutTest"
CATALOG_MODEL_EDITOR_MODULES = (
    "test_model_editor_defaults_contract", "test_catalog_model_editor_smoke_scope",
    "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


SEARCH_BROWSER_PLATFORM_CLASSES = (
    CATALOG_MODEL_EDITOR_PLATFORM_CLASS,
    "app.daiyu.android.storage.ModelSettingsCatalogBindingTest",
    "app.daiyu.android.browser.BrowserProductionCompositionTest",
)
SEARCH_BROWSER_MODULES = tuple(dict.fromkeys(
    CATALOG_MODEL_EDITOR_MODULES + PROVIDER_SAVE_MODULES + BROWSER_MODULES
    + ("test_rikkahub_protocol_port_contract",)
))


PROVIDER_CATALOG_PLATFORM_CLASSES = (
    "app.daiyu.android.ui.screens.settings.ProviderCatalogSettingsScreenTest",
    "app.daiyu.android.LocalCatalogModelsActivityTest",
)
PROVIDER_CATALOG_MODULES = (
    "test_provider_catalog_tabs_contract", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


SEARCH_PLATFORM_CLASSES = (
    "app.daiyu.android.SearchHistoryDialogTest",
    "app.daiyu.android.SearchHostActivityTest",
    "app.daiyu.android.RetainedSearchDataTest",
    "app.daiyu.android.SearchSQLiteCancellationTest",
)
SEARCH_MODULES = (
    "test_search_history_sheet_contract", "test_search_host_start_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


CONVERSATION_DATE_PLATFORM_CLASS = "app.daiyu.android.storage.ConversationDateGroupingTest"
CONVERSATION_DATE_MODULES = ('test_conversation_date_contract', 'test_conversation_date_smoke_scope', 'test_record_tool_tests_scope', 'test_acceptance_tool_receipt', 'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope')


IME_PLATFORM_CLASSES = (
    'app.daiyu.android.ui.navigation.PageImeCompositionActivityTest',
    'app.daiyu.android.ChatComposerImeActivityTest',
    'app.daiyu.android.ChatAttachmentSizingActivityTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatComposerMeasurePhaseTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatResumeScrollTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest',
    'app.daiyu.android.ManagedInputActivityTest',
)
IME_MODULES = (
    'test_chat_return_refresh_contract.ContentRecoveryWitnessContract.test_chat_status_bar_space_ignores_visibility_but_keeps_live_bottom_insets',
    'test_managed_input_contract',
    'test_chat_ime_hotpath_contract',
    'test_chat_ime_lifecycle_contract',
    'test_chat_ime_motion_contract',
    'test_ime_frame_contract',
    'test_chat_attachment_sizing_contract',
    'test_chat_resume_scroll_contract',
    'test_streaming_occlusion_contract',
    'test_chat_ime_smoke_scope',
    'test_record_tool_tests_scope',
    'test_acceptance_tool_receipt',
    'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope',
)


PET_PRESENTATION_PLATFORM_CLASSES = tuple("app.daiyu.android.pet." + name for name in (
    "PetOverlayHostTest", "PetHostTest", "PetApplicationHostTest", "PetHostCloseTest",
    "PetCupboardDialogTest", "PetPresentationTest",
))
PET_PRESENTATION_MODULES = (
    "test_pet_presentation_contract", "test_pet_presentation_smoke_scope",
    "test_pet_error_logging_contract", "test_pet_settings_disclosure_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


PET_MODEL_PLATFORM_CLASSES = (
    "app.daiyu.android.pet.AndroidPetStoreCapacityTest",
    "app.daiyu.android.pet.AndroidPetStoreTest",
    "app.daiyu.android.pet.PetThinkingPickerActivityTest",
    "app.daiyu.android.pet.PetCupboardDialogTest",
    "app.daiyu.android.pet.PetApplicationHostTest",
    "app.daiyu.android.pet.PetHostTest",
    "app.daiyu.android.IndependentModelProductionTest",
)
PET_MODEL_MODULES = (
    "test_pet_model_reasoning_contract", "test_pet_model_smoke_scope",
    "test_pet_error_logging_contract", "test_pet_settings_disclosure_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)

WORKFLOW_NODE_MOTION_PLATFORM_CLASSES = (
    "app.daiyu.android.ui.screens.chat.conversation.WorkflowNodeMotionTest",
    "app.daiyu.android.ui.screens.chat.conversation.WorkTimelineConnectorTest",
)
WORKFLOW_NODE_MOTION_MODULES = (
    "test_reasoning_workflow_contract", "test_work_timeline_connector_contract",
    "test_workflow_render_isolation_contract", "test_workflow_narration_contract",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


WORKSPACE_GESTURE_PLATFORM_CLASS = "app.daiyu.android.ConversationWorkspaceActivityTest"
WORKSPACE_GESTURE_MODULES = (
    "test_workspace_picker_contract", "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


GENERATION_PORT_PLATFORM_CLASSES = (
    'app.daiyu.android.GenerationInputImportTest',
    'app.daiyu.android.storage.SQLiteGenerationAttemptStoreTest',
    'app.daiyu.android.storage.SQLiteGenerationOutputStoreTest',
    'app.daiyu.android.integration.tools.RunGenerationToolsDeviceTest',
    'app.daiyu.android.ControllerGenerationResourcesTest',
    'app.daiyu.android.ControllerGenerationIntegrationTest',
    'app.daiyu.android.storage.GenerationProtocolMigrationTest',
)
GENERATION_PORT_MODULES = (
    'test_generation_port_scope',
    'test_record_tool_tests_scope',
    'test_acceptance_tool_receipt',
    'test_frozen_smoke_source',
    'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope',
    'test_smoke_boot.ProcessTests.test_four_shards_are_disjoint_deterministic_and_complete',
    'test_smoke_boot.ProcessTests.test_strict_shared_result_rejects_ambiguous_and_failed_protocol',
    'test_smoke_boot.ProcessTests.test_targeted_count_mismatch_or_runner_failure_is_not_success',
)


CHILD_HOT_CONFIGURATION_PLATFORM_CLASSES = (
    "app.daiyu.android.ChildStopChoiceActivityTest",
    "app.daiyu.android.ControllerChildRetentionTest",
    "app.daiyu.android.ControllerChildModelsTest",
    "app.daiyu.android.storage.SQLiteChildTaskStoreTest",
    "app.daiyu.android.model.usage.AttributedUsageDatabaseTest",
    "app.daiyu.android.backup.TypedUsageBackupTest",
    "app.daiyu.android.ControllerGenerationIntegrationTest",
    "app.daiyu.android.integration.tools.RunGenerationToolsDeviceTest",
    "app.daiyu.android.storage.SQLiteGenerationAttemptStoreTest",
    "app.daiyu.android.storage.SQLiteGenerationOutputStoreTest",
)
CHILD_HOT_CONFIGURATION_MODULES = tuple(dict.fromkeys(
    CHILD_STOP_MODULES + GENERATION_PORT_MODULES + (
        "test_child_hot_configuration_scope", "test_windows_unit_skips",
    )
))


def child_hot_configuration_scope(raw):
    parts = raw.split(",")
    if sorted(parts) == sorted(CHILD_HOT_CONFIGURATION_PLATFORM_CLASSES):
        return True
    if len({part.strip() for part in parts} & set(CHILD_HOT_CONFIGURATION_PLATFORM_CLASSES)) >= len(CHILD_HOT_CONFIGURATION_PLATFORM_CLASSES) - 1:
        raise ValueError("Exact child hot configuration smoke scope required")
    return False


RETURN_CHAT_HANDOFF_PLATFORM_CLASSES = PAGE_BACK_PLATFORM_CLASSES + (
    "app.daiyu.android.RetainedToolOutcomesTest",
    "app.daiyu.android.ToolReceiptPauseActivityTest",
    "app.daiyu.android.ToolScreenshotPreviewActivityTest",
    "app.daiyu.android.ui.ToolScreenshotReceiptTest",
    "app.daiyu.android.ui.screens.chat.conversation.ChildTaskListTest",
    "app.daiyu.android.ControllerGenerationResourcesTest",
    "app.daiyu.android.ControllerGenerationIntegrationTest",
)
RETURN_CHAT_HANDOFF_MODULES = tuple(dict.fromkeys(
    PAGE_BACK_MODULES + PAUSE_RECEIPT_MODULES + SCREEN_CLEANUP_MODULES
    + CHILD_TASK_CARD_MODULES + GENERATION_PORT_MODULES
))


MESSAGE_ACTION_PLATFORM_CLASSES = ('app.daiyu.android.ControllerMessageActionsTest', 'app.daiyu.android.MessageActionsActivityTest', 'app.daiyu.android.MessageConfirmationActivityTest', 'app.daiyu.android.ui.screens.chat.conversation.MessageActionBarLayoutTest', 'app.daiyu.android.ui.screens.chat.conversation.MessageActionAvailabilityTest')
MESSAGE_ACTION_MODULES = (
    "test_message_generation_actions_contract", "test_message_action_snapshot_admission_contract",
    "test_message_action_scroll_contract", "test_message_confirmation_contract",
    "test_branch_configuration_inheritance_contract", "test_message_timestamp_style_contract",
    "test_record_tool_tests_scope",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


QUESTION_CARD_PLATFORM_CLASSES = (
    "app.daiyu.android.ui.screens.chat.conversation.QuestionCardReadabilityTest",
    "app.daiyu.android.question.QuestionCardBindingTest",
    "app.daiyu.android.question.QuestionTest",
)
QUESTION_CARD_MODULES = (
    "test_question_card_readability_contract", "test_stream_question_projection_contract",
    "test_question_card_smoke_scope",
)


PANE_QUESTION_PLATFORM_CLASSES = (
    "app.daiyu.android.QuestionBarrierIntegrationTest",
    "app.daiyu.android.ConversationPaneSwitchActivityTest",
    "app.daiyu.android.ui.screens.chat.conversation.MessageActionBarLayoutTest",
    "app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest",
    "app.daiyu.android.ui.screens.chat.conversation.WorkflowDisclosureDirectionTest",
)
PANE_QUESTION_CORE_PLATFORM_CLASSES = PANE_QUESTION_PLATFORM_CLASSES[:3]
PANE_QUESTION_MODULES = (
    "test_stream_question_projection_contract", "test_sidebar_selection_contract",
    "test_tool_receipt_snapshot_contract", "test_tool_receipt_pause_contract",
    "test_message_display_contract", "test_message_action_snapshot_admission_contract",
    "test_message_generation_actions_contract", "test_message_timestamp_style_contract",
    "test_streaming_occlusion_contract", "test_streaming_follow_contract",
    "test_workflow_disclosure_scope", "test_workflow_disclosure_coverage_contract",
    "test_disclosure_consistency_contract", "test_pane_question_smoke_scope",
    "test_record_tool_tests_scope", "test_acceptance_tool_receipt", "test_test_refresh_contract",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


MEDIA_SEND_PLATFORM_CLASSES = (
    "app.daiyu.android.ImageChatHostActivityTest",
    "app.daiyu.android.ui.screens.chat.attachments.ImageAttachmentsUiTest",
    "app.daiyu.android.ui.screens.chat.attachments.VideoAttachmentsUiTest",
    "app.daiyu.android.ui.MessageImageRenderingTest",
    "app.daiyu.android.RetainedToolOutcomesTest",
    "app.daiyu.android.ToolReceiptPauseActivityTest",
    "app.daiyu.android.ChatReferenceEntryActivityTest",
)
MEDIA_SEND_MODULES = (
    "test_media_send_contract", "test_image_sidebar_visibility_contract",
    "test_message_composer_edit_contract", "test_tool_receipt_snapshot_contract",
    "test_tool_receipt_pause_contract", "test_record_tool_tests_scope",
    "test_acceptance_tool_receipt",
    "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope",
)


def command_for(python, env):
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(MEDIA_SEND_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *MEDIA_SEND_MODULES, "-v"], "media-send-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(SEARCH_BROWSER_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *SEARCH_BROWSER_MODULES, "-v"], "browser-targeted"
    voice = ["app.daiyu.android.ControllerModelPreparationTest"]
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) in (
                voice, voice + ["app.daiyu.android.DictationActivityTest"])):
        return [python, "-B", "-m", "unittest", "test_voice_call_contract", "test_voice_browser_smoke_scope",
                "test_browser_navigation_defaults_contract", "test_windows_unit_skips", "-v"], "voice-browser-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) in
                (sorted(BROWSER_PLATFORM_CLASSES), sorted(BROWSER_LIFECYCLE_PLATFORM_CLASSES))):
        return [python, "-B", "-m", "unittest", *BROWSER_MODULES, "-v"], "browser-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and child_hot_configuration_scope(env.get("SMOKE_TEST_CLASSES", ""))):
        return [python, "-B", "-m", "unittest", *CHILD_HOT_CONFIGURATION_MODULES, "-v"], "child-hot-configuration-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(PANE_QUESTION_CORE_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *PANE_QUESTION_MODULES, "-v"], "pane-question-core-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(PANE_QUESTION_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *PANE_QUESTION_MODULES, "-v"], "pane-question-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(RUNTIME_STOP_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *RUNTIME_STOP_MODULES, "-v"], "runtime-stop-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(CHILD_STOP_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *CHILD_STOP_MODULES, "-v"], "child-stop-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == "app.daiyu.android.ui.screens.chat.conversation.ChatTimelineDividerTest"):
        return [python, "-B", "-m", "unittest", "test_chat_timeline_contract",
                "test_reasoning_flow_stability_contract", "test_record_tool_tests_scope",
                "test_acceptance_tool_receipt",
                "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope", "-v"], "timeline-divider-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(QUESTION_CARD_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *QUESTION_CARD_MODULES, "-v"], "question-card-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(RETURN_CHAT_HANDOFF_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *RETURN_CHAT_HANDOFF_MODULES, "-v"], "return-chat-handoff-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == "app.daiyu.android.ChatSubmissionActivityTest"):
        return [python, "-B", "-m", "unittest", "test_chat_submission_preview_contract",
                "test_message_confirmation_contract", "-v"], "regeneration-preparation-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(MESSAGE_ACTION_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *MESSAGE_ACTION_MODULES, "-v"], "message-actions-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(GENERATION_PORT_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *GENERATION_PORT_MODULES, "-v"], "generation-port-targeted"
    if env.get("SMOKE_MODE") == "smoke" and env.get("SMOKE_TEST_CLASSES") == WORKSPACE_GESTURE_PLATFORM_CLASS:
        return [python, "-B", "-m", "unittest", *WORKSPACE_GESTURE_MODULES, "-v"], "workspace-gesture-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(WORKFLOW_NODE_MOTION_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *WORKFLOW_NODE_MOTION_MODULES, "-v"], "workflow-node-motion-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(PET_MODEL_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *PET_MODEL_MODULES, "-v"], "pet-model-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == CATALOG_MODEL_EDITOR_PLATFORM_CLASS):
        return [python, "-B", "-m", "unittest", *CATALOG_MODEL_EDITOR_MODULES, "-v"], "catalog-model-editor-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(PET_PRESENTATION_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *PET_PRESENTATION_MODULES, "-v"], "pet-presentation-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(DELETE_CONFIRM_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *DELETE_CONFIRM_MODULES, "-v"], "delete-confirm-targeted"
    if env.get("SMOKE_MODE") == "smoke" and env.get("SMOKE_TEST_CLASSES") == USAGE_STATS_PLATFORM_CLASS:
        return [python, "-B", "-m", "unittest", *USAGE_STATS_MODULES, "-v"], "usage-statistics-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(SUBAGENT_PRESET_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *SUBAGENT_PRESET_MODULES, "-v"], "subagent-presets-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(BATCH_DELETE_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *BATCH_DELETE_MODULES, "-v"], "conversation-batch-delete-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) in (
                sorted(PROVIDER_SAVE_PLATFORM_CLASSES), sorted(PROVIDER_SAVE_BINDING_PLATFORM_CLASSES))):
        return [python, "-B", "-m", "unittest", *PROVIDER_SAVE_MODULES, "-v"], "provider-save-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(IME_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *IME_MODULES, "-v"], "ime-targeted"
    if env.get("SMOKE_MODE") == "smoke" and env.get("SMOKE_TEST_CLASSES") == CONVERSATION_DATE_PLATFORM_CLASS:
        return [python, "-B", "-m", "unittest", *CONVERSATION_DATE_MODULES, "-v"], "conversation-date-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(SEARCH_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *SEARCH_MODULES, "-v"], "search-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(PROVIDER_CATALOG_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *PROVIDER_CATALOG_MODULES, "-v"], "provider-catalog-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and (env.get("SMOKE_TEST_CLASSES") == "app.daiyu.android.VoiceRecognitionRefreshActivityTest"
                 or sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(VOICE_REFRESH_PLATFORM_CLASSES))):
        return [python, "-B", "-m", "unittest", *VOICE_REFRESH_MODULES, "-v"], "voice-refresh-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == [
                "app.daiyu.android.ChatComposerImeActivityTest", "app.daiyu.android.VoiceCallFloatingWindowTest"]):
        return [python, "-B", "-m", "unittest", *DOUBAO_MOTION_MODULES,
                "test_chat_ime_motion_contract", "test_ime_frame_contract", "-v"], "doubao-motion-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) in (sorted(DOUBAO_MOTION_PLATFORM_CLASSES), sorted(DOUBAO_MOTION_REASONING_PLATFORM_CLASSES), sorted(DOUBAO_MOTION_VOICE_PLATFORM_CLASSES), sorted(DOUBAO_MOTION_FLOATING_PLATFORM_CLASSES), ["app.daiyu.android.VoiceCallFloatingWindowTest"])):
        return [python, "-B", "-m", "unittest", *DOUBAO_MOTION_MODULES, "-v"], "doubao-motion-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(WORKFLOW_COLLAPSE_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *WORKFLOW_DISCLOSURE_MODULES, "-v"], "workflow-collapse-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(WORKFLOW_DISCLOSURE_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *WORKFLOW_DISCLOSURE_MODULES, "-v"], "workflow-disclosure-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(WORKFLOW_BODY_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *WORKFLOW_BODY_MODULES, "-v"], "workflow-body-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(WORKSPACE_IMPORT_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *WORKSPACE_IMPORT_MODULES, "-v"], "workspace-import-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(WORKSPACE_ENTRY_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *WORKSPACE_ENTRY_MODULES, "-v"], "workspace-entry-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(SCROLL_POPUP_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *SCROLL_POPUP_MODULES, "-v"], "scroll-popup-targeted"
    if env.get("SMOKE_MODE") == "smoke" and env.get("SMOKE_TEST_CLASSES") == "app.daiyu.android.PlatformTest":
        return [python, "-B", "-m", "unittest", "test_platform_smoke_scope", "test_test_refresh_contract", "test_acceptance_tool_receipt", "-v"], "platform-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(COMPRESSION_DIALOG_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *COMPRESSION_DIALOG_MODULES, "-v"], "compression-dialog-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == LINUX_SETTINGS_PLATFORM_CLASS):
        return [python, "-B", "-m", "unittest", *LINUX_SETTINGS_MODULES, "-v"], "linux-settings-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and env.get("SMOKE_TEST_CLASSES") == "app.daiyu.android.AssistantPickerVisualTest"):
        return [python, "-B", "-m", "unittest", "test_assistant_picker_frame_contract",
                "test_record_tool_tests_scope", "test_acceptance_tool_receipt",
                "test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope", "-v"], "assistant-picker-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(POPUP_TITLE_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *POPUP_TITLE_MODULES, "-v"], "popup-title-targeted"
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
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(RECEIPT_SUMMARY_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *RECEIPT_SUMMARY_MODULES, "-v"], "receipt-summary-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(PANE_RECEIPT_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *PANE_RECEIPT_MODULES, "-v"], "pane-receipts-targeted"
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
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(MENU_NAVIGATION_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *MENU_NAVIGATION_MODULES, "-v"], "menu-navigation-targeted"
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) == sorted(MENU_PLATFORM_CLASSES)):
        return [python, "-B", "-m", "unittest", *MENU_MODULES, "-v"], "menu-targeted"
    if (env.get('SMOKE_MODE') == 'smoke'
            and sorted(env.get('SMOKE_TEST_CLASSES', '').split(',')) in (
                sorted(PAGE_BACK_PLATFORM_CLASSES),
                sorted(PAGE_BACK_GESTURE_RETAINED_CLASSES),
                sorted((*PAGE_BACK_PLATFORM_CLASSES, 'app.daiyu.android.MessageActionsActivityTest')))):
        return [python, '-B', '-m', 'unittest', *PAGE_BACK_MODULES, '-v'], 'page-back-targeted'
    if (env.get('SMOKE_MODE') == 'smoke'
            and sorted(env.get('SMOKE_TEST_CLASSES', '').split(',')) in (
                sorted(TASK_SCROLL_PLATFORM_CLASSES), sorted(TASK_SCROLL_COMPLEMENT_CLASSES),
                ['app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest'])):
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
            and sorted(env.get('SMOKE_TEST_CLASSES', '').split(',')) == sorted(SESSION_ENTRY_PLATFORM_CLASSES)):
        return [python, '-B', '-m', 'unittest', *SESSION_ENTRY_MODULES, '-v'], 'session-entry-targeted'
    if (env.get('SMOKE_MODE') == 'smoke'
            and sorted(env.get('SMOKE_TEST_CLASSES', '').split(',')) == sorted(MARKDOWN_ENTRY_PLATFORM_CLASSES)):
        return [python, '-B', '-m', 'unittest', *MARKDOWN_ENTRY_MODULES, '-v'], 'markdown-entry-targeted'
    if (env.get("SMOKE_MODE") == "smoke"
            and sorted(env.get("SMOKE_TEST_CLASSES", "").split(",")) in (
                sorted(CHILD_TASK_CARD_PLATFORM_CLASSES), sorted(CHILD_TASK_CARD_INHERITANCE_CLASSES),
                sorted(CHILD_TASK_CARD_THEME_CLASSES), sorted(CHILD_DELEGATION_PLATFORM_CLASSES),
                sorted(CHILD_TASK_CARD_READ_ONLY_CLASSES), sorted(CHILD_TASK_STATUS_PLATFORM_CLASSES))):
        return [python, "-B", "-m", "unittest", *CHILD_TASK_CARD_MODULES, "-v"], "child-task-cards-targeted"
    return [python, "-B", "-m", "unittest", "discover", "-s", "tests", "-v"], "full"


def record(python):
    sha = os.environ["GITHUB_SHA"]
    if not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", sha):
        raise ValueError("invalid CI SHA")
    workflow_sha = sha
    expected = os.environ.get("EXPECTED_SHA", "")
    if os.environ.get("SMOKE_MODE") == "smoke" and expected:
        if not re.fullmatch(r"[0-9a-f]{40}", expected):
            raise ValueError("invalid smoke checkout SHA")
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        if sha != expected:
            raise ValueError("smoke checkout SHA mismatch")
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
    validation_cwd = None
    if scope in ("media-send-targeted", "receipt-summary-targeted", "runtime-stop-targeted", "workflow-collapse-targeted", "voice-browser-targeted", "browser-targeted", "doubao-motion-targeted", "child-hot-configuration-targeted", "child-stop-targeted", "generation-port-targeted", "message-actions-targeted", "return-chat-handoff-targeted", "pane-question-targeted", "pane-question-core-targeted", "timeline-divider-targeted", "task-scroll-targeted", "page-back-targeted") or (
            scope == "child-task-cards-targeted" and sorted(os.environ.get("SMOKE_TEST_CLASSES", "").split(","))
            == sorted(CHILD_TASK_CARD_READ_ONLY_CLASSES)):
        validation_cwd = Path(__file__).resolve().parents[2]
        child_env["PYTHONPATH"] = str(validation_cwd / "tests") + os.pathsep + os.environ.get("PYTHONPATH", "")
    log = root / "unittest.log"
    with log.open("wb") as output:
        if validation_cwd is None:
            code = subprocess.call(command, stdout=output, stderr=subprocess.STDOUT, env=child_env)
        else:
            code = subprocess.call(command, stdout=output, stderr=subprocess.STDOUT, env=child_env, cwd=validation_cwd)
    result = dict(identity=identity, command=command, exit_code=code)
    if sha != workflow_sha:
        result["workflow_sha"] = workflow_sha
        result["validation_tools_sha"] = workflow_sha
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
