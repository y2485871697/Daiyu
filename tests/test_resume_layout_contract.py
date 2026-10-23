"""Source-only regression gates; geometry and lifecycle tests run in the approved Android batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class ResumeLayoutContractTest(unittest.TestCase):
    def test_chevron_presentation_is_independent_of_click_authority(self):
        text = (SRC / "ui/screens/settings/SettingsUiComponents.kt").read_text()
        row = text.split("internal fun SettingsRow(", 1)[1].split("internal fun SettingsSwitchRow(", 1)[0]
        self.assertIn("showChevron: Boolean = onClick != null", row)
        self.assertIn("} else if (showChevron) {", row)
        self.assertNotIn("} else if (onClick != null) {", row)
        # A revoked callback still removes both pointer and accessibility actions.
        self.assertIn(".then(if (onClick != null) Modifier.semantics", row)
        self.assertIn(".clickable(onClick = onClick) else Modifier)", row)
        self.assertIn("Column(Modifier.weight(1f)", row)
        for workaround in ("requiredHeight", "maxLines =", "animateContentSize", "LocalDensity"):
            self.assertNotIn(workaround, row)

    def test_all_audited_temporary_actions_keep_their_existing_chevron_slot(self):
        expected = {'ui/screens/settings/BackupScreen.kt': 2, 'ui/screens/settings/CompressionSettingsForm.kt': 1, 'ui/screens/settings/LinuxSettingsScreen.kt': 2, 'ui/screens/settings/PetSettingsScreen.kt': 5, 'ui/screens/settings/BrowserSettingsScreen.kt': 1, 'ui/screens/settings/IndependentModelScreen.kt': 1, 'ui/screens/settings/OfflineSpeechSettingsScreen.kt': 1, 'ui/screens/settings/ModelSettingsScreen.kt': 1, 'ui/screens/settings/GuiSettingsPanel.kt': 4, 'ui/screens/settings/McpSettingsScreen.kt': 2, 'ui/screens/settings/SettingsHomeScreen.kt': 1, 'ui/screens/settings/SettingsDiagnosticsHost.kt': 2, 'ui/screens/settings/RequestBodyFieldsEditor.kt': 1, 'ui/screens/settings/ProviderDirectoryPage.kt': 3, 'title/TitleModelSettingsPanel.kt': 1, 'VoiceAccountPanel.kt': 14, 'VoiceSettingsPanel.kt': 15, 'VoiceSettingsPreview.kt': 1}
        for path, count in expected.items():
            with self.subTest(path=path):
                text = (SRC / path).read_text()
                actual = text.count("showChevron = true, onClick = if")
                if path.endswith("OfflineSpeechSettingsScreen.kt"):
                    actual += text.count("showChevron = !pack.ready, onClick = if")
                self.assertEqual(count, actual)
        voice = (SRC / "VoiceSettingsPanel.kt").read_text()
        self.assertIn('TextButton("保存连接设置"', voice)
        self.assertNotIn('SettingsRow("保存连接设置"', voice)
        self.assertIn('TextButton("保存账户"', voice)
        self.assertNotIn('SettingsRow("保存账户"', voice)
        self.assertIn('TextButton("保存参考声音"', voice)
        self.assertNotIn('SettingsRow("保存参考声音"', voice)

    def test_provider_revalidation_and_callback_revocation_are_preserved(self):
        route = (SRC / "ui/screens/settings/ProviderManagementRoute.kt").read_text()
        self.assertIn("checkingRegistry = true", route)
        self.assertIn("!checkingRegistry", route)
        self.assertIn("resumed = false; permit.revoke()", route)
        self.assertIn("permit = ProviderPagePermit(); resumed = true", route)
        page = (SRC / "ui/screens/settings/ProviderDirectoryPage.kt").read_text()
        for action in ("onAddOpenAi", "onAddAnthropic", "onApiKey"):
            self.assertIn(f"showChevron = true, onClick = if (enabled) {action} else null", page)

    def test_permanent_read_only_rows_do_not_gain_navigation_arrows(self):
        home = (SRC / "ui/screens/settings/SettingsHomeScreen.kt").read_text()
        rootfs = (SRC / "ui/screens/settings/LinuxRootfsPanel.kt").read_text()
        self.assertIn('onClick = if (entry.id == "donate") null else onClick', home)
        self.assertNotIn('showChevron = true, onClick = if (entry.id == "donate")', home)
        self.assertIn("onClick = if (entry.directory)", rootfs)
        self.assertNotIn("showChevron = true, onClick = if (entry.directory)", rootfs)
        offline = (SRC / "ui/screens/settings/OfflineSpeechSettingsScreen.kt").read_text()
        self.assertIn("showChevron = !pack.ready, onClick = if (pack.checking || pack.ready) null", offline)

    def test_indirect_local_catalog_action_also_keeps_its_chevron(self):
        text = (SRC / "ui/screens/settings/LocalCatalogModelsScreen.kt").read_text()
        self.assertIn("val add = if (actions.add != null && enabled", text)
        self.assertIn("showChevron = true, onClick = add)", text)

    def test_collaboration_presentation_is_retained_but_authority_is_not(self):
        row = (SRC / "ui/components/SubAgentProfileRow.kt").read_text()
        self.assertIn("editor?.modelDisplayProviders?.collectAsState()?.value ?: providers", row)
        self.assertIn("editor?.modelRefreshing?.collectAsState()?.value ?: false", row)
        self.assertIn("enabled && editor?.enabled == true && !modelRefreshing", row)
        self.assertIn("profile.role, presentationProviders)", row)
        self.assertEqual(3, row.count("SubAgentGptSpeedButton(profile, presentationProviders, usable"))
        self.assertIn("SubAgentModelPickerProjector.project(providers,", row)
        self.assertIn("SubAgentProfileConfigDialog(session, editor, providers,", row)
        editor = (SRC / "ui/components/ConversationSubAgentEditor.kt").read_text()
        self.assertIn("val resolvedProvider = providerLookup(providerId)", editor)
        regression = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/SubAgentResumeGeometryActivityTest.kt").read_text()
        for assertion in ("testCollaborationCardKeepsModelAndSpeedWhileLookupIsRevoked", "source.clear()",
                          "speed.assertIsNotEnabled().performTouchInput", "reload(emptyList())", "assertEquals(saved, repository.snapshot(owner))"):
            self.assertIn(assertion, regression)

    def test_frame_regressions_use_current_semantics_and_real_pending_reads(self):
        tests = ROOT / "app/src/androidTest/kotlin/app/daiyu/android"
        provider = (tests / "ProviderDirectoryResumeGeometryActivityTest.kt").read_text()
        collaboration = (tests / "SubAgentResumeGeometryActivityTest.kt").read_text()
        for text in (provider, collaboration):
            self.assertIn("semanticsOwner.unmergedRootSemanticsNode", text)
            self.assertIn("release.await(30, TimeUnit.SECONDS)", text)
            self.assertIn("failure.compareAndSet(null, caught)", text)
        self.assertIn("fixture.settings.modelCatalog.readConfiguration", provider)
        self.assertIn("assertFalse(checkNotNull(refresh).isCompleted)", collaboration)
        self.assertIn("source.refreshAll {", collaboration)
        self.assertIn("refreshScope.cancel()", collaboration)
        search = (tests / "SearchHistoryDialogTest.kt").read_text()
        self.assertIn("hasTestTag(tag) and hasClickAction()", search)
        self.assertIn("fun assertRetainedGeometry()", search)

    def test_global_font_density_and_refresh_rate_are_not_frozen(self):
        theme = (SRC / "ui/app/AgentAppTheme.kt").read_text()
        self.assertIn("density = platformDensity.density * appearance.interfaceScale", theme)
        self.assertIn("fontScale = platformDensity.fontScale", theme)
        activity = (SRC / "MainActivity.kt").read_text()
        self.assertIn("applyPreferredRefreshRate()", activity)
        self.assertNotIn("resources.updateConfiguration", activity)


if __name__ == "__main__":
    unittest.main()
