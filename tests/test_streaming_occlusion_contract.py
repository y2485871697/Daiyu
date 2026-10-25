"""Source guards for composer-aware follow; not Android runtime evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageList.kt"
DEVICE = ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatStreamingOcclusionTest.kt"


class StreamingOcclusionContract(unittest.TestCase):
    def test_follow_resolves_occluded_tail_before_smoothing_remaining_spacing(self):
        source = CHAT.read_text()
        follow = source.split("// Keep a single motion", 1)[1].split("// Read confirmation", 1)[0]
        self.assertIn("val visibleBottom = layout.viewportEndOffset - currentOcclusionPx", follow)
        self.assertIn("val obscuredTail = if (currentOcclusionPx > 0)", follow)
        self.assertIn("(last.offset + last.size - visibleBottom).coerceAtLeast(0)", follow)
        self.assertIn("maxOf(motion.step(overflow.toFloat(), frameTime, currentDensity), obscuredTail.toFloat())", follow)
        self.assertLess(follow.index("if (!followBottom || currentDragging"), follow.index("val obscuredTail"))
        self.assertIn("if (delta > 0f) listState.scrollBy(delta)", follow)

    def test_device_sources_use_real_composer_measure_and_unclipped_tail_geometry(self):
        source = DEVICE.read_text()
        for token in ["ChatConversationLayers(", "composerBottomInset = bottomInset",
                      "positionInRoot().y", "coordinates.size.height", "boundsInRoot.top",
                      "testStreamingGrowthKeepsTailAboveComposer", "testComposerAndViewportChangesKeepFollowVisible",
                      "testTouchAndExactNavigationRetainScrollOwnership", "assertEquals(0, fixture.opens.get())"]:
            self.assertIn(token, source)


if __name__ == "__main__":
    unittest.main()
