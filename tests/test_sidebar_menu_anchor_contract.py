"""Sidebar context menus use the active press, not their layout parent's corner."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android/ui"


class SidebarMenuAnchorContractTest(unittest.TestCase):
    def test_row_records_touch_without_replacing_click_or_accessibility_semantics(self):
        row = (SRC / "components/ConversationSidePaneScaffold.kt").read_text().split(
            "private fun ConversationTextRow(", 1)[1].split("private fun EmptyConversations", 1)[0]
        for fragment in ("awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)",
                         "pressedPosition = down.position", "pressedPosition = null",
                         "coordinates.localToRoot(it)", "Rect(point, point)",
                         "else coordinates.boundsInRoot()", ".combinedClickable(",
                         "anchorBounds = actionMenuAnchor", 'drawer-conversation-menu:${conversation.id}'):
            self.assertIn(fragment, row)
        self.assertNotIn(".consume()", row)

    def test_menu_forwarding_preserves_default_anchors_for_other_callers(self):
        pane = (SRC / "components/ConversationSidePaneScaffold.kt").read_text()
        menu = pane.split("private fun DrawerGlassMenu(", 1)[1]
        self.assertIn("anchorBounds: Rect? = null", menu)
        self.assertIn("anchorBounds = anchorBounds", menu)
        layer = (SRC / "screens/chat/conversation/ChatMenuLayer.kt").read_text()
        self.assertIn("val latestAnchorBounds by rememberUpdatedState(anchorBounds)", layer)
        self.assertIn("{ latestAnchorBounds ?: anchor }", layer)
        self.assertIn("parentLayoutCoordinates?.boundsInRoot()", layer)

    def test_real_touch_geometry_regression_covers_scroll_reopen_edges_and_semantics(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ConversationPaneVisualTest.kt").read_text()
        method = test.split("fun testLongPressMenuFollowsTouchAfterScrollingAndReopeningWithinNarrowHost()", 1)[1].split("fun testShortcuts", 1)[0]
        for fragment in ("listOf(false, true)", "fontScale = 1.3f", "longClick(Offset(",
                         'performScrollToKey("touch-8")', 'performScrollToKey("touch-6")',
                         'performScrollToKey("touch-35")', "SemanticsActions.OnLongClick",
                         "menu.positionInRoot()", "menu.size.width", "expectedX, menu.left",
                         "expectedY, menu.top", "assertPlacement(row, accessibleMenu)",
                         "assertPlacement(Rect(touch, touch), menu)",
                         "assertEquals(before, bounds(tag))", "assertNull(action)",
                         "assertEquals(0, fixture.opens.get())"):
            self.assertIn(fragment, method)


if __name__ == "__main__":
    unittest.main()
