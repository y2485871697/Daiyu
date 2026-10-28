"""Guard the provider indicator's read-only path from the selected conversation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/app/daiyu/android"
UI = MAIN / "ui/screens/settings"


class ProviderConversationIndicatorContractTest(unittest.TestCase):
    def test_directory_indicator_has_an_explicit_session_slot_and_no_global_fallback(self):
        page = (UI / "ProviderDirectoryPage.kt").read_text()
        self.assertIn("currentProviderSlotId: String? = null", page)
        self.assertIn("currentProviderSlotId == registration.slotId", page)
        self.assertNotIn("registry?.activeSlotId", page)
        self.assertIn("val checked = registration.slotId in selectedSlots", page)

    def test_host_passes_the_selected_conversation(self):
        host = (MAIN / "MainActivity.kt").read_text()
        route = host.split('"providers" -> ProviderManagementRoute(controller,', 1)[1].split('"models" ->', 1)[0]
        self.assertIn("conversationId = selected?.id", route)

    def test_registry_refresh_resolves_the_same_binding_as_chat_before_publishing(self):
        route = (UI / "ProviderManagementRoute.kt").read_text()
        refresh = route.split("fun refresh()", 1)[1].split("fun open(", 1)[0]
        self.assertIn("controller.conversationModelEditing(conversationId).first.slotId", refresh)
        self.assertIn("withContext(Dispatchers.IO)", refresh)
        self.assertIn("currentProviderSlotId = slot", refresh)
        self.assertIn("currentProviderSlotId = null", refresh)
        self.assertIn(".getOrNull()", refresh)
        self.assertNotIn(".activeSlotId", refresh)
        self.assertIn("currentProviderSlotId = currentProviderSlotId", route)
        self.assertIn(") = key(conversationId) { ProviderSettingsTheme {", route)
        self.assertIn("visit.set(false)", route)
        self.assertIn("controller.unobserve(listener)", route)

    def test_chat_display_and_transport_share_the_persisted_model_binding(self):
        controller = (MAIN / "ChatController.kt").read_text()
        display = controller.split("fun conversationModelEditing(", 1)[1].split("private fun freezePreparationProvider", 1)[0]
        transport = controller.split("fun freezeConversationProvider(", 1)[1].split("internal fun conversationModelEditing", 1)[0]
        self.assertIn("database.modelBinding(owner)", display)
        self.assertIn("database.modelBinding(owner)", transport)


if __name__ == "__main__":
    unittest.main()
