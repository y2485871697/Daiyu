"""Keep IME focus-transfer policy through the production chat caller."""
from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[1]
M=R/'app/src/main/kotlin/app/daiyu/android/ui/components'
class R51BatchContract(unittest.TestCase):
 def test_window_dismissal_returns_the_new_editor_guard_to_chat(self):
  managed=(M/'ManagedInput.kt').read_text();chat=(M/'ChatInputIme.kt').read_text()
  helper=managed.split('internal fun clearManagedInputFocus',1)[1].split('private class ComposeInputRegion',1)[0]
  self.assertIn('): Boolean {',helper)
  self.assertIn('if (host != null) return host.dismiss(view, action)',helper)
  self.assertLess(helper.index('action()'),helper.index('return true'))
  common=managed.split('fun dismiss(view: View, clear: () -> Unit): Boolean {',1)[1].split('fun remove(entry:',1)[0]
  self.assertIn('onCheckIsTextEditor() == true)) return false',common)
  self.assertLess(common.index('return false'),common.index('hideSoftInputFromWindow'))
  self.assertLess(common.index('hideSoftInputFromWindow'),common.index('return true'))
  body=chat.split('internal fun hideChatInputIme',1)[1].split('internal suspend fun showChatInputIme',1)[0]
  self.assertIn('if (!clearManagedInputFocus(view) { focusManager.clearFocus(force = true) }) return',body)
  self.assertLess(body.index('}) return'),body.index('keyboard?.hide()'))
 def test_real_editor_transfer_keeps_outer_hide_and_window_ime_oracles(self):
  source=(R/'app/src/androidTest/kotlin/app/daiyu/android/ChatComposerImeActivityTest.kt').read_text()
  body=source.split('fun testDismissDoesNotHideImeWhenBlurListenerTransfersToAnotherEditor()',1)[1].split('fun testOutsideBlankAndTitleTaps',1)[0]
  for token in ['manageInput(activity.window)','second.requestFocus()','hideChatInputIme(focus, keyboard, first)','0, keyboardHideCalls','awaitStableWindowFrame(second)','bottom > 0','first.selectionStart','first.selectionEnd']:
   self.assertIn(token,body)
  self.assertEqual(1,body.count('showSoftInput('))
if __name__=='__main__':unittest.main()
