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
        self.assertIn("ConversationThinkingPicker(show, effort, options, onDismiss, onEffortChange, description, speedMode, onCycleSpeed, speedEnabled)", text)
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
        self.assertIn("if (allowTaskConfigurationChange()) try {", picker)
        self.assertIn("if (controller.setChatReasoning(thinkingOwner, effort)) publishChat()", picker)
        controller = (SRC / "ChatController.kt").read_text()
        setter = controller.split("internal fun setChatReasoning(", 1)[1].split("\n    }", 1)[0]
        self.assertIn("if (!idleAvailable() || hasLiveChildTasks()) return false\n"
                      "        reasoningPreference.set(owner, effort)\n"
                      "        return true", setter)
        shared = (COMPONENTS / "subagent/ConversationThinkingPicker.kt").read_text()
        subagent, main_chat = shared.split("ConversationAgentTypography {", 1)
        self.assertIn("{ selected = it }, { index ->", subagent)
        self.assertIn("ConversationDepthSlider(selected, choices.lastIndex, { index ->\n"
                      "                    if (choices[index] != effort) onEffortChange(choices[index])\n"
                      "                }, {})", main_chat)
        self.assertNotIn("selected = index", main_chat)
        self.assertIn("remember(show, effort, choices)", shared)
        self.assertIn("mutableIntStateOf(choices.indexOf(effort))", shared)
        self.assertIn("setProgress { value ->", shared)

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
