"""检查供应商保存后滚动位置的源码连接。"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings"


def source(name):
    return (UI / name).read_text()


class ProviderSavePositionContractTest(unittest.TestCase):
    def test_first_connection_save_registers_before_clearing_recovery_gate(self):
        settings = (ROOT / "app/src/main/kotlin/app/daiyu/android/storage/ModelSettings.kt").read_text()
        save = settings.split("fun save(settings:ModelSettings", 1)[1]
        replacement = save.split("modelCatalog.replaceRegisteredProvider(initialModels)", 1)[1]
        self.assertLess(replacement.index("modelCatalog.register(initialModels)"),
                        replacement.index("remove(CATALOG_PENDING)"))
        self.assertNotIn("if (speechOnly) modelCatalog.register(initialModels)", replacement)
        route = source("ProviderManagementRoute.kt")
        self.assertIn("it.localModelCatalogSnapshot()", route)
        android = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ProviderSavePositionActivityTest.kt").read_text()
        first = android.split("fun testFirstRegistrationAndFollowingEditSaveKeepOneFormAndBottomPosition()", 1)[1].split("fun testExistingProvider", 1)[0]
        self.assertIn("it.localModelCatalogSnapshot()", first)
        self.assertNotIn("it.modelCatalogSnapshot()", first)
        self.assertIn('node("local-model-add").assertIsEnabled()', first)

    def test_saved_feedback_requires_readback_and_clears_on_edits_and_new_attempts(self):
        route = source("ProviderManagementRoute.kt")
        opening = route.split("fun open(", 1)[1].split("fun mutate(", 1)[0]
        self.assertLess(opening.index("detail = ready"),
                        opening.index("if (savedPageUi != null) ready.pageUi.configurationSaved.value = savedPageUi.configurationSaveCommitted"))
        self.assertEqual(4, route.count("configurationSaved.value = false"))
        self.assertIn("val configurationSaved = mutableStateOf(false)", source("ProviderPageUiState.kt"))
        self.assertIn("saveSucceeded = d.pageUi.configurationSaved.value && d.live.get()", route)
        self.assertIn("configurationSaveSucceeded = d.pageUi.configurationSaved.value && d.live.get()", route)
        self.assertIn("saveSucceeded = configurationSaveSucceeded", source("ProviderCatalogSettingsScreen.kt"))
        self.assertIn("saveSucceeded = saveSucceeded", source("ProviderConfigurationForm.kt"))
        screen = source("ModelSettingsScreen.kt")
        self.assertIn("saveSucceeded && !LocalProviderConfigurationSavePending.current", screen)
        self.assertIn("fieldsSaveError == null && errorText.isNullOrBlank()", screen)
        self.assertLess(screen.index('item(key = "model_save")'), screen.index('item(key = "provider_saved")'))
        self.assertLess(screen.index('item(key = "provider_saved")'), screen.index('item(key = "provider_delete")'))
        self.assertIn('Text("已保存", style = MiuixTheme.textStyles.body2, color = Color(0xFF34B233)', screen)
        self.assertIn("textAlign = TextAlign.Center", screen)
        self.assertIn('testTag("model-save-status")', screen)

    def test_partial_registration_and_paused_callbacks_do_not_forge_or_lose_receipts(self):
        route = source("ProviderManagementRoute.kt")
        creation = route.split("onSave = { submitted ->", 1)[1].split("}, onFetchCatalog", 1)[0]
        self.assertLess(creation.index("runtime.save("), creation.index("newProvider.pageUi.configurationSaveCommitted = true"))
        self.assertIn("newProvider.pageUi.configurationSaveCommitted = false", creation)
        callback = route.split("balanceConfiguration = submitted.providerBalanceConfiguration(d.editor.slotId)", 1)[1].split("} catch", 1)[0]
        self.assertIn("success && visit.get() && detail === d", callback)
        self.assertIn("d.pageUi.configurationSaveCommitted = true", callback)
        self.assertIn("savedReadbackUi = d.pageUi; openingSlot = d.editor.slotId; pendingOpen = true", callback)
        opening = route.split("fun open(", 1)[1].split("fun mutate(", 1)[0]
        self.assertIn("(savedPageUi ?: restore?.pageUi)?.configurationSaved?.value = false", opening)

    def test_provider_actions_match_the_balance_button_horizontal_inset(self):
        self.assertIn("LocalSettingsPageStyle provides true", source("SettingsScaffold.kt"))
        card = source("SettingsUiComponents.kt").split("internal fun SettingsCard(", 1)[1].split("internal fun SettingsRow(", 1)[0]
        self.assertIn("Modifier.padding(horizontal = 16.dp, vertical = 4.dp)", card)
        balance = source("ProviderBalanceFields.kt").split('ProviderActionButton(text = if (test.running)', 1)[1].split('test.money?.let', 1)[0]
        self.assertIn('.padding(horizontal = 12.dp, vertical = 8.dp)', balance)
        screen = source("ModelSettingsScreen.kt")
        actions = screen.split('item(key = "model_save")', 1)[1]
        save = actions.split('if (providerName != null)', 1)[1].split('} else {', 1)[0]
        self.assertIn('modifier = saveModifier.padding(horizontal = 28.dp, vertical = 4.dp)', save)
        self.assertIn('onClick = submit', save)
        delete = actions.split('item(key = "provider_delete")', 1)[1]
        self.assertIn('Modifier.testTag("provider-delete").padding(horizontal = 28.dp, vertical = 4.dp)', delete)
        self.assertIn('colors = deletionButtonColors()', delete)
        self.assertIn('onDelete()', delete)

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
        self.assertIn("else if (d != null) key(d.pageUi)", source("ProviderManagementRoute.kt"))
        self.assertIn("key(persistence) { rememberCoroutineScope() }", catalog)
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

    def test_equal_readback_uses_reference_state_without_weakening_old_callback_guards(self):
        screen = source("ModelSettingsScreen.kt")
        self.assertIn("mutableStateOf(draft, referentialEqualityPolicy())", screen)
        self.assertIn(".apply { value = draft }", screen)
        for name in ("ModelSettingsScreen.kt", "ProviderBalanceFields.kt", "ProviderInlineEditors.kt", "RequestBodyFieldsEditor.kt"):
            text = source(name)
            self.assertIn("val latestDraft by rememberUpdatedSettingsDraft(draft)", text, name)
            self.assertNotIn("val latestDraft by rememberUpdatedState(draft)", text, name)
        self.assertIn("latestDraft === draft", screen)

    def test_save_retains_colors_but_blocks_touch_accessibility_and_ime(self):
        form = source("ProviderConfigurationForm.kt")
        for required in ("savePending: Boolean = false", "enabled = enabled || savePending",
                         "PointerEventPass.Initial", "it.consume()", "clearAndSetSemantics",
                         "disabled()", "onPreviewKeyEvent { true }",
                         "LocalProviderConfigurationSavePending provides savePending"):
            self.assertIn(required, form)
        self.assertIn("readOnly = LocalProviderConfigurationSavePending.current", source("SettingsUiComponents.kt"))
        self.assertIn("enabled && !LocalProviderConfigurationSavePending.current", source("ModelSettingsScreen.kt"))
        route = source("ProviderManagementRoute.kt")
        self.assertIn("busy == null && !controller.dataBusy && d.draft === submitted", route)
        self.assertIn("(busy != null || savedReadbackUi != null)", route)
        self.assertIn("configurationSavePending = configurationSavePending", route)

    def test_real_route_repeats_name_edits_and_holds_the_save_transaction_for_visual_checks(self):
        android = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ProviderSavePositionActivityTest.kt").read_text()
        for required in ("testEqualDraftReplacementUpdatesIdentityAndKeepsCurrentNameEditable",
                         "testBlockedSaveKeepsButtonColorsAndRejectsTouchAndStaleActions",
                         "After unchanged save", "After renamed save", "Third position name",
                         "readConfiguration", "staleSave(); staleSave()", "expected.red", "actual.red"):
            self.assertIn(required, android)


    def test_cloud_regression_fixtures_use_their_actual_host_and_initialized_catalog(self):
        android = ROOT / "app/src/androidTest/kotlin/app/daiyu/android"
        position = (android / "ProviderSavePositionActivityTest.kt").read_text()
        self.assertIn("val catalog = fixture.controller.providers.withEditor(editor) { it.localModelCatalogSnapshot() }", position)
        self.assertIn("assertEquals(catalog?.providerIdentity, currentCatalog?.providerIdentity)", position)
        self.assertIn("assertEquals(previous.activeSlotId, registered.activeSlotId)", position)
        body = (android / "RequestBodyFieldsActivityTest.kt").read_text()
        self.assertIn('private fun openBodyFields()', body)
        self.assertIn('awaitUniqueTag("model-body-fields")', body)
        self.assertNotIn('reveal("model-body-fields")', body)
        self.assertIn('realPauseResume(activity, atPause =', body)
        inline = (android / "ui/screens/settings/ProviderInlineEditorsTest.kt").read_text()
        self.assertIn('node("provider-cache-retention:5m").assertIsSelected()', inline)
        self.assertIn('node("provider-cache-retention-menu").assertDoesNotExist()', inline)
        self.assertIn('control("provider-cache-retention").assertTextContains("5分钟")', inline)
        self.assertIn('pageTitle = "语音字段夹具"', inline)
        self.assertIn('awaitUniqueTag("page:语音字段夹具")', inline)


if __name__ == "__main__":
    unittest.main()
