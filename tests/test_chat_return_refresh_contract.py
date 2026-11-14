"""The real MainActivity return path, not the Compose-only retained-chat harness."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def body(source, signature):
    start = source.index('{', source.index(signature)) + 1
    depth = 1
    for end in range(start, len(source)):
        depth += (source[end] == '{') - (source[end] == '}')
        if depth == 0:
            return source[start:end]
    raise AssertionError(f'unclosed function: {signature}')


class ChatReturnRefreshContractTest(unittest.TestCase):
    def setUp(self):
        self.main = (APP / 'MainActivity.kt').read_text()

    def test_return_keeps_cleanup_order_guards_and_only_the_final_refresh(self):
        returned = body(self.main, 'private fun returnToChat(refreshState: Boolean)')
        for cleanup in ('dismissSidebarState()', 'dismissChatModels()', 'cancelRestorePreview()', 'retainedPage = "chat"'):
            self.assertIn(cleanup, returned)
        self.assertIn('chatReturnRefresh = ChatReturnRefresh(selected?.id, editorGeneration, refreshState)', returned)
        self.assertIn('pending.full = pending.full || refreshState', returned)
        self.assertIn('chatState.composer.copy(enabled = false)', returned)
        self.assertNotIn('refresh()', returned)
        self.assertNotIn('publishChat(', returned)
        settled = body(self.main, 'private fun finishChatReturn(')
        for guard in ('chatReturnRefresh !== request', '!activityUsable()', '!voiceForeground',
                      'retainedPage != "chat"', 'Lifecycle.State.RESUMED', 'request.conversation != selected?.id',
                      'request.editor != editorGeneration'):
            self.assertIn(guard, settled)
        self.assertIn('if (request.full || controller.videoSendState() != null) refresh() else publishChat(rowsChanged = false)', settled)
        self.assertLess(settled.index('chatReturnRefresh = null'), settled.index('if (request.full'))
        self.assertIn('private fun returnToChat() = returnToChat(refreshState = true)', self.main)
        self.assertIn('private val listener: () -> Unit = { refresh() }', self.main)
        self.assertIn('"settings", "permission-check" -> returnToChat(refreshState = false)', self.main)
        self.assertIn('"permission-check" -> app.daiyu.android.ui.screens.settings.PermissionCheckScreen(\n                        onBack = { returnToChat(refreshState = false) }', self.main)
        self.assertIn('onBack = { returnToChat(refreshState = false) }, onRoute = { route -> openSettingsFromPage(route) }', self.main)
        self.assertIn('if (skillsReturnPage == "settings") retainedPage = "settings" else returnToChat(refreshState = false)', self.main)
        state = body(self.main, 'private fun dismissSidebarState()')
        self.assertEqual('''
        sidebarVisible = false; speechHost.setVisible(retainedPage == "chat")
        imageHost.setVisible(retainedPage == "chat", selected?.id?.value)
    ''', state)
        dismiss = body(self.main, 'private fun dismissSidebar()')
        self.assertEqual('''
        dismissSidebarState()
        if (activityUsable()) refresh()
    ''', dismiss)

    def test_return_idle_masks_admission_and_cancels_on_existing_owner_boundaries(self):
        self.assertIn('retainedRootActive = retainedPage == "chat" && returnRefreshRequest == null', self.main)
        self.assertIn('onTransitionIdle = { finishChatReturn(returnRefreshRequest) }', self.main)
        self.assertIn('idleRequestKey = returnRefreshRequest', self.main)
        self.assertIn('it.enabled && routeActive && chatReturnRefresh == null', self.main)
        for signature in ('private fun composerShortcutAvailable()', 'private fun currentChatControlAvailable()'):
            self.assertIn('chatReturnRefresh == null', self.main.split(signature, 1)[1].split('\n', 1)[0])
        self.assertIn('voiceForeground && chatReturnRefresh == null && messageActionVisit', self.main)
        self.assertIn('val composerAvailable = chatReturnRefresh == null', self.main)
        self.assertIn('if (chatReturnRefresh != null) return@guard', self.main)
        self.assertIn('if (value != "chat") chatReturnRefresh = null', self.main)
        for signature in ('override fun onPause()', 'override fun onResume()', 'override fun onDestroy()'):
            self.assertIn('chatReturnRefresh = null', body(self.main, signature))
        self.assertIn('if (field != value) { chatReturnRefresh = null;', self.main)
        host = (APP / 'ui/navigation/PageBackGestures.kt').read_text()
        idle = host.split('val currentTransitionIdle by rememberUpdatedState', 1)[1].split('val showDeparture', 1)[0]
        self.assertIn('idleRequestKey', idle)
        self.assertIn('resumed && !activeDeparture && !state.active && !state.gestureVisualActive', idle)
        self.assertNotIn('delay(', idle)
        self.assertNotIn('visualProgress', idle)
        regression = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackGesturesActivityTest.kt').read_text()
        for method in ('testTransitionIdleWaitsForCommittedDeparture', 'testTransitionIdleWaitsForCancelledGesture',
                       'testTransitionIdleConsumesNewRequestOnAlreadyIdleRoute'):
            self.assertIn('fun ' + method + '()', regression)

    def test_custom_subagent_toolbars_share_the_final_copy_entry(self):
        source = (APP / 'ui/SubAgentSettingsScreen.kt').read_text()
        self.assertEqual(2, source.count('LocalPageBackToolbarReturn.current'))
        self.assertEqual(2, source.count('if (pageReturn == null) onBack() else pageReturn(onBack)'))
        self.assertNotIn('IconButton(onClick = onBack)', source)

    def test_fixed_sync_phases_wrap_original_entry_bodies_not_composables_or_io(self):
        refresh = body(self.main, 'private fun refresh()')
        publish = body(self.main, 'private fun publishChat(rowsChanged: Boolean,')
        self.assertTrue(refresh.strip().startswith('app.daiyu.android.diagnostics.synchronousTrace("daiyu.chat.refresh") {'))
        self.assertTrue(publish.strip().startswith('app.daiyu.android.diagnostics.synchronousTrace("daiyu.chat.publish") {'))
        self.assertEqual(1, self.main.count('synchronousTrace("daiyu.chat.refresh")'))
        self.assertEqual(1, self.main.count('synchronousTrace("daiyu.chat.publish")'))
        self.assertIn('if (!activityUsable()) return', refresh)
        self.assertIn('if (controller.dataBusy)', refresh)
        self.assertIn('cancelRestorePreview(); publishChat(); return', refresh)
        self.assertIn('if (!controller.ready || controller.problem?.startsWith("会话恢复失败") == true) { publishChat(); return }', refresh)
        self.assertIn('if (dataRevision != controller.dataRevision)', refresh)
        self.assertIn('if (titleRevision != controller.titleRevision)', refresh)
        self.assertIn('private fun publishChat() = publishChat(rowsChanged = true)', self.main)
        self.assertIn('RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.UI_PUBLISH)', publish)
        self.assertIn('if (rowsChanged) refreshTimeline()', publish)
        for block in (refresh, publish):
            self.assertNotIn('@Composable', block)
            self.assertNotIn('withContext(', block)
            self.assertNotIn('delay(', block)

    def test_rendered_revision_is_single_entry_deep_list_projection_not_authority(self):
        prepare = body(self.main, 'private fun prepareMessageActionSnapshot()')
        lookup = 'messageActionRenderedRevision.revision(owner, shown.map { it.second })'
        self.assertIn('private val messageActionRenderedRevision = MessageActionRenderedRevision(', self.main)
        self.assertIn(lookup, prepare)
        self.assertLess(prepare.index(lookup), prepare.index('if (messageActionKey == key'))
        callback = prepare.split('controller.loadMessageActionSnapshot(owner)', 1)[1]
        self.assertNotIn('messageActionRenderedRevision', callback)
        for guard in ('messageActionVisit === visit', 'messageActionLoading == key',
                      'activityUsable() && voiceForeground', 'selected?.id == key.conversation',
                      'editorGeneration == key.editor', 'controller.dataRevision == key.data',
                      'ContextRevision.digest(rows) == key.rows', 'candidate.matchesRendered(rows)'):
            self.assertIn(guard, callback)
        self.assertIn('snapshot.matchesRendered(shown.map { it.second })', self.main)
        self.assertIn('messageActionRenderedRevision.matches(it, shown.map { row -> row.second })', self.main)
        self.assertIn('snapshot.matchesRendered(rows)', self.main)
        self.assertEqual(1, self.main.count('synchronousTrace("daiyu.chat.action-match")'))
        self.assertEqual(1, self.main.count('synchronousTrace("daiyu.chat.action-revision")'))
        cold = self.main.split('private val messageActionRenderedRevision =', 1)[1].split('private fun clearInlineMessageEditor()', 1)[0]
        self.assertIn('ContextRevision.digest(rows)', cold)
        helper = (APP / 'MessageActionRenderedRevision.kt').read_text()
        self.assertIn('private var entry: Entry? = null', helper)
        self.assertIn('it.owner == owner && it.rows == rows', helper)
        self.assertIn('current.revision ?: digest(current.rows)', helper)
        self.assertIn('snapshot.rowRevisions.toMap()', helper)
        self.assertIn('it.snapshot === snapshot && it.revisions == revisions', helper)
        self.assertIn('matcher(snapshot, current.rows)', helper)
        for owner in ('message', 'segment'):
            for field in ('blocks', 'protocolItems', 'reportedReasoning'):
                self.assertIn(f'{field} = {owner}.{field}.toList()', helper)
        self.assertIn('historySegments = message.historySegments.map', helper)
        for forbidden in ('toByteArray(', 'MessageDigest', 'hashCode(', 'IdentityHashMap',
                          'serialize', 'database.', 'JSONObject', 'isCurrent', 'MessageActionTarget'):
            self.assertNotIn(forbidden, helper)
        self.assertIn('ContextRevision::digest', helper)

    def test_real_main_regression_counts_sync_work_and_keeps_existing_scope(self):
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageRetainedContentActivityTest.kt').read_text()
        regression = body(android, 'fun testRealMainChatReturnReusesRowsAndSidebarDismissStillRefreshesImmediately()')
        self.assertIn('openSettings()', regression)
        self.assertIn('activity.onBackPressedDispatcher.onBackPressed()', regression)
        self.assertIn('invokeHost(activity, "dismissSidebar")', regression)
        self.assertNotIn('setContent', regression)
        self.assertNotIn('assertRealChatReturn', regression)
        self.assertIn('runtime.enabled = false', regression)
        self.assertIn('recorder.frame(System.nanoTime(), 40_000_000L', regression)
        self.assertIn('expectedRefresh, refreshCount', regression)
        self.assertIn('expectedRefresh = 0', regression)
        self.assertIn('before.turns, after.turns', regression)
        self.assertIn('before.contentRevision, after.contentRevision', regression)
        video = body(android, 'fun testPureChatReturnStillAdoptsVideoReceiptThatRequiredVisibleEditor()')
        self.assertIn('fixture.controller.videoSendState()', video)
        self.assertIn('invokeHost(activity, "settleRetainedVideoReceipt")', video)
        self.assertIn('activity.onBackPressedDispatcher.onBackPressed()', video)
        self.assertIn('transition idle takes the video business refresh fallback', video)
        self.assertIn('listOf("permission-check", "skills", "providers")', regression)
        self.assertIn('recordSynchronous(startWindow = true, expectedRefresh = 0, expectedPublish = 0) { invokeHost(activity, "returnToChat") }', regression)
        self.assertIn('expectedPublish, publishCount', regression)
        for source in ('expectedRefresh = if (full) 1 else 0', 'invokeHost(activity, "finishChatReturn", pending)',
                       'same-owner return requests coalesce', 'settled work consumes the request exactly once',
                       'settled pure return retains rows', 'settled publication recalculates current composer admission'):
            self.assertIn(source, regression)
        self.assertIn('invokeHost(activity, "getChatReturnRefresh")', regression)
        self.assertNotIn('getDeclaredField("chatReturnRefresh")', regression)
        self.assertLess(regression.index('action()'), regression.index('recorder.end('))
        self.assertLess(regression.index('recorder.end('), regression.index('runtime.export()'))
        flow = (ROOT / '.github/workflows/screen-observation-jank-checks.yml').read_text()
        page_scope = flow.split("if os.environ['SCREEN_JANK_CHECK_SCOPE'] == 'page-back':", 1)[1].split('if os.environ', 1)[0]
        self.assertIn('test_chat_return_refresh_contract.py', page_scope)
        self.assertIn('"MESSAGE_ACTION_REVISION"', regression)
        self.assertIn('"MESSAGE_ACTION_MATCH"', regression)
        self.assertIn('warm Compose binding must reuse the unchanged content match', regression)
        self.assertIn('fixture.database.send(user, assistant)', regression)
        self.assertIn('message action snapshot admitted before warm returns', regression)
        self.assertIn('recordSynchronous(startWindow = true) { invokeHost(activity, "refresh") }', regression)
        for name in ('BackPerformanceRecorderTest', 'BackPerformanceRuntimeTest', 'MessageActionRenderedRevisionTest'):
            self.assertEqual(2, flow.count(name))
        classes = flow.split('if [[ $SCREEN_JANK_CHECK_SCOPE == page-back ]]; then', 1)[1].split('\n          fi', 1)[0]
        expected = flow.split("if os.environ['SCREEN_JANK_CHECK_SCOPE'] == 'page-back':", 2)[2].split('suites =', 1)[0]
        self.assertIn('app.daiyu.android.MessageActionRenderedRevisionTest', classes)
        self.assertIn("'MessageActionRenderedRevisionTest'", expected)
        self.assertIn('checks_only.init.gradle', flow)



class ContentRecoveryWitnessContract(unittest.TestCase):
    def test_chat_recovery_is_normal_release_native_draw_numeric_only_and_bounded(self):
        root = Path(__file__).resolve().parents[1]
        code = (root / "app/src/main/kotlin/app/daiyu/android/diagnostics/ChatRecoveryDiagnostics.kt").read_text()
        runtime = (root / "app/src/main/kotlin/app/daiyu/android/diagnostics/RuntimeDiagnostics.kt").read_text()
        chat = (root / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageList.kt").read_text()
        self.assertIn("ObserveChatRecovery(pageActive && scrollResumed)", chat)
        witness = chat.split("ObserveChatRecovery(pageActive && scrollResumed)", 1)[1].split("// Read confirmation", 1)[0]
        for source in ("listState.layoutInfo", "positionInWindow()", "currentNestedTail.viewportOffset",
                       "currentNestedTail.scrollOffset", "currentNestedTail.contentHeight",
                       "ChatRecoveryGeometry.Item(it.index, it.offset, it.size)"):
            self.assertIn(source, witness)
        for forbidden in (".key", "scrollToItem", "scrollBy", "forceRemeasure", "invalidate", "turns"):
            self.assertNotIn(forbidden, witness)
        self.assertIn("ViewTreeObserver.OnDrawListener", code)
        self.assertIn("MAX_FRAMES = 16", code)
        self.assertIn("MAX_ITEMS = 6", code)
        self.assertIn("WINDOW_MS = 750L", code)
        self.assertNotIn("BuildConfig", code)
        self.assertNotIn("invalidate", code)
        self.assertIn("NATIVE_DRAW_LAYOUT_NOT_PRESENTATION", runtime)
        self.assertIn("handler.removeCallbacksAndMessages(recorder)", code)
        method = runtime.split("internal fun chatRecovery(", 1)[1].split("private val active", 1)[0]
        self.assertNotIn("!enabled", method)
        self.assertIn("incident.epoch != backGeneration.get()", method)


    def test_chat_status_bar_space_ignores_visibility_but_keeps_live_bottom_insets(self):
        root = Path(__file__).resolve().parents[1]
        chat = (root / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatScreen.kt").read_text()
        self.assertIn("val statusBars = WindowInsets.statusBarsIgnoringVisibility", chat)
        self.assertIn("remember(view, statusBars) { NativeChatStatusBarInsets(view, statusBars) }", chat)
        self.assertIn(".windowInsetsPadding(stableStatusBars)", chat)
        native = body(chat, "override fun getTop(density: Density)")
        self.assertIn("val composeTop = source.getTop(density)", native)
        self.assertIn("ViewCompat.getRootWindowInsets(view)", native)
        self.assertIn("getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())?.top ?: composeTop", native)
        self.assertLess(native.index("source.getTop(density)"), native.index("ViewCompat.getRootWindowInsets(view)"))
        self.assertNotIn("coerceAtLeast", native)
        self.assertIn("@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)", chat)
        self.assertNotIn("statusBarsPadding", chat)
        self.assertIn(".windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))", chat)
        self.assertNotIn("systemBarsIgnoringVisibility", chat)
        self.assertNotIn("navigationBarsIgnoringVisibility", chat)

    def test_status_bar_regression_observes_native_draws_before_visibility_and_home_resume(self):
        root = Path(__file__).resolve().parents[1]
        android = (root / "app/src/androidTest/kotlin/app/daiyu/android/ChatStatusBarInsetsTest.kt").read_text()
        for source in ("ViewTreeObserver.OnDrawListener", "controller.hide(status)", "controller.show(status)",
                       "getInsetsIgnoringVisibility(status)", "GLOBAL_ACTION_HOME", "Lifecycle.Event.ON_RESUME",
                       "restoreOriginalHost(original)", 'node("chat-messages")', "awaitImeFrame",
                       "testStatusBarVisibilityKeepsEveryNativeChatDrawStableAndImeLive",
                       "testHomeReturnKeepsChatGeometryFromFirstNativeDraw"):
            self.assertIn(source, android)
        for forbidden in ("setContent", "captureScreenNode", "takeScreenshot", "Thread.sleep"):
            self.assertNotIn(forbidden, android)
        native = body(android, "private fun assertNativeDraws(")
        self.assertLess(native.index("observer.addOnDrawListener(listener)"), native.index("change()"))
        self.assertIn("if (withIme) atBottom(expected, frame.geometry.list.bottom) else expected", native)
        self.assertIn("nativeExpected(expected, it) == it.geometry", native)
        self.assertIn("nativeExpected(expected, settled), settled.geometry", native)
        self.assertIn("SYSTEM_UI_FLAG_FULLSCREEN", android)
        readiness = body(android, "private fun statusReady(")
        self.assertIn("Build.VERSION.SDK_INT < 30", readiness)
        self.assertIn("getInsetsIgnoringVisibility(status).top == physicalTop", readiness)
        oracle = body(android, "private fun nativeFrame(")
        for source in ("view.getLocationInWindow(position)", "position[1] + view.height",
                       "Type.ime()", "Type.navigationBars()", "getInsetsIgnoringVisibility"):
            self.assertIn(source, oracle)
        self.assertIn("maxOf(frame.imeBottom, frame.navigationBottom)", android)
        for method in ("fun testStatusBarVisibilityKeepsEveryNativeChatDrawStableAndImeLive()",
                       "fun testHomeReturnKeepsChatGeometryFromFirstNativeDraw()"):
            self.assertNotIn("SDK_INT", body(android, method))
        flow = (root / "scripts/smoke_boot.sh").read_text()
        self.assertIn("app.daiyu.android.ChatStatusBarInsetsTest", flow)
        self.assertIn("chat_status_bar_only", flow)

if __name__ == '__main__':
    unittest.main()
