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

    def test_cancel_uses_secondary_role_and_precedes_danger_delete(self):
        page = (UI / "ProviderDirectoryPage.kt").read_text()
        footer = page.split("if (selectedSlots != null) WidePageContent(fillHeight = false)", 1)[1].split("internal fun ProviderAuthenticationPage", 1)[0]
        self.assertLess(footer.index('text = "取消"'), footer.index('text = "删除"'))
        cancel, delete = footer.split('TextButton(text = "取消"', 1)[1].split('TextButton(text = "删除"', 1)
        self.assertIn('resolveAppearanceActions(LocalAppearanceSettings.current, LocalAppearanceDark.current)', footer)
        self.assertIn('color = accents.secondaryContainer', cancel)
        self.assertIn('textColor = accents.onSecondary', cancel)
        self.assertIn('onClick = onCancelSelection', cancel)
        self.assertIn('colors = deletionButtonColors()', delete)
        self.assertIn('enabled = enabled && selectedSlots.isNotEmpty()', delete)

    def test_count_and_select_all_share_typography_and_text_baseline(self):
        page = (UI / "ProviderDirectoryPage.kt").read_text()
        header = page.split('item("provider-count")', 1)[1].split('rows.forEach', 1)[0]
        self.assertIn('val titleStyle = MiuixTheme.textStyles.body2.copy', header)
        self.assertIn('fontSize = if (grouped) 13.sp else 14.sp', header)
        self.assertEqual(3, header.count('style = titleStyle'))
        self.assertEqual(3, header.count('.alignByBaseline()'))
        self.assertIn('testTag("provider-count-title")', header)
        self.assertIn('testTag("provider-select-all")', header)
        self.assertIn('role = Role.Button', header)
        self.assertIn('enabled = enabled && visibleIds.isNotEmpty()', header)
        self.assertNotIn('TextButton(', header)

    def test_selection_cancel_pause_and_back_keep_the_directory(self):
        route = (UI / "ProviderManagementRoute.kt").read_text()
        self.assertIn("if (selectedSlots != null) { clearTransientPrompts(); return }", route)
        self.assertIn("fun clearTransientPrompts() { confirmation = null; selectedSlots = null }", route)
        self.assertIn("BackHandler(previewEnabled = selectedSlots == null", route)
        pause = route.split('if (event == Lifecycle.Event.ON_PAUSE)', 1)[1].split('} else if (event == Lifecycle.Event.ON_RESUME)', 1)[0]
        self.assertNotIn("clearTransientPrompts()", pause)
        self.assertIn("confirmation = null", pause)
        self.assertIn("permit.revoke()", pause)
        self.assertIn("selectedSlots = selectedSlots?.intersect(value.providers.map { it.slotId }.toSet())", route)
        self.assertIn("selectedSlots === selection", route)
        self.assertIn("if (selectedSlots === selected) mutate(expected, p, completed", route)

    def test_selection_resume_prunes_full_snapshot_and_has_real_home_regression(self):
        route = (UI / "ProviderManagementRoute.kt").read_text()
        self.assertIn("current(p) && enabled && registry === displayedRegistry && selection != null", route)
        self.assertIn("checkingRegistry = true; pendingOpen = true", route)
        runtime = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/SelectionPresentationLifecycleActivityTest.kt").read_text()
        for entry in ("testProviderHiddenSelectionSurvivesRepeatedPauseAndHomeWithOldButtonsRevoked",
                      "testProviderRevisionChangePrunesOnlyRemovedIdsWithoutSelectingNewRows",
                      "GLOBAL_ACTION_HOME", "Lifecycle.Event.ON_STOP", "RetainedListSelectionAction"):
            self.assertIn(entry, runtime)

    def test_deleted_current_model_reuses_only_presentation_not_old_config_or_grants(self):
        route = (UI / "ProviderManagementRoute.kt").read_text()
        for guard in ('restore.editing.copy(settings = restore.editing.settings.copy(model = "")) == editing',
                      'retained?.catalogIdentity == identity',
                      'catalog.models.none { it.id == restore.editing.settings.model }',
                      'if (clearedDeletedModel) loaded.pageUi = checkNotNull(restore).pageUi'):
            self.assertIn(guard, route)
        page = (UI / "ProviderPageUiState.kt").read_text()
        self.assertIn('selectedModelIds = mutableStateOf<Set<String>?>(null)', page)
        self.assertNotIn('DialogActionLease', page)
        runtime = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/SelectionPresentationLifecycleActivityTest.kt").read_text()
        self.assertIn('testDeletingCurrentModelRetainsModelTabAndSurvivingSelection', runtime)

    def test_oauth_restoration_releases_the_registry_check_after_same_revision(self):
        route = (UI / "ProviderManagementRoute.kt").read_text()
        oauth = route.split("target.attachOAuth(auth)", 1)[1].split("} else discardOAuth(auth)", 1)[0]
        self.assertIn("checkingRegistry = false", oauth)
        self.assertIn("pendingOpen = false", oauth)
        self.assertIn("sameRegistry && current(permit)", route)

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
