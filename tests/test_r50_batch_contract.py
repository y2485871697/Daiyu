"""Production dismissal shares window focus ownership; rendering oracles stay unchanged."""
from pathlib import Path
import unittest
R=Path(__file__).resolve().parents[1]
M=R/'app/src/main/kotlin/app/daiyu/android/ui/components'
T=R/'app/src/androidTest/kotlin/app/daiyu/android'
class R50BatchContract(unittest.TestCase):
 def test_chat_hiding_uses_window_owned_dismissal_before_focus_is_cleared(self):
  managed=(M/'ManagedInput.kt').read_text();chat=(M/'ChatInputIme.kt').read_text()
  helper=managed.split('internal fun clearManagedInputFocus',1)[1].split('private class ComposeInputRegion',1)[0]
  for token in ['view.inputWindow()?.let { InputWindows.windows[it] }','host.dismiss(view, action)','root.requestFocus()','clear()','InputDismissal.run(action)']:self.assertIn(token,helper)
  self.assertLess(helper.index('root.requestFocus()'),helper.index('clear()'))
  self.assertIn('clearManagedInputFocus(view) { focusManager.clearFocus(force = true) }',chat)
  common=managed.split('fun dismiss(view: View, clear: () -> Unit)',1)[1].split('fun remove(entry:',1)[0]
  self.assertLess(common.index('holdRestingFocus()'),common.index('InputDismissal.run { clear() }'))
  self.assertIn('if (entries.any { it.focused() } ||',common)
  self.assertIn('hideSoftInputFromWindow(view.windowToken, 0)',common)
 def test_lazy_last_row_waits_for_queued_scroll_without_losing_height_checks(self):
  source=(T/'BottomSheetEdgeActivityTest.kt').read_text();body=source.split('if (state == 4)',1)[1].split('close("conversation-agent-sheet")',1)[0]
  self.assertLess(body.index('performScrollToIndex(rows.lastIndex)'),body.index('ui.mainClock.advanceTimeByFrame()'))
  self.assertLess(body.index('ui.waitForIdle()'),body.index('node("subagent-status:task-39").scrollToVisible()'))
  self.assertEqual(2,body.count('rawBounds(node("conversation-agent-sheet").fetchSemanticsNode()).height, 2f'))
  self.assertIn('scrollToVisible().performClick()',body)
 def test_speed_overflow_keeps_the_same_oracle_and_records_geometry(self):
  source=(T/'SubAgentCollaborationLayoutTest.kt').read_text()
  for token in ['assertFalse(geometry, label.hasVisualOverflow)','label.didOverflowWidth','label.didOverflowHeight','label.layoutInput.constraints','label.layoutInput.density','label.isLineEllipsized(0)','assertEquals(10f, label.layoutInput.style.fontSize.value, .01f)','assertEquals(16f, label.layoutInput.style.lineHeight.value, .01f)']:self.assertIn(token,source)
if __name__=='__main__':unittest.main()
