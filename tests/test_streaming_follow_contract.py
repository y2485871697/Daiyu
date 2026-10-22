"""Source guards for the frame-driven chat follow wiring, not runtime UI results."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageList.kt"
DEVICE = ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatStreamingFollowTest.kt"


class StreamingFollowContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = CHAT.read_text()
        cls.follow = cls.source.split("// Keep a single motion", 1)[1].split("// Read confirmation", 1)[0]

    def test_height_updates_do_not_restart_the_motion_collector(self):
        observe, animate = self.follow.split("}.collectLatest { canFollow ->", 1)
        self.assertNotIn("layoutInfo", observe)
        self.assertIn("followBottom && !currentDragging", observe)
        self.assertNotIn("index to overflow", observe)
        self.assertIn("val motion = BottomFollowMotion()", animate)
        self.assertLess(animate.index("val motion = BottomFollowMotion()"), animate.index("while (true)"))
        self.assertNotIn("LaunchedEffect(turns", self.follow)

    def test_measured_overflow_is_integrated_not_paid_as_a_single_scroll(self):
        self.assertIn("val frameTime = withFrameNanos { it }", self.follow)
        self.assertIn("motion.step(overflow.toFloat(), frameTime, currentDensity)", self.follow)
        self.assertIn("listState.scrollBy(delta)", self.follow)
        self.assertNotIn("scrollBy(overflow.toFloat())", self.follow)
        self.assertNotIn("animateScrollToItem", self.follow)
        self.assertIn("if (overflow <= 0) {", self.follow)
        self.assertIn("}.first { it }", self.follow)
        self.assertNotIn("scrollBy(delta) <= 0f", self.follow)
        self.assertIn("finally {\n                motion.reset()", self.follow)

    def test_explicit_scroll_and_touch_keep_ownership(self):
        self.assertIn("currentScrollSerial != null && currentScrollSerial != consumedScrollSerial", self.follow)
        self.assertIn("currentScrollSerial == null || currentScrollSerial == consumedScrollSerial", self.follow)
        self.assertIn("if (!followBottom || currentDragging || currentTurns.isEmpty()", self.follow)
        self.assertIn("followBottom = false; wasDragged = false", self.source)
        self.assertIn("consumedScrollSerial = currentScrollSerial", self.source)
        self.assertIn("listState.animateScrollToItem(index)", self.source)
        self.assertIn("readyTurns[it.listKey] == it", self.source)

    def test_idle_wait_rechecks_current_layout_without_ending_the_worker(self):
        idle = self.follow.split("if (overflow <= 0) {", 1)[1].split("val delta = motion.step", 1)[0]
        self.assertIn("motion.reset()", idle)
        self.assertIn("snapshotFlow {", idle)
        self.assertIn("val nextLayout = listState.layoutInfo", idle)
        self.assertIn("}.first { it }", idle)
        self.assertIn("continue", idle)
        self.assertNotIn("break", self.follow)
        self.assertIn("if (delta > 0f) listState.scrollBy(delta)", self.follow)

    def test_domain_motion_algorithm_remains_single_source(self):
        self.assertIn("import app.daiyu.android.domain.BottomFollowMotion", self.source)
        self.assertIn("val currentDensity by rememberUpdatedState(density.density)", self.source)
        self.assertNotIn("2400f", self.source)
        self.assertNotIn("720f", self.source)

    def test_device_regressions_cover_frames_moving_targets_touch_and_navigation(self):
        device = DEVICE.read_text()
        for token in ["testLineGrowthUsesSeveralForwardFramesAndRetainsMotionWhenTargetMoves",
                      "testTouchCancelsAnActiveMotionUntilAnExplicitBottomRequest",
                      "testExactMessageNavigationWinsOverAnActiveFollow",
                      "testIdleFollowWakesForEachLaterBurstWithoutANewScrollRequest", "height += 26.dp",
                      "movement <= 14f * density", "height += 78.dp", "frame()",
                      "Continuous growth cannot starve follow", "ui.mainClock.autoAdvance = false",
                      "ui.mainClock.advanceTimeByFrame()", "positionInRoot().y",
                      "Touch cancels momentum as well as later height updates",
                      'messageId = "u1", followBottom = false', "fixture.opens.get()"]:
            self.assertIn(token, device)


if __name__ == "__main__":
    unittest.main()
