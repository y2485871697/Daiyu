"""Source guards only; callback completion still requires Android regression."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/AssistantPickerVisualTest.kt'


class AssistantPickerClickCompletionContractTest(unittest.TestCase):
    def body(self):
        text = SOURCE.read_text(encoding='utf-8')
        return text.split('fun testActionsKeepPreviewGeometryAndEditUsesOriginalId()', 1)[1].split('fun testSearchStillFiltersRealProfilesAndSelectionUsesOriginalId()', 1)[0]

    def test_real_single_edit_click_completes_before_original_id_assertion(self):
        body = self.body()
        click = 'ui.onNodeWithTag("assistant-action-edit").performClick()'
        self.assertEqual(1, body.count(click))
        self.assertEqual(1, body.count('awaitVisualIdle()'))
        self.assertIn(click + '\n            awaitVisualIdle()\n            assertEquals("visual-a", edited)', body)
        self.assertEqual(2, body.count('performTouchInput { longClick() }'))
        for forbidden in ['Thread.sleep', 'SystemClock.sleep', 'performSemanticsAction', 'awaitDialogEnter', 'awaitNodeWindowFocus', 'ui.waitUntil', 'mainThread {']:
            self.assertNotIn(forbidden, body)

    def test_theme_scale_geometry_and_original_selection_oracles_remain(self):
        body = self.body()
        for oracle in [
            'for (dark in listOf(false, true)) for (scale in listOf(1f, .8f))',
            'ui.onNodeWithContentDescription("关闭").assertDoesNotExist()',
            'ui.onNodeWithContentDescription("返回").assertHasClickAction()',
            'for (row in listOf(copy, edit, delete)) assertEquals(48f, dp(row.height), .6f)',
            'assertEquals(6f, dp(edit.top - copy.bottom), .6f)',
            'assertEquals(6f, dp(delete.top - edit.bottom), .6f)',
            'assertEquals(copy.left, edit.left, .6f)',
            'assertEquals(copy.right, delete.right, .6f)',
            'assertType(text(R.string.assistant_actions_title), 18.sp, 26.sp)',
            'assertType("Visual A", 15.sp, 22.sp)',
            'assertType("First description", 12.sp, 18.sp)',
            'assertType(text(R.string.ui_edit_assistant), 14.sp, 20.sp)',
            'assertEquals(if (dark) Color(0xFFDF9CA9) else Color(0xFFAE687A),',
            'ui.onNodeWithText(text(R.string.assistant_back_to_list)).performClick()',
            'ui.onNodeWithTag("assistant-item-visual-a").assertIsDisplayed()',
            'assertNull(selected)',
            'assertEquals("visual-a", edited)',
        ]:
            self.assertIn(oracle, body)


if __name__ == '__main__':
    unittest.main()
