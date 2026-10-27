"""Source wiring contracts; real WebView/Compose checks await the approved build batch."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
PORT = APP / 'browser/ported'
HOST = ROOT / 'components/openminis-browser/adapter/src/main/kotlin/app/daiyu/android/browser/openminis'


class BrowserPortContractTest(unittest.TestCase):
    def test_original_surfaces_use_real_visit_and_shared_original_sheet(self):
        ui = (APP / 'ui/screens/browser/BrowserVisualScreen.kt').read_text()
        self.assertIn('BrowserUiPool(context.applicationContext, visit)', ui)
        self.assertIn('BrowserSheet(pool, onBack, presentation)', ui)
        self.assertIn('val presentation = uiState ?: remember { BrowserSheetUiState() }', ui)
        sheet = (PORT / 'ui/browser/BrowserSheet.kt').read_text()
        for name in ('StandardChatSheet(', 'BrowserSettingsSheet(', 'BrowserHistorySheet(', 'BrowserDownloadsSheet('):
            self.assertIn(name, sheet)
        self.assertIn('key(tabPool.visit, selectedTab.id)', sheet)
        self.assertIn('tabPool.visit.mount(it)', sheet)
        self.assertIn('tabPool.visit.unmount(it)', sheet)

    def test_three_language_resource_references_resolve(self):
        references = {name for p in PORT.rglob('*.kt') for name in re.findall(r'R\.string\.(\w+)', p.read_text())}
        self.assertGreater(len(references), 40)
        for locale in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
            names = {e.get('name') for e in ET.parse(ROOT / f'app/src/main/res/{locale}/openminis_browser.xml').getroot()}
            self.assertFalse(references - names, (locale, references - names))

    def test_host_actions_route_to_native_host_without_placeholder_manager_actions(self):
        mapper = (HOST / 'OpenMinisCommandMapper.kt').read_text()
        host = (HOST / 'AndroidOpenMinisBrowserHost.kt').read_text()
        self.assertIn('if (command.action in hostActions) validate(command) else map(command)', mapper)
        self.assertIn('resource.executeCommand(command)', (HOST / 'LeaseBrowserHost.kt').read_text())
        for token in ('view.goBackOrForward(-1)', 'view.goBackOrForward(1)', 'view.reload()', 'view.settings.userAgentString =', 'active.applyViewport('):
            self.assertIn(token, host)
        self.assertIn('navigation.manualSequence == sequence', host)
        self.assertIn('if (!view.url.isNullOrEmpty()) mountVisibleView()', host)
        self.assertIn('ui.post { fenced { navigation.failed(reason) } }', host)

    def test_manual_browsing_does_not_become_model_execution(self):
        local = (APP / 'LocalBrowserVisual.kt').read_text()
        self.assertIn('human-browser:${UUID.randomUUID()}', local)
        self.assertIn('HUMAN_BROWSER_NO_COOKIE_EXPORT', local)
        self.assertIn('override fun restore() { /* Human sessions have no model execution to resume. */ }', local)
        main = (APP / 'MainActivity.kt').read_text()
        self.assertIn('closeBrowserVisualVisit(clearTarget = false)', main)
        pause = main.split('override fun onPause()', 1)[1].split('override fun onStop()', 1)[0]
        self.assertNotIn('retainedPage = "chat"', pause)
        self.assertIn('if (human.needsReplacement)', main)
        host = (HOST / 'AndroidOpenMinisBrowserHost.kt').read_text()
        self.assertIn('if (human != null && visibleIdentity == null) return', host)
        self.assertIn('pageURL = view.url', host)

    def test_pool_downloads_and_configuration_are_real_and_persisted(self):
        pool = (APP / 'browser/ui/BrowserUiPool.kt').read_text()
        for token in ('downloadManager.enqueue(request)', 'downloadManager.query(', 'downloadManager.remove(id)',
                      'downloadManager.getUriForDownloadedFile(entry.id)', 'visit?.configure(configuration())',
                      'putString(downloadKey, saved.toString())', 'latest.state != DownloadState.DOWNLOADING'):
            self.assertIn(token, pool)
        self.assertIn('state.tabs.isNotEmpty() && !state.takenOver', pool)
        self.assertIn('updateDownloadProgress(); return', pool)
        settings = (PORT / 'ui/browser/BrowserSettingsSheet.kt').read_text()
        self.assertIn('var selectedProfile by remember(appliedProfile)', settings)
        self.assertNotIn('putString("user_agent_profile"', settings)

    def test_original_search_recognition_and_download_chooser_keep_native_authorization(self):
        pool = (APP / 'browser/ui/BrowserUiPool.kt').read_text()
        for token in ('value.startsWith("about:")', 'looksLikeUrl(value)',
                      'https://www.bing.com/search?q=', 'OpenMinisNavigationPolicy.permits(url)',
                      'Intent.createChooser(intent, null)', 'getMimeTypeFromExtension(extension)',
                      'Intent.FLAG_GRANT_READ_URI_PERMISSION'):
            self.assertIn(token, pool)
        self.assertNotIn('notice.value = "没有可打开此文件的应用"', pool)

    def test_real_android_regressions_cover_actions_and_same_view_lifecycle(self):
        tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/browser/BrowserProductionCompositionTest.kt').read_text()
        for name in ('testNativeHistoryAndConfigurationUseTheLoadedResource',
                     'testBlankVisibleTabMountsOnlyAfterNavigationAndSurvivesLateDetachedCallback',
                     'testVisibleTakeoverUsesOriginalViewAndManualFinishNeverApprovesHistory'):
            self.assertIn('fun ' + name + '()', tests)
        self.assertIn('assertSame(view, parent.getChildAt(0))', tests)
        self.assertIn('it.trim() == "URL: $manualUrl"', tests)
        controller = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ControllerBrowserVisualTest.kt').read_text()
        self.assertIn('testVisibleCommandsForwardExactValuesAndConfigurationFailureIsNotReplayed', controller)
        self.assertIn('assertEquals(count, f.configurations.size)', controller)
