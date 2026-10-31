"""Source-only wiring contracts; draft persistence and gestures use Compose tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
COMPONENTS = SRC / "ui/components"


class SubAgentThinkingPickerContractTest(unittest.TestCase):
    def test_subagent_picker_always_uses_the_new_surface(self):
        text = (COMPONENTS / "subagent/ThinkingEffortPicker.kt").read_text()
        self.assertIn("mainChat: Boolean = false", text)
        self.assertIn("if (!show || options.isEmpty()) return", text)
        self.assertIn("LocalConversationAgentStyle provides !mainChat", text)
        self.assertIn("ConversationThinkingPicker(show, effort, options, onDismiss, onEffortChange, speedMode, onCycleSpeed, speedEnabled)", text)
        self.assertNotIn("WindowDialog", text)
        self.assertNotIn("SliderDefaults", text)

    def test_text_request_choices_do_not_depend_on_ui_style_or_declared_capabilities(self):
        text = (COMPONENTS / "subagent/SubAgentThinkingPreference.kt").read_text()
        self.assertNotIn("chatStyle", text)
        self.assertNotIn("LocalConversationAgentStyle", text)
        text_branch, media_branch = text.split("return SubAgentThinkingPreference(SubAgentPreferences", 1)
        self.assertIn("if (!profile.isMedia)", text_branch)
        self.assertIn("ReasoningEffort.textPreference(profile.reasoning, config.effectiveReasoningEffort, config.model)", text_branch)
        self.assertIn("ReasoningEffort.textChoices(config.model)", text_branch)
        self.assertNotIn("config.reasoningCapabilities", text_branch)
        self.assertIn(".applyReasoning(profile, config).effectiveReasoningEffort", media_branch)
        self.assertIn("MediaReasoningSettings.resolve(config, profile.role).efforts", media_branch)

    def test_both_subagent_entry_points_use_style_independent_preferences(self):
        for name, subject in (("SubAgentProfileConfigDialog.kt", "draft"), ("SubAgentProfileRow.kt", "profile")):
            text = (COMPONENTS / name).read_text()
            self.assertIn(f"subAgentThinkingPreference({subject}, it)", text)
            self.assertNotRegex(text, r"subAgentThinkingPreference\([^\n]*LocalConversationAgentStyle")
            self.assertIn("SubAgentThinkingEffortPickerDialog(true,", text)
            self.assertNotIn("mainChat = true", text)
            self.assertIn("MediaReasoningSettings.Status.SUPPORTED", text)

    def test_main_chat_is_explicit_and_keeps_its_immediate_update(self):
        text = (SRC / "MainActivity.kt").read_text()
        picker = text.split("SubAgentThinkingEffortPickerDialog(show = thinkingPicker", 1)[1]
        self.assertIn("mainChat = true", picker)
        self.assertIn("if (allowTaskConfigurationChange(thinkingOwner?.let(::ConversationId))) try {", picker)
        self.assertIn("if (controller.setChatReasoning(thinkingOwner, effort)) publishChat()", picker)
        controller = (SRC / "ChatController.kt").read_text()
        setter = controller.split("internal fun setChatReasoning(", 1)[1].split("\n    }", 1)[0]
        self.assertIn("if (taskConfigurationBusy(owner?.let(::ConversationId))) return false\n"
                      "        reasoningPreference.set(owner, effort)\n"
                      "        return true", setter)
        shared = (COMPONENTS / "subagent/ConversationThinkingPicker.kt").read_text()
        self.assertIn("if (subagent) ConversationDepthSlider", shared)
        subagent, main_chat = shared.split("if (subagent) ConversationDepthSlider", 1)[1].split("else ConversationDepthSlider", 1)
        self.assertIn("{ selected = it }, { index ->", subagent)
        self.assertIn("if (choices[index] != effort) onEffortChange(choices[index])", subagent)
        self.assertIn("if (choices[index] != effort) onEffortChange(choices[index])", main_chat)
        self.assertNotIn("selected = index", main_chat)
        self.assertIn("remember(show, effort, choices)", shared)
        self.assertIn("mutableIntStateOf(choices.indexOf(effort))", shared)
        self.assertIn("setProgress { value ->", shared)

    def test_child_and_chat_share_one_layout_without_explanatory_footer(self):
        shared = (COMPONENTS / "subagent/ConversationThinkingPicker.kt").read_text()
        wrapper = (COMPONENTS / "subagent/ThinkingEffortPicker.kt").read_text()
        self.assertEqual(1, shared.count('ConversationAgentSheet(onDismiss'))
        self.assertEqual(1, shared.count('Text("调整思考深度"'))
        self.assertNotIn('SubAgentConfigurationWindow(', shared)
        self.assertNotIn('Text(description', shared)
        self.assertNotIn('description:', shared)
        self.assertNotIn('description:', wrapper)
        self.assertIn('if (subagent) "subagent-thinking-title" else "chat-thinking-title"', shared)
        self.assertIn('if (subagent) "subagent-thinking-value" else "chat-thinking-value"', shared)
        for name in ("SubAgentProfileConfigDialog.kt", "SubAgentProfileRow.kt"):
            text = (COMPONENTS / name).read_text()
            picker = text.split("SubAgentThinkingEffortPickerDialog(true,", 1)[1]
            self.assertNotIn('description =', picker)
        self.assertNotIn('仅修改当前草稿，确认后保存', (COMPONENTS / "SubAgentProfileConfigDialog.kt").read_text())

    def test_draft_and_row_keep_distinct_save_ownership(self):
        draft = (COMPONENTS / "SubAgentProfileConfigDialog.kt").read_text()
        row = (COMPONENTS / "SubAgentProfileRow.kt").read_text()
        draft_picker = draft.split("SubAgentThinkingEffortPickerDialog(true,", 1)[1].split("if (usable) choices?", 1)[0]
        self.assertIn("session.edit { it.copy(reasoning = next) }", draft_picker)
        self.assertIn("session.canCommit(latestEditor, latestEnabled) && next in efforts", draft_picker)
        self.assertNotIn("commitProfileDraft", draft_picker)
        row_picker = row.split("SubAgentThinkingEffortPickerDialog(true,", 1)[1]
        self.assertIn("editor?.updateProfile(profile.id)", row_picker)
        self.assertIn("latest.role == profile.role && latest.providerId == profile.providerId && latest.modelId == profile.modelId", row_picker)


if __name__ == "__main__":
    unittest.main()
