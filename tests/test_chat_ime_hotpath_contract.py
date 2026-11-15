"""IME animation ownership guards; runtime composition is covered on both APIs."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class ChatImeHotpathContractTest(unittest.TestCase):
    def test_host_does_not_subcompose_the_entire_chat_for_each_ime_height(self):
        host = (MAIN / 'MainActivity.kt').read_text()
        self.assertNotIn('import androidx.compose.foundation.layout.imePadding', host)
        self.assertNotIn('.imePadding()', host)
        start = host.index('val contextUsageLabels = ')
        wrapper = host[start:host.index('DaiyuChatScreen(', start)]
        self.assertIn('BoxWithConstraints(Modifier.fillMaxSize())', wrapper)
        self.assertIn('val extrasMaxHeight = minOf(200.dp, maxHeight * 0.35f)', wrapper)
        self.assertNotIn('WindowInsets.ime', wrapper)
        screen = (MAIN / 'ui/screens/chat/conversation/ChatScreen.kt').read_text()
        self.assertEqual(1, screen.count('WindowInsets.navigationBars.union(WindowInsets.ime)'))
        self.assertIn('ChatConversationLayers(Modifier.weight(1f)', screen)

    def test_list_height_is_read_by_measure_not_item_composition(self):
        source = (MAIN / 'ui/screens/chat/conversation/ChatMessageList.kt').read_text()
        body = source.split('internal fun ChatMessageList(', 1)[1].split('internal fun UserTurn(', 1)[0]
        self.assertNotIn('BoxWithConstraints(', body)
        self.assertIn('remember { mutableIntStateOf(0) }', body)
        self.assertIn('viewportHeightPx.intValue = constraints.maxHeight', body)
        self.assertIn('listViewportHeight = { viewportHeightPx.intValue }', body)
        viewport = source.split('internal fun StreamingTailContainer(', 1)[1].split('internal fun ChatMessageList(', 1)[0]
        self.assertIn('val safeBottom = listViewportHeight() - clearance()', viewport)
        self.assertIn('val currentListHeight = listViewportHeight()', viewport)
        tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageListMeasurePhaseTest.kt').read_text()
        self.assertIn('testRealStreamingListViewportResizeDoesNotRecomposeTurnContent', tests)
        self.assertIn('onTurnComposed = observe', tests)

    def test_navigation_observes_ime_visibility_not_every_pixel(self):
        source = (MAIN / 'ui/navigation/PageBackGestures.kt').read_text()
        helper = source.split('internal fun rememberPageImeVisible(', 1)[1].split('internal class PageBackGestureState', 1)[0]
        self.assertIn('remember(insets, density)', helper)
        self.assertIn('derivedStateOf { insets.getBottom(density) > 0 }', helper)
        self.assertIn('val imeVisible = rememberPageImeVisible()', source)
        self.assertNotIn('WindowInsets.ime.getBottom(LocalDensity.current)', source)
        self.assertIn('canNavigateBack && swipeDismissEnabled && resumed && !imeVisible', source)

    def test_instrumentation_covers_intermediate_and_terminal_ime_frames(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageImeCompositionActivityTest.kt').read_text()
        for fragment in ('rememberPageImeVisible(insets)', 'SideEffect',
                         'listOf(20, 80, 160, 280, 360, 210, 90, 1)',
                         'opened, compositions.get()', 'insets.bottom = 0', 'assertFalse(visible.get())'):
            self.assertIn(fragment, test)


if __name__ == '__main__':
    unittest.main()
