"""Production navigation wiring; Android frames are deferred to the approved build batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class PageNavigationRegressionContract(unittest.TestCase):
    def test_provider_loading_does_not_suppress_the_shared_visual_route(self):
        source = (APP / 'ui/screens/settings/ProviderManagementRoute.kt').read_text()
        stack = source.split('    PageBackStack(when {', 1)[1]
        self.assertIn('BackHandler(previewEnabled = selectedSlots == null) { back() }', stack)
        self.assertNotIn('!loading || initialSlot', stack)
        self.assertIn('(detail != null || openingSlot != null)', stack)
        self.assertIn('"provider:${detail?.editor?.slotId ?: openingSlot}"', stack)
        opening = source.split('    fun open(', 1)[1].split('    fun mutate(', 1)[0]
        self.assertIn('if (!current(p) || serial != request) return@launch', opening)

    def test_linux_toolbar_system_back_and_preview_use_one_return_target(self):
        source = (APP / 'MainActivity.kt').read_text()
        self.assertIn('"linux-settings", "chat-workspace" -> closeLinuxSettingsPage()', source)
        renderer = source.split('"linux-settings", "chat-workspace" -> {', 1)[1].split('"generation-settings" ->', 1)[0]
        self.assertIn('linuxSettingsVisit === visit && linuxSettingsPageOwner === page', renderer)
        self.assertIn('closeLinuxSettingsPage()', renderer)
        self.assertIn('"linux-settings", "chat-workspace" -> linuxSettingsReturnPage', source)
        self.assertIn('"terminal" -> { terminalPageOwner = Any(); retainedPage = "terminal" }', source)
        self.assertIn('app.daiyu.android.ui.navigation.linuxSettingsBackTarget(', source)
        self.assertIn('app.daiyu.android.ui.navigation.terminalBackTarget(', source)
        self.assertIn('state.putString("linux-settings-return-page", linuxSettingsReturnPage)', source)
        self.assertIn('state?.getString("linux-settings-return-page")', source)

    def test_android_regressions_use_real_production_paths_and_a_bounded_read_barrier(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/PageNavigationRegressionActivityTest.kt').read_text()
        for token in ('testProviderEntryAnimatesWhileItsRealReadIsPendingAndReadyDoesNotReplay',
                      'testTerminalEnvironmentEntryAndSystemReturnKeepTheChatParentAndFreshOwner',
                      'repo.withEditor(editor)', 'store.modelCatalog.readConfiguration',
                      'release.await(30, TimeUnit.SECONDS)', 'worker.join(5_000)',
                      'awaitMotion("page-enter-previous")', 'awaitMotion("page-back-departure")',
                      'assertNotSame(owner, returnedOwner)', 'invokeHost(activity, "terminalPageCurrent", owner!!)', 'assertEquals(0, fixture.opens.get())'):
            self.assertIn(token, source)


if __name__ == '__main__':
    unittest.main()
