"""Save-position wiring guards. Android regressions own composition/scroll behavior, not these guards."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings"


def source(name):
    return (UI / name).read_text()


class ProviderSavePositionContractTest(unittest.TestCase):
    def test_first_and_later_save_retain_only_the_page_ui_for_authoritative_reload(self):
        route = source("ProviderManagementRoute.kt")
        self.assertIn("open(added.slotId, savedPageUi = newProvider.pageUi)", route)
        self.assertIn("else if (success) open(d.editor.slotId, savedPageUi = d.pageUi)", route)
        self.assertNotIn("else if (success) open(d.editor.slotId)\n", route)
        self.assertIn("open(registered, savedPageUi = checkNotNull(creating).pageUi)", route)

    def test_readback_keeps_disabled_form_mounted_instead_of_showing_loading_page(self):
        opening = source("ProviderManagementRoute.kt").split("fun open(", 1)[1].split("fun mutate(", 1)[0]
        pending = opening.split("if (savedPageUi != null) {", 1)[1].split("openingSlot = slot", 1)[0]
        self.assertIn("detail?.revoke()", pending)
        self.assertIn("savedReadbackUi = savedPageUi", pending)
        self.assertIn("else if (retained == null) closeDetail()", pending)
        self.assertIn("loading = true", opening)
        self.assertIn("if (savedPageUi != null) loaded.pageUi = savedPageUi", opening)
        route = source("ProviderManagementRoute.kt")
        self.assertIn("busy == null && !loading && !checkingRegistry", route)
        self.assertIn("if (newProvider != null)", route)
        self.assertLess(route.index("} else if (d != null)"), route.index("} else if (loading)"))

    def test_readback_refreshes_all_authority_and_never_promotes_submitted_draft(self):
        opening = source("ProviderManagementRoute.kt").split("fun open(", 1)[1].split("fun mutate(", 1)[0]
        for required in ("repo.snapshot()", "repo.editor(slot)", "editor.registryRevision == snapshot.revision",
                         "store.loadForEditing()", "readConfiguration", "it.localModelCatalogSnapshot()",
                         "configurationForEditing()", "repo.catalogPersistence(editor)", "ProviderDetail(editor",
                         "registry = ready.registry", "detail = ready"):
            self.assertIn(required, opening)
        self.assertNotIn("submitted", opening)
        failure = opening.split("}, onFailure =", 1)[1]
        self.assertIn("if (savedPageUi != null) closeDetail()", failure)
        self.assertIn("详情读取失败或已变化", failure)
        self.assertIn("if (!current(p) || serial != request) return@launch", opening)
        route = source("ProviderManagementRoute.kt")
        self.assertIn('else { closeDetail(); notice = "保存未完整确认', route)
        self.assertIn("newProvider.registration.beginSave()", route)
        self.assertIn("newProvider.registration.finishSave()", route)
        self.assertIn("newProvider.registration.registered(added.slotId)", route)

    def test_back_from_creation_clears_pending_save_readback_before_refresh(self):
        route = source("ProviderManagementRoute.kt")
        back = route.split("fun back()", 1)[1].split("PageBackStack(", 1)[0]
        self.assertIn("if (creating != null) { closeDetail(); refresh(); return }", back)
        close = route.split("fun closeDetail()", 1)[1].split("fun refresh()", 1)[0]
        for cleared in ("serial++", "loading = false", "openingSlot = null", "savedReadbackUi = null", "clearPrompts()"):
            self.assertIn(cleared, close)

    def test_pause_during_readback_reloads_instead_of_reviving_the_old_editor(self):
        route = source("ProviderManagementRoute.kt")
        self.assertIn("val readbackUi = savedReadbackUi", route)
        self.assertIn("if (readbackUi != null && openingSlot != null) open(checkNotNull(openingSlot), savedPageUi = readbackUi)", route)
        self.assertIn("openingSlot = null; savedReadbackUi = null", route)
        self.assertIn("if (registered != null && (pendingOpen || !loading))", route)
        self.assertIn("visit.get() && p.live.get() && permit === p && resumed", route)

    def test_one_movable_form_spans_creation_plain_detail_and_catalog_detail(self):
        route = source("ProviderManagementRoute.kt")
        self.assertIn("val configurationOwner = newProvider?.pageUi ?: d?.pageUi", route)
        self.assertIn("val configurationContent = key(configurationOwner)", route)
        self.assertIn("remember { movableContentOf<ProviderConfigurationForm> { it.Content() } }", route)
        self.assertEqual(2, route.count("configurationContent(ProviderConfigurationForm("))
        self.assertIn("configurationContent = configurationContent", route)
        catalog = source("ProviderCatalogSettingsScreen.kt")
        self.assertIn("configurationContent: @Composable (ProviderConfigurationForm) -> Unit", catalog)
        self.assertIn("else configurationContent(ProviderConfigurationForm(", catalog)
        form = source("ProviderConfigurationForm.kt")
        self.assertEqual(1, form.count("ModelSettingsScreen("))
        for forwarding in ("onSave = onSave", "onDraftChange = onDraftChange", "listState = listState",
                           "testBalance = testBalance", "testConnection = testConnection",
                           "requestOptionsUnavailable = requestOptionsUnavailable", "headersUnavailable = headersUnavailable",
                           "credentialUnavailable = credentialUnavailable", "onOAuthLogin = onOAuthLogin"):
            self.assertIn(forwarding, form)

    def test_catalog_invalidation_and_presentation_ownership_are_not_relaxed(self):
        catalog = source("ProviderCatalogSettingsScreen.kt")
        self.assertIn("onSave = { session.invalidate(); onSave(it) }", catalog)
        self.assertIn("onDraftChange = { session.invalidate(); onDraftChange(it) }", catalog)
        page = source("ProviderPageUiState.kt")
        self.assertIn("val configurationListState = LazyListState()", page)
        self.assertNotIn("ModelSettingsSaveOwner", page)
        self.assertNotIn("ModelSettingsDraftUi", page)

    def test_real_route_regression_covers_registration_second_save_validation_and_switch(self):
        android = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ProviderSavePositionActivityTest.kt").read_text()
        for required in ("testFirstRegistrationAndFollowingEditSaveKeepOneFormAndBottomPosition",
                         "testExistingProviderSaveAndValidationFailureKeepPositionAndCatalog",
                         "RetainedProviderConfigurationForm", "firstVisibleItemIndex", "firstVisibleItemScrollOffset",
                         "assertSame", "assertNotSame", 'saveAtBottom("添加提供商", "Position provider")',
                         'saveAtBottom("Position provider", "Renamed position provider")',
                         'reveal("model-save-error"', "currentCatalog?.models", "previous.activeSlotId"):
            self.assertIn(required, android)


if __name__ == "__main__":
    unittest.main()
