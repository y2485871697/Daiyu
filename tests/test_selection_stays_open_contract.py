"""Source wiring assertions; Android fixtures own actual input and window behavior."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
class SelectionStaysOpenContractTest(unittest.TestCase):
    def test_assistant_keeps_stable_owner_and_advances_only_confirmed_selection(self):
        main = (SRC / "MainActivity.kt").read_text()
        body = main.split("assistantSelectionOwner?.let { owner -> key(owner)", 1)[1].split("usageVisit?.let", 1)[0]
        self.assertIn("var confirmedAssistantId by remember(owner) { mutableStateOf(owner.expectedAssistantId) }", body)
        self.assertIn("selectedId = confirmedAssistantId", body)
        self.assertIn("controller.bindConversationAssistant(conversationId,confirmedAssistantId,id)", body)
        self.assertLess(body.index("controller.bindConversationAssistant("), body.index("confirmedAssistantId = id"))
        selection = body.split("onSelect = { id -> guard", 1)[1].split("onEdit =", 1)[0]
        self.assertNotIn("assistantPicker = false", selection)
        self.assertNotIn("assistantSelectionOwner =", selection)
        self.assertIn("allowAssistantSelection(owner)", selection)
        leaf = (SRC / "ui/screens/assistants/AssistantPickerDialogs.kt").read_text()
        self.assertIn("onClick = { if (canSelect()) onSelect(profile.id) }", leaf)
        self.assertIn("finishSelection(onDismiss)", leaf)
        self.assertIn("finishSelection { if (canInteract()) actionProfileId = profile.id }", leaf)
    def test_model_success_refreshes_authoritative_session_without_remount(self):
        main = (SRC / "MainActivity.kt").read_text()
        success = main.split("private fun selectChatModel(", 1)[1].split("private fun gatewayCurrent", 1)[0]
        self.assertIn("reloadChatModels(owner, retainPresentation = true)", success)
        self.assertNotIn("dismissChatModels()", success)
        reload = main.split("private fun reloadChatModels(", 1)[1].split("private fun selectChatModel(", 1)[0]
        self.assertIn("if (!retainPresentation)", reload)
        self.assertIn("controller.database.modelBinding(conversation)", reload)
        self.assertIn("LocalCatalogChatModelPickerSession(", reload)
        self.assertIn("cancelled.get() || chatModelCancelled !== cancelled", reload)
    def test_subagent_value_callbacks_keep_their_child_and_draft_boundary(self):
        row = (SRC / "ui/components/SubAgentProfileRow.kt").read_text()
        self.assertNotIn("LaunchedEffect(profile.role) { rolePicker = false", row)
        form = (SRC / "ui/components/SubAgentProfileConfigDialog.kt").read_text()
        self.assertNotIn("}; choices = null", form)
        self.assertNotIn("session.selectModel(ModelFeatureSelection(true, provider, model)); modelPicker = false", form)
        self.assertIn("commitProfileDraft", form)
        self.assertIn("WriteResult.Saved", form)
        self.assertIn("session.canCommit(latestEditor, latestEnabled)", form)
    def test_linux_keeps_window_identity_separate_from_value_authorization(self):
        linux = (SRC / "ui/screens/settings/LinuxSettingsScreen.kt").read_text()
        self.assertIn("choiceScope = remember(page, runningMode, installChoice)", linux)
        self.assertIn("currentChoiceScope.value === choiceScope", linux)
        self.assertNotIn("popupScope = choiceScope", linux)
        self.assertEqual(2, linux.count("popupScope = remember(page, runningMode, renderedTabScope) { Any() }"))
    def test_voice_values_retain_but_record_actions_still_close(self):
        leaf = (SRC / "VoiceSettingsDialogs.kt").read_text()
        self.assertIn("retainOnSelect: Boolean = false", leaf)
        self.assertIn("if (retainOnSelect)", leaf)
        tone = leaf.split("internal fun VoiceToneDialog(", 1)[1]
        self.assertNotIn("rememberBottomSheetAction", tone)
        panel = (SRC / "VoiceSettingsPanel.kt").read_text()
        self.assertIn("key(profile, dialog)", panel)
        self.assertIn("retainOnSelect = true", panel)
        self.assertIn('if (kind == "DOUBAO") dialog = null', panel)
        self.assertNotIn("dialog = null; busy = true", panel)
        account = (SRC / "VoiceAccountPanel.kt").read_text()
        self.assertIn("voiceMenu = null", account)
        self.assertNotIn("confirmation = null; slotPicker = false", account)
        self.assertNotIn("existingId = voice.id; confirmation = null; importSlotPicker = false", account)
    def test_settings_values_do_not_reset_on_value_changes(self):
        provider = (SRC / "ui/screens/settings/ProviderSettingsComponents.kt").read_text()
        self.assertIn("remember(tag) { mutableStateOf(false) }", provider)
        self.assertNotIn("DisposableEffect(lifecycle, value", provider)
        self.assertIn("Lifecycle.Event.ON_PAUSE) expanded = false", provider)
        appearance = (SRC / "ui/screens/settings/AppearanceSettingsScreen.kt").read_text()
        self.assertNotIn("val finishSelection =", appearance)
        self.assertIn("onDismiss = { choosingBlur = false }", appearance)
        self.assertEqual(1, appearance.count("choosingBlur = false"))
if __name__ == "__main__": unittest.main()
