"""Narrow source wiring guards; device IME behavior remains instrumentation coverage."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = Path('app/src/main/kotlin/app/daiyu/android')
CHAT = MAIN / 'ui/screens/chat/conversation'
ANDROID = Path('app/src/androidTest/kotlin/app/daiyu/android')


class ChatImeLifecycleContractTest(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text()

    def test_window_owns_insets_before_content_and_keeps_resize_dispatch(self):
        host = self.read(MAIN / 'MainActivity.kt')
        create = host.split('override fun onCreate(state: Bundle?)', 1)[1]
        self.assertLess(create.index('enableEdgeToEdge()'), create.index('setContent {'))
        self.assertIn('android:windowSoftInputMode="adjustResize"', self.read(Path('app/src/main/AndroidManifest.xml')))

    def test_ime_padding_is_local_to_conversation_not_header(self):
        screen = self.read(CHAT / 'ChatScreen.kt')
        self.assertEqual(1, screen.count('.imePadding()'))
        self.assertIn('ChatConversationLayers(Modifier.weight(1f).imePadding()', screen)
        header = screen.split('Column(', 1)[1].split('ChatTopBar(', 1)[0]
        self.assertNotIn('imePadding()', header)
        for forbidden in ('animateDpAsState', 'translationY', 'WindowInsets.ime.getBottom'):
            self.assertNotIn(forbidden, screen)

    def test_composer_reservation_uses_same_measure_not_snapshot_feedback(self):
        layers = self.read(CHAT / 'ChatComposerSurface.kt').split('internal fun ChatConversationLayers', 1)[1]
        self.assertIn('SubcomposeLayout', layers)
        self.assertLess(layers.index('subcompose("composer")'), layers.index('val bottomInset = editor.height.toDp()'))
        self.assertLess(layers.index('val bottomInset = editor.height.toDp()'), layers.index('subcompose("messages")'))
        self.assertIn('messages(bottomInset)', layers)
        self.assertLess(layers.index('content.placeRelative(0, 0)'), layers.index('editor.placeRelative(0, height - editor.height)'))
        for forbidden in ('onSizeChanged', 'mutableIntStateOf', 'mutableStateOf'):
            self.assertNotIn(forbidden, layers)

    def test_stop_cleanup_is_chat_scoped_and_does_not_restore_focus(self):
        helper = self.read(MAIN / 'ui/components/ChatInputIme.kt').split('internal fun DismissImeOnStop(', 1)[1]
        self.assertIn('var pendingStopDismiss = false', helper)
        stop = helper.split('Lifecycle.Event.ON_STOP -> {', 1)[1].split('Lifecycle.Event.ON_RESUME', 1)[0]
        self.assertIn('pendingStopDismiss = true', stop)
        self.assertIn('hide()', stop)
        self.assertIn('if (!pendingStopDismiss || restoreDismiss != null ||', helper)
        self.assertIn('Lifecycle.State.RESUMED', helper)
        self.assertIn('view.hasWindowFocus()', helper)
        self.assertLess(helper.index('pendingStopDismiss = false\n                    hide()'),
                        helper.index('view.postOnAnimation(work)'))
        self.assertIn('hideChatInputIme(focus, keyboard, view)', helper)
        self.assertIn('lifecycle.addObserver(observer)', helper)
        self.assertIn('lifecycle.removeObserver(observer)', helper)
        self.assertIn('restoreDismiss?.let(view::removeCallbacks)', helper)
        self.assertIn('removeOnWindowFocusChangeListener(windowFocus)', helper)
        for forbidden in ('ON_PAUSE', 'requestFocus(', '.show()'):
            self.assertNotIn(forbidden, helper)
        composer = self.read(CHAT / 'ChatComposer.kt')
        self.assertIn('DismissImeOnStop { dismiss ->', composer)
        self.assertIn('try { dismiss() } finally { lifecycleDismissInProgress = false }', composer)
        self.assertIn('!lifecycleDismissInProgress && !isInputDismissInProgress()', composer)
        self.assertNotIn('DismissImeOnStop(', self.read(MAIN / 'MainActivity.kt'))

    def test_stopped_editor_preserves_local_value_and_host_selection(self):
        composer = self.read(CHAT / 'ChatComposer.kt')
        change = composer.split('onValueChange = { value ->', 1)[1].split('enabled = state.enabled', 1)[0]
        guard = change.index('lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)')
        for edit in ('actions.onTextChange(next)', 'actions.onSelectionChange(', 'editingValue = TextFieldValue('):
            self.assertLess(guard, change.index(edit))
        self.assertNotIn('value.selection.collapsed', change)

    def test_programmatic_show_does_not_repeat_or_use_two_channels(self):
        helper = self.read(MAIN / 'ui/components/ChatInputIme.kt').split('internal suspend fun showChatInputIme', 1)[1].split('/** Returning', 1)[0]
        self.assertEqual(1, helper.count('requestFocus()'))
        self.assertIn('if (keyboard != null) keyboard.show()', helper)
        self.assertIn('else (view.context as? Activity)', helper)
        self.assertEqual(2, helper.count('if (!view.hasWindowFocus()) return'))
        self.assertNotIn('repeat(', helper)
        self.assertNotIn('delay(', helper)

    def test_regressions_cover_home_return_reopen_pause_only_and_same_measure(self):
        android = self.read(ANDROID / 'ChatComposerImeActivityTest.kt')
        home = android.split('private fun keyboardBackgroundRoundTrip', 1)[1].split('fun testReturningFromHome', 1)[0]
        self.assertIn('GLOBAL_ACTION_HOME', home)
        self.assertIn('Lifecycle.Event.ON_STOP', home)
        self.assertIn('stopped.await(5,', home)
        self.assertIn('FLAG_ACTIVITY_REORDER_TO_FRONT', home)
        self.assertNotIn('FLAG_ACTIVITY_CLEAR_TASK', home)
        returned = home.split('awaitHostWindowFocus(original)', 1)[1]
        before_reopen = returned.split('node("chat-draft").performTouchInput', 1)[0]
        self.assertNotIn('dismissIme()', before_reopen.replace('// No dismissIme here:', ''))
        for expected in ('assertIsNotFocused()', 'assertDraft(draft)', 'assertSelection(1, 4)', 'assertHostSelection(1, 4)',
                         'assertEquals(database, fixture.database.exportBackup())'):
            self.assertIn(expected, before_reopen)
        self.assertIn('settledFrame(visible = true, before = returned)', returned)
        self.assertIn('repeat(2) { keyboardBackgroundRoundTrip(hideBeforeLeaving = false) }', android)
        self.assertIn('repeat(2) { keyboardBackgroundRoundTrip(hideBeforeLeaving = true) }', android)
        self.assertIn('realPauseResume(activity)', android)
        measure = self.read(ANDROID / 'ui/screens/chat/conversation/ChatComposerMeasurePhaseTest.kt')
        self.assertIn('actual > 0', measure)
        self.assertIn('actual, reserved', measure)
        self.assertIn('editorHeight = 150.dp', measure)
        self.assertIn('viewportHeight = 240.dp', measure)


if __name__ == '__main__':
    unittest.main()
