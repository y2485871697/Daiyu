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
        self.assertIn('Box(Modifier.fillMaxSize().captureForTopBar(backdrop)) { content() }', host)
        self.assertIn('ChatMenuOverlay(state, backdrop, diagnostic, probe)', host)
        self.assertNotIn('state.active', host)
        self.assertNotIn('mutableStateOf(Rect.Zero)', host)
        self.assertIn('Modifier.matchParentSize().windowInsetsPadding(', overlay)
        self.assertIn('ChatMenuOverlayContent(state) { menu ->', overlay)
        runtime = (CHAT / 'ChatMenuOverlayContent.kt').read_text()
        self.assertIn('var active by mutableStateOf<T?>(null)', runtime)
        self.assertIn('state.active?.let { menu ->', runtime)
        self.assertIn('key(menu) { content(menu) }', runtime)
        leaf = (CHAT / 'ChatAutomaticCompressionContent.kt').read_text()
        self.assertIn('@Composable\ninternal fun ChatAutomaticCompressionContent(', leaf)
        self.assertIn('content(readState())', leaf)

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
        self.assertIn('LazyColumn(state = listState, userScrollEnabled = !initialPositionPending', messages)
        self.assertNotIn('readAutomaticCompression', messages)


if __name__ == '__main__':
    unittest.main()
