"""Production wiring guards; work/isolation behavior lives in Compose runtime JUnit tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CHAT = APP / 'ui/screens/chat/conversation'


class ChatMenuScrollIsolationContract(unittest.TestCase):
    def test_page_passes_a_deferred_configuration_reader_to_the_menu_leaf(self):
        activity = (APP / 'MainActivity.kt').read_text()
        call = activity.split('DaiyuChatScreen(chatState.copy(', 1)[1].split('callUi =', 1)[0]
        before_reader, reader = call.split('readAutomaticCompression = {', 1)
        self.assertNotIn('automaticCompression = automaticCompression', before_reader)
        self.assertNotIn('automaticCompressionPending = automaticCompressionPending', before_reader)
        self.assertIn('ChatAutomaticCompressionState(', reader)
        self.assertIn('automaticCompression, automaticCompressionPending)', reader)
        for name in ('ChatScreen.kt', 'ChatTopBar.kt'):
            source = (CHAT / name).read_text()
            self.assertIn('readAutomaticCompression: (() -> ChatAutomaticCompressionState)? = null', source)
            self.assertIn('readAutomaticCompression = readAutomaticCompression', source)
        overflow = (CHAT / 'ChatMenus.kt').read_text().split('internal fun ChatOverflowMenu(', 1)[1].split('private fun ChatMenuItem(', 1)[0]
        self.assertLess(overflow.index('ChatAnchoredMenu('), overflow.index('ChatAutomaticCompressionContent('))
        self.assertIn('ChatAutomaticCompressionContent(readAutomaticCompression ?: {', overflow)
        self.assertIn('ChatAutomaticCompressionState(automaticCompression, automaticCompressionPending)', overflow)
        self.assertIn('ChatMenuAction.AUTO_COMPRESS -> compression.enabled', overflow)
        self.assertIn('ChatMenuAction.AUTO_COMPRESS -> compression.enabled != null && !compression.pending', overflow)

    def test_overlay_ownership_is_read_below_the_captured_page_restart_scope(self):
        source = (CHAT / 'ChatMenuLayer.kt').read_text()
        host, overlay = source.split('private fun BoxScope.ChatMenuOverlay(', 1)
        host = host.split('internal fun ChatMenuLayer(', 1)[1]
        overlay = overlay.split('internal fun ChatAnchoredMenu(', 1)[0]
        self.assertIn('ChatMenuCaptureContent(state) { capture ->', host)
        self.assertIn('Box(Modifier.fillMaxSize().captureForTopBar(if (capture) backdrop else null)) { content() }', host)
        self.assertIn('ChatMenuOverlay(state, backdrop, diagnostic, probe)', host)
        self.assertNotIn('state.active', host)
        self.assertNotIn('mutableStateOf(Rect.Zero)', host)
        self.assertIn('Modifier.matchParentSize().windowInsetsPadding(', overlay)
        self.assertIn('ChatMenuOverlayContent(state) { menu ->', overlay)
        runtime = (CHAT / 'ChatMenuOverlayContent.kt').read_text()
        self.assertIn('var active by mutableStateOf<T?>(null)', runtime)
        self.assertIn('content(state.active != null)', runtime)
        self.assertIn('state.active?.let { menu ->', runtime)
        self.assertIn('key(menu) { content(menu) }', runtime)
        leaf = (CHAT / 'ChatAutomaticCompressionContent.kt').read_text()
        self.assertIn('@Composable\ninternal fun ChatAutomaticCompressionContent(', leaf)
        self.assertIn('content(readState())', leaf)

    def test_capture_gates_only_the_modifier_for_the_full_mounted_lifetime(self):
        source = (CHAT / 'ChatMenuLayer.kt').read_text()
        host = source.split('internal fun ChatMenuLayer(', 1)[1].split('private fun BoxScope.ChatMenuOverlay(', 1)[0]
        self.assertEqual(1, host.count('{ content() }'))
        self.assertEqual(1, host.count('.captureForTopBar('))
        self.assertIn('captureForTopBar(if (capture) backdrop else null)', host)
        runtime = (CHAT / 'ChatMenuOverlayContent.kt').read_text()
        capture = runtime.split('internal fun <T : Any> ChatMenuCaptureContent(', 1)[1].split('/** Keep menu ownership', 1)[0]
        self.assertIn('content(state.active != null)', capture)
        self.assertNotIn('expanded', capture)
        owner = source.split('internal fun ChatAnchoredMenu(', 1)[1].split('internal fun ChatGlassMenuSurface(', 1)[0]
        for token in ('if ((expanded || mounted) && layer != null)', 'delay(PopupExitRetentionMillis)',
                      'DisposableEffect(layer, menu)', 'if (layer.active === menu) layer.active = null'):
            self.assertIn(token, owner)
        wrapper = (APP / 'ui/components/TopBarBackdrop.kt').read_text()
        self.assertIn('if (Build.VERSION.SDK_INT < 33 || backdrop == null) this else layerBackdrop(backdrop)', wrapper)
        # The shared production wrapper protects both nested hosts, not only an isolated fixture.
        self.assertIn('ChatMenuLayer(modifier.fillMaxSize())', (APP / 'ui/components/ConversationSidePaneScaffold.kt').read_text())
        self.assertIn('ChatMenuLayer(', (CHAT / 'ChatScreen.kt').read_text())

    def test_capture_replays_an_inner_display_list_instead_of_drawing_the_page_twice(self):
        source = (APP / 'ui/components/TopBarBackdrop.kt').read_text()
        helper = source.split('internal fun Modifier.captureForTopBar(', 1)[1].split('@Composable', 1)[0]
        self.assertIn('layerBackdrop(backdrop).graphicsLayer()', helper)
        self.assertNotIn('graphicsLayer().layerBackdrop', helper)
        self.assertIn('Build.VERSION.SDK_INT < 33 || backdrop == null', helper)
        renderer = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/BackdropDrawIsolationTest.kt').read_text()
        for token in ('Modifier.layerBackdrop(checkNotNull(backdrop))', 'AtomicIntegerArray',
                      'testProductionMenuOpenExitAndReopenDoNotRedrawTheStaticBodyPerAnimationFrame',
                      'testParentRedrawReusesBodyAndContentChangesAndResizeStayLive',
                      'node("draw-count-body").captureToImage()', 'initial, draws.get(0)',
                      'assertSame(modifier, modifier.captureForTopBar(null))'):
            self.assertIn(token, renderer)

    def test_resume_invalidates_inside_the_capture_display_list_without_remounting_content(self):
        source = (APP / 'ui/components/TopBarBackdrop.kt').read_text()
        helper = source.split('internal fun Modifier.captureForTopBar(', 1)[1].split('private data object', 1)[0]
        self.assertIn('layerBackdrop(backdrop).graphicsLayer().then(ResumeBackdropCaptureElement)', helper)
        node = source.split('private class ResumeBackdropCaptureNode', 1)[1].split('@Composable', 1)[0]
        for token in ('CompositionLocalConsumerModifierNode', 'ObserverModifierNode', 'LifecycleEventObserver',
                      'override fun onAttach() = observeLifecycle()',
                      'override fun onObservedReadsChanged() = observeLifecycle()',
                      'observeReads {', 'currentValueOf(LocalLifecycleOwner).lifecycle',
                      'if (current !== lifecycle)', 'current.addObserver(this)',
                      'if (event == Lifecycle.Event.ON_RESUME) invalidateDraw()',
                      'override fun ContentDrawScope.draw() = drawContent()'):
            self.assertIn(token, node)
        owner = node.split('if (current !== lifecycle)', 1)[1].split('override fun onStateChanged', 1)[0]
        self.assertLess(owner.index('lifecycle?.removeObserver(this)'), owner.index('current.addObserver(this)'))
        detach = node.split('override fun onDetach()', 1)[1]
        self.assertIn('lifecycle?.removeObserver(this)', detach)
        self.assertIn('lifecycle = null', detach)
        self.assertNotIn('delay(', node)
        self.assertNotIn('withFrameNanos', node)
        self.assertNotIn('key(', node)

    def test_real_chat_drawer_visual_state_does_not_follow_lifecycle_activation(self):
        activity = (APP / 'MainActivity.kt').read_text()
        root = activity.split('val rootChatContent: @Composable (Boolean) -> Unit = { routeActive ->', 1)[1]
        call = root.split('ConversationSidePaneScaffold(', 1)[1].split('onOpen =', 1)[0]
        self.assertIn('visible = retainedPage == "chat" && sidebarVisible', call)
        self.assertIn('backHandlerEnabled = routeActive', call)
        self.assertNotIn('visible = routeActive && sidebarVisible', call)
        # Pausing still revokes interaction; only the visual-intent wiring changes.
        host = (APP / 'ui/navigation/PageBackGestures.kt').read_text()
        self.assertIn('val rootActive = retainedRootActive && resumed', host)
        self.assertIn('PageRetainedContent(retainedRootVisible, rootActive)', host)
        self.assertIn('retainedRootContent(active)', host)
        tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ConversationPaneVisualTest.kt').read_text()
        for token in ('testRealOpenedDrawerKeepsPositionAcrossPauseResume',
                      'testRealOpenedDrawerKeepsPositionAcrossHomeResume',
                      'ViewTreeObserver.OnDrawListener', 'ViewTreeObserver.OnGlobalLayoutListener',
                      'whilePaused = {'):
            self.assertIn(token, tests)

    def test_refresh_and_toggle_keep_authoritative_io_and_pending_gates(self):
        source = (APP / 'MainActivity.kt').read_text()
        self.assertIn('onMore = { if (currentChat()) refreshAutomaticCompression() }', source)
        refresh = source.split('private fun refreshAutomaticCompression() {', 1)[1].split('private fun toggleAutomaticCompression()', 1)[0]
        self.assertIn('if (automaticCompressionPending) return', refresh)
        self.assertIn('automaticCompressionPending = true', refresh)
        self.assertIn('withContext(Dispatchers.IO)', refresh)
        self.assertIn('ProductionCompressionSettingsStore(applicationContext).snapshot().automatic', refresh)
        self.assertIn('automaticCompression = value.getOrNull()', refresh)
        self.assertIn('automaticCompressionPending = false', refresh)
        toggle = source.split('private fun toggleAutomaticCompression() {', 1)[1].split('private fun openChatModels()', 1)[0]
        for guard in ('val expected = automaticCompression ?: return', 'if (automaticCompressionPending) return',
                      'store.changeAutomatic(expected).automatic', 'automaticCompression = result.getOrNull()'):
            self.assertIn(guard, toggle)

    def test_glass_placement_exit_and_scroll_mechanisms_remain_enabled(self):
        source = (CHAT / 'ChatMenuLayer.kt').read_text()
        for token in ('rememberTopBarBackdrop()', 'blurRadius = 24f', 'saturation = 1.35f',
                      '.clip(shape).then(blur)', 'rememberPopupMotion(menu.expanded())',
                      'BackHandler(enabled = menu.expanded(), onBack = menu.onDismiss)',
                      '0.15f + 0.85f * motion.fraction.value', 'TransformOrigin(1f, 0f)',
                      'surface.place(x, y)', 'delay(PopupExitRetentionMillis)',
                      'if (!menu.expanded()) event.changes.forEach { it.consume() }',
                      'else Modifier.clearAndSetSemantics {}', 'menu.onDismiss()',
                      '.verticalScroll(rememberScrollState())'):
            self.assertIn(token, source)
        messages = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('snapshotFlow { Triple(scrollResumed, currentDragging, listState.isScrollInProgress) }', messages)
        self.assertIn('LazyColumn(state = listState, userScrollEnabled = pageActive && !initialPositionPending', messages)
        self.assertNotIn('readAutomaticCompression', messages)


if __name__ == '__main__':
    unittest.main()
