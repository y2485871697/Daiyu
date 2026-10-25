"""Source guards, not device animation or frame-rate evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CHAT = MAIN / 'ui/screens/chat/conversation'


class ChatImeMotionContractTest(unittest.TestCase):
    def test_hide_has_one_owner_and_native_fallback_is_exclusive(self):
        helper = (MAIN / 'ui/components/ChatInputIme.kt').read_text().split('internal fun hideChatInputIme(', 1)[1].split('internal suspend fun', 1)[0]
        self.assertIn('clearManagedInputFocus(view, hideIme = {', helper)
        self.assertIn('if (keyboard != null) keyboard.hide() else hideManagedInputIme(view)', helper)
        self.assertNotIn('WindowCompat.getInsetsController', helper)
        managed = (MAIN / 'ui/components/ManagedInput.kt').read_text()
        native = managed.split('internal fun hideManagedInputIme(', 1)[1].split('/** Let blur', 1)[0]
        self.assertIn('if (controller != null) controller.hide(', native)
        self.assertIn('else (view.context.getSystemService', native)
        dismiss = managed.split('fun dismiss(view: View, clear:', 1)[1].split('fun remove(', 1)[0]
        self.assertEqual(1, dismiss.count('hideIme()'))
        self.assertLess(dismiss.index('return false'), dismiss.index('hideIme()'))
        fallback = managed.split('internal fun clearManagedInputFocus(', 1)[1].split('internal fun hideManagedInputIme', 1)[0]
        self.assertIn('host.dismiss(view, action, hideIme)', fallback)
        self.assertEqual(1, fallback.count('hideIme()'))
        self.assertLess(fallback.index('return false'), fallback.index('hideIme()'))

    def test_send_and_append_dismiss_before_original_actions_not_pause(self):
        composer = (CHAT / 'ChatComposer.kt').read_text()
        click = composer.split('ChatIconButton(primaryIcon, primaryLabel, {', 1)[1].split('}, Modifier.size(32.dp)', 1)[0]
        self.assertIn('if (!textAction) actions.onPrimaryTap()', click)
        self.assertIn('finally { lifecycleDismissInProgress = false }', click)
        for call in ('actions.onSend(state.text)', 'actions.onSupplement(state.text)'):
            self.assertLess(click.index('hideChatInputIme(focus, keyboard, view)'), click.index(call))
        self.assertNotIn('postDelayed', click)
        self.assertNotIn('requestFocus', click)
        self.assertNotIn('onTextChange', click)

    def test_editing_motion_excludes_ime_and_preserves_composer_blank_region(self):
        composer = (CHAT / 'ChatComposer.kt').read_text()
        self.assertIn('Column(modifier.managedInput()', composer)
        self.assertIn('Column(Modifier.fillMaxWidth().animateContentSize(', composer)
        editor = composer.split('Column(Modifier.fillMaxWidth().animateContentSize(', 1)[1]
        self.assertLess(editor.index('ComposerReferenceChips('), editor.index('BasicTextField('))
        self.assertIn('\n                }\n                Box(Modifier.fillMaxWidth().padding(start = 13.dp', editor)
        self.assertLess(editor.index('BasicTextField('), editor.index('testTag("chat-composer-bottom")'))
        self.assertEqual(1, composer.count('animateContentSize('))
        self.assertIn('alignment = Alignment.BottomCenter', composer)
        self.assertIn('spring(dampingRatio = 1f, stiffness = 500f)', composer)
        self.assertNotIn('imePadding()', composer)
        self.assertNotIn('windowInsetsPadding(', composer)
        screen = (CHAT / 'ChatScreen.kt').read_text()
        self.assertEqual(1, screen.count('.windowInsetsPadding('))
        self.assertIn('WindowInsets.navigationBars.union(WindowInsets.ime)', screen)
        self.assertNotIn('animateContentSize', screen)

    def test_device_sources_cover_inset_changes_animation_and_hide_count(self):
        testroot = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'
        geometry = (testroot / 'ui/screens/chat/conversation/ChatComposerMeasurePhaseTest.kt').read_text()
        self.assertIn('testMergedImeInsetsKeepComposerAboveKeyboardAcrossInsetChanges', geometry)
        self.assertIn('listOf(0, 220, 320, 110, 280, 0)', geometry)
        self.assertIn('testComposerContentShrinksThroughIntermediateBottomAnchoredFrames', geometry)
        self.assertIn('ui.mainClock.advanceTimeByFrame()', geometry)
        ime = (testroot / 'ChatComposerImeActivityTest.kt').read_text()
        self.assertIn('testManagedAndFallbackDismissDispatchHideExactlyOnceAfterClear', ime)
        self.assertIn('assertEquals(listOf("clear", "hide"), events)', ime)
        self.assertIn('testSendAndAppendReleaseImeBeforeDispatchAndPreserveDraft', ime)
        self.assertIn('for (phase in listOf(ComposerPhase.IDLE, ComposerPhase.RUNNING))', ime)


if __name__ == '__main__':
    unittest.main()
