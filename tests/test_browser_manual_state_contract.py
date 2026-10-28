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

    def test_detached_visual_readiness_drives_draw_but_only_real_callback_confirms(self):
        host = (HOST / 'AndroidOpenMinisBrowserHost.kt').read_text()
        gate = host.split('private suspend fun awaitVisualState()', 1)[1].split('override fun revokeAdmission', 1)[0]
        self.assertIn('val ready = CompletableDeferred<Unit>()', gate)
        self.assertIn('view.postVisualStateCallback(request, object : WebView.VisualStateCallback()', gate)
        callback = gate.split('override fun onComplete(requestId: Long)', 1)[1].split('if (!ready.isCompleted', 1)[0]
        self.assertIn('requestId == request && ready.isActive && !destroyed && live()', callback)
        self.assertEqual(1, gate.count('ready.complete(Unit)'))
        self.assertIn('ready.complete(Unit)', callback)
        self.assertIn('if (!ready.isCompleted && !view.isAttachedToWindow)', gate)
        self.assertIn('Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)', gate)
        self.assertNotIn('Bitmap.createBitmap(view.width', gate)
        self.assertLess(gate.index('kotlinx.coroutines.delay(16)'), gate.index('Bitmap.createBitmap('))
        self.assertIn('scale(1f / view.width, 1f / view.height)', gate)
        self.assertIn('check(view.width > 0 && view.height > 0)', gate)
        pump = gate.split('while (!ready.isCompleted)', 1)[1].split('ready.await()', 1)[0]
        self.assertIn('check(!destroyed && live())', pump)
        self.assertIn('view.draw(canvas)', pump)
        self.assertIn('kotlinx.coroutines.delay(16)', pump)
        self.assertNotIn('reload(', gate)
        self.assertNotIn('execute(', gate)
        self.assertNotIn('onComplete(', pump)
        self.assertIn('finally {\n            ready.cancel()\n            bitmap?.recycle()', gate)

    def test_configuration_cache_regression_keeps_model_operation_detached(self):
        source = (ANDROID / 'browser/BrowserProductionCompositionTest.kt').read_text()
        test = source.split('fun testModelConfigurationInvalidatesVisibleConfigurationCache()', 1)[1].split('override fun tearDown', 1)[0]
        self.assertIn('assertNull(originalView.parent)', test)
        self.assertIn('assertFalse("model configuration must run offscreen", originalView.isAttachedToWindow)', test)
        self.assertLess(test.index('originalView.isAttachedToWindow'), test.index('when (action)'))
        self.assertIn('"set_viewport" -> confirmed(host, "action" to action, "viewport_width" to 800, "viewport_height" to 600)', test)
        self.assertIn('assertSame(originalView, parent.getChildAt(0))', test)
        self.assertIn('"$action must invalidate visible configuration deduplication", second.isLoading', test)
        self.assertIn('assertEquals(appliedRequests, requests.get())', test)

    def test_first_tab_address_projection_always_observes_editable_snapshot(self):
        state = (APP / 'browser/BrowserSheetUiState.kt').read_text()
        projection = state.split('fun displayedAddress(', 1)[1].split('fun synchronize(', 1)[0]
        self.assertLess(projection.index('val retainedAddress = address'), projection.index('return when'))
        self.assertIn('selectedTab == null -> retainedAddress', projection)
        self.assertIn('else -> retainedAddress', projection)
        unit = (ROOT / 'app/src/test/kotlin/app/daiyu/android/browser/BrowserSheetUiStateTest.kt').read_text()
        for fragment in ('SnapshotStateObserver', 'observeReads(', 'state.synchronize(0, "")',
                         'Snapshot.sendApplyNotifications()', 'assertFalse(invalidated)',
                         'assertTrue("first-tab projection must observe address edits", invalidated)'):
            self.assertIn(fragment, unit)

    def test_address_input_is_bound_to_its_visit_and_preserves_modal_blur_selection(self):
        state = (APP / 'browser/BrowserSheetUiState.kt').read_text()
        gate = state.split('fun addressInput(', 1)[1].split('fun editAddress(', 1)[0]
        for token in ('current: () -> Boolean', 'dismissing: () -> Boolean',
                      'value.text == address.text',
                      'dismissing() || showHistory || showSettings || showDownloads',
                      'if (current() && !(sameText && blur)) editAddress(value)'):
            self.assertIn(token, gate)
        sheet = (APP / 'browser/ported/ui/browser/BrowserSheet.kt').read_text()
        self.assertIn('addressInputCurrent: () -> Boolean = { tabPool.visit?.snapshot()?.live == true }', sheet)
        self.assertIn('onValueChange = uiState.addressInput(addressInputCurrent)', sheet)
        self.assertIn('app.daiyu.android.ui.components.InputDismissal.inProgress', sheet)
        self.assertNotIn('onValueChange = uiState::editAddress', sheet)
        screen = (APP / 'ui/screens/browser/BrowserVisualScreen.kt').read_text()
        self.assertIn('addressInputCurrent = { visit != null && ownerCurrent() }', screen)
        main = (APP / 'MainActivity.kt').read_text()
        self.assertIn('ownerCurrent = { browserVisualPageCurrent(original) }', main)
        unit = (ROOT / 'app/src/test/kotlin/app/daiyu/android/browser/BrowserSheetUiStateTest.kt').read_text()
        for name in ('revokedAddressInputCannotOverwriteItsSuccessorsSelectionOrText',
                     'addressInputPreservesSelectionDuringDismissalAndEachBrowserPanel',
                     'currentAddressInputAcceptsUserCursorSelectionCompositionAndTextChanges'):
            self.assertIn('fun ' + name + '()', unit)
        self.assertIn('listOf(TextRange(2, 9), TextRange(9, 2))', unit)
        self.assertIn('oldInput(TextFieldValue("stale input"))', unit)
        android = (ANDROID / 'BrowserPageLifecycleActivityTest.kt').read_text()
        self.assertGreaterEqual(android.count('SemanticsProperties.TextSelectionRange, range'), 3)
        self.assertNotIn('requestFocus()', android)
