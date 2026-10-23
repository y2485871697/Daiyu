"""Source wiring only; Android regressions are not executed by this suite."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
HOST = ROOT / 'components/openminis-browser/adapter/src/main/kotlin/app/daiyu/android/browser/openminis'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class BrowserManualStateContractTest(unittest.TestCase):
    def test_agent_overlay_does_not_use_error_or_human_busy(self):
        pool = (APP / 'browser/ui/BrowserUiPool.kt').read_text()
        self.assertIn('unavailable.value = !state.live || state.unknown', pool)
        self.assertIn('agentBrowsing.value = visit.taskOwned && !unavailable.value && state.tabs.isNotEmpty() && !state.takenOver', pool)
        self.assertIn('busy.value = !state.live || state.waiting || state.unknown', pool)
        sheet = (APP / 'browser/ported/ui/browser/BrowserSheet.kt').read_text()
        self.assertIn('val isAgentBrowsing by tabPool.agentBrowsing.collectAsState()', sheet)
        self.assertIn('if (isAgentBrowsing && selectedTab != null)', sheet)
        self.assertNotIn('if (isAgentBusy && tabPool.visit != null)', sheet)

    def test_unknown_route_leaves_without_retrying_or_rearming(self):
        screen = (APP / 'ui/screens/browser/BrowserVisualScreen.kt').read_text()
        self.assertIn('if (visit != null && pool.unavailable.value)', screen)
        failed = screen.split('if (visit != null && pool.unavailable.value)', 1)[1].split('kotlinx.coroutines.delay', 1)[0]
        for text in ('visit.close()', 'dismiss()', 'break'):
            self.assertIn(text, failed)
        self.assertNotIn('navigate(', failed)
        self.assertNotIn('openVisit(', failed)
        pool = (APP / 'browser/ui/BrowserUiPool.kt').read_text()
        self.assertIn('if (!state.live || state.unknown) return', pool)
        local = (APP / 'LocalBrowserVisual.kt').read_text()
        self.assertIn('if (!current() || unknown) return', local)
        self.assertIn('android.util.Log.w("DaiyuBrowser", "human:$stage:${failure.javaClass.simpleName}")', local)
        self.assertNotIn('failure.message', local)
        main = (APP / 'MainActivity.kt').read_text()
        replacement = main.split('if (human.needsReplacement)', 1)[1].split('else human.openVisit()', 1)[0]
        self.assertLess(replacement.index('human.close()'), replacement.index('LocalBrowserVisual('))

    def test_blocked_human_request_never_posts_a_model_failure(self):
        host = (HOST / 'AndroidOpenMinisBrowserHost.kt').read_text()
        blocked = host.split('private fun blocked(', 1)[1].split('private fun fenced(', 1)[0]
        self.assertIn('if (humanControl())', blocked)
        self.assertIn('if (mainFrame) navigation.cancelManualLoad()', blocked)
        self.assertIn('else navigation.failed("BROWSER_OPENMINIS_PAGE_URL_BLOCKED")', blocked)
        request = host.split('private fun requestBlocked(', 1)[1].split('private fun postRequestFailure', 1)[0]
        self.assertIn('pageRequestBlocked(live(), url)', request)
        self.assertIn('if (!humanControl()) postRequestFailure(reason)', request)
        self.assertIn('if (requestBlocked(request.url.toString())) return denied()', host)
        self.assertIn('if (!blocked(url, request.isForMainFrame))', host)
        self.assertIn('ByteArrayInputStream("BROWSER_OPENMINIS_URL_BLOCKED"', host)
        # Renderer failure still quarantines; no blanket swallowing of real unknown outcomes.
        self.assertIn('navigation.failed("BROWSER_OPENMINIS_RENDERER_FAILED")', host)

    def test_manual_history_is_not_a_fallible_page_snapshot_write(self):
        host = (HOST / 'AndroidOpenMinisBrowserHost.kt').read_text()
        page = host.split('fun visiblePage(', 1)[1].split('fun visibleLoading(', 1)[0]
        self.assertNotIn('.record(', page)
        self.assertIn('manualFinished(checkNotNull(url), view.title.orEmpty())', host)
        history = host.split('manualFinished = { url, title ->', 1)[1].split('created.onNewWindow', 1)[0]
        self.assertIn('try { com.openminis.app.browser.BrowserHistoryStore', history)
        self.assertIn('catch (_: Exception)', history)
        self.assertNotIn('history.allowUpstreamRecord', history)

    def test_cancelled_manual_start_and_mismatched_finish_keep_model_guard(self):
        nav = (HOST / 'NavigationCallbacks.kt').read_text()
        self.assertIn('if (isManual && phase == Phase.IDLE)', nav)
        self.assertIn('if (key in used || !manualOverride(checkNotNull(url), true)) return false', nav)
        self.assertIn('pending != null && key != pending && key in used ||', nav)
        self.assertIn('if (isManual) { used += key; return false }', nav)
        self.assertIn('return fail("BROWSER_OPENMINIS_NAVIGATION_UNKNOWN")', nav)
        self.assertIn('if (key in used) return fail("BROWSER_OPENMINIS_NAVIGATION_URL_REUSE")', nav)

    def test_real_webview_and_ui_regressions_are_declared(self):
        ui = (ANDROID / 'BrowserVisualLayoutTest.kt').read_text()
        for name in ('testHumanFailureNeverShowsAgentBrowsingAndTaskTakeoverStillWorks',
                     'testFailedVisualReturnsOnceWithoutReplayingUnknownNavigation'):
            self.assertIn('fun ' + name + '()', ui)
        for token in ('assertTrue(visit.navigations.isEmpty())', 'assertEquals(0, visit.newTabs)',
                      'assertEquals(0, visit.controls)', 'unknown = true, takenOver = false, waiting = true'):
            self.assertIn(token, ui)
        host = (ANDROID / 'browser/BrowserProductionCompositionTest.kt').read_text()
        for name in ('testHumanBlockedResourcesAndExternalNavigationDoNotRetireOriginalView',
                     'testStoppedHumanLateStartDoesNotMakePageBusyOrRetireView'):
            self.assertIn('fun ' + name + '()', host)
        for token in ('assertEquals(url, 403, requireNotNull(response).statusCode)',
                      'assertSame(view, parent.getChildAt(0))', 'view.progress != 100',
                      'historyApprovals.isEmpty()'):
            self.assertIn(token, host)
