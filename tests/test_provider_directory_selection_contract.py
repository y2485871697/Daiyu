"""Source wiring guards; JVM deletion and Android interaction tests cover behavior."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/app/daiyu/android"
UI = MAIN / "ui/screens/settings"


class ProviderDirectorySelectionContractTest(unittest.TestCase):
    def test_long_press_enters_selection_without_an_action_sheet(self):
        route = (UI / "ProviderManagementRoute.kt").read_text()
        page = (UI / "ProviderDirectoryPage.kt").read_text()
        self.assertNotIn("DaiyuChoiceSheet", route)
        self.assertNotIn("managing", route)
        self.assertIn("selectedSlots = setOf(it.slotId)", route)
        self.assertIn('onLongClickLabel = "选择提供商"', page)
        self.assertIn("if (selectedSlots == null) onOpen(registration)", page)
        self.assertIn("else onSelectionChange(", page)
        for fragment in ("clip(CircleShape)", "role = Role.Checkbox", "ToggleableState.On", "ToggleableState.Off"):
            self.assertIn(fragment, page)

    def test_footer_is_outside_lazy_content_and_keeps_both_actions(self):
        page = (UI / "ProviderDirectoryPage.kt").read_text()
        footer = page.split("if (selectedSlots != null) WidePageContent(fillHeight = false)", 1)[1]
        for fragment in ('text = "删除"', 'text = "取消"', 'navigationBarsPadding()',
                         'selectedSlots.isNotEmpty()', 'testTag("provider-selection-actions")'):
            self.assertIn(fragment, footer)
        self.assertEqual(2, footer.count("Modifier.weight(1f).heightIn(min = 48.dp)"))
        self.assertIn('Modifier.weight(1f), listState = listState', page)
        self.assertIn("selectedSlots - visibleIds else selectedSlots + visibleIds", page)
        self.assertIn("val visibleIds = rows.map { it.slotId }.toSet()", page)
        layout = (MAIN / "ui/layout/AdaptivePageLayout.kt").read_text()
        self.assertIn("fillHeight: Boolean = true", layout)
        self.assertIn("if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()", layout)

    def test_selection_cancel_pause_and_back_keep_the_directory(self):
        route = (UI / "ProviderManagementRoute.kt").read_text()
        self.assertIn("if (selectedSlots != null) { clearTransientPrompts(); return }", route)
        self.assertIn("fun clearTransientPrompts() { confirmation = null; selectedSlots = null }", route)
        self.assertIn("BackHandler(previewEnabled = selectedSlots == null", route)
        self.assertIn("if (registry?.revision != value.revision) selectedSlots = null", route)
        self.assertIn("selectedSlots === selection", route)
        self.assertIn("if (selectedSlots === selected) mutate(expected, p, completed", route)

    def test_batch_delete_uses_the_existing_guarded_controller_and_one_cas(self):
        route = (UI / "ProviderManagementRoute.kt").read_text()
        self.assertIn("controller.mutateProviders(expected.revision, p.save, action)", route)
        self.assertIn("repo.deleteSelected(revision, selected, admit)", route)
        repo = (MAIN / "storage/ProviderRepository.kt").read_text()
        batch = repo.split("fun deleteSelected(", 1)[1].split("fun activate(", 1)[0]
        self.assertEqual(1, batch.count("registry.change(expectedRevision)"))
        for fragment in ("val selected = slotIds.toSet()", "require(selected.isNotEmpty())",
                         "selected.forEach { requireProvider(old, it) }", "it.slotId in selected", "it in selected"):
            self.assertIn(fragment, batch)
        self.assertNotIn("resolveStore", batch)

    def test_runtime_regressions_cover_selection_and_atomic_deletion(self):
        android = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ProviderDirectorySelectionActivityTest.kt").read_text()
        for fragment in ("testLongPressSelectsInlineAndCancelBackAndPauseNeverWrite", "realPauseResume(activity)",
                         "testFilteredSelectAllAndEmptySelectionDoNotSelectHiddenProviders", "assertIsNotEnabled()",
                         "testConfirmedBatchDeletionUsesOneRevisionAndDoesNotSwitchCurrentProvider",
                         "assertEquals(before.revision + 1, after.revision)", "assertNull(after.activeSlotId)"):
            self.assertIn(fragment, android)


if __name__ == "__main__":
    unittest.main()
