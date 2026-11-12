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
        cls.guard = cls.source.split("fun canFollowBottom(): Boolean =", 1)[1].split("\n\n", 1)[0]
        cls.follow = cls.source.split("// Keep a single motion", 1)[1].split("// Read confirmation", 1)[0]

    def test_height_updates_do_not_restart_the_motion_collector(self):
        self.assertIn("LaunchedEffect(listState, revealResetEpoch)", self.follow)
        self.assertIn("snapshotFlow {", self.follow)
        self.assertIn("Triple(canFollowBottom(),", self.follow)
        self.assertIn("val motion = BottomFollowMotion()", self.follow)
        self.assertNotIn("while (true)", self.follow)
        self.assertIn("withFrameNanos", self.follow)
        self.assertIn("if (observedViewport != null && observedViewport != viewport) alignViewport = true", self.follow)
        alignment = self.follow.split("if (alignment) {", 1)[1].split("}", 1)[0]
        self.assertIn("motion.reset()", alignment)
        self.assertNotIn("LaunchedEffect(turns", self.follow)

    def test_measured_overflow_is_integrated_not_paid_as_a_single_scroll(self):
        self.assertIn("val followDistance = maxOf(overflow.toFloat(), obscuredTail.toFloat())", self.follow)
        self.assertIn("if (followDistance <= 0f)", self.follow)
        self.assertIn("val alignment = alignViewport || resumeFollowPending", self.follow)
        self.assertIn("var delta = if (alignment) followDistance else 0f", self.follow)
        self.assertIn("val frameFollowDistance = maxOf(frameOverflow.toFloat(), frameObscuredTail.toFloat())", self.follow)
        self.assertIn("delta = motion.step(frameFollowDistance, frameTimeNanos, currentDensity)", self.follow)
        self.assertNotIn("delta = maxOf(", self.follow)
        self.assertNotIn("System.nanoTime()", self.follow)
        self.assertIn("listState.scrollBy(delta)", self.follow)
        self.assertIn("if (followDistance <= 0f)", self.follow)
        self.assertIn("while (canFollowBottom())", self.follow)
        self.assertNotIn("alignViewport || resumeFollowPending) overflow.toFloat() else {", self.follow)
        self.assertNotIn("animateScrollToItem", self.follow)
        self.assertIn("maxOf(frameOverflow.toFloat(), frameObscuredTail.toFloat())", self.follow)

    def test_explicit_scroll_and_touch_keep_ownership(self):
        self.assertIn("if (!canFollow)", self.follow)
        self.assertIn("scrollResumed && followBottom && !currentDragging", self.guard)
        self.assertIn("followBottom = false; wasDragged = false", self.source)
        self.assertIn("consumedScrollSerial = currentScrollSerial", self.source)
        self.assertIn("listState.animateScrollToItem(index)", self.source)
        self.assertIn("readyTurns[it.listKey] == it", self.source)
        self.assertIn("readyTurns[tail.listKey] == tail && revealedTurns[tail.listKey] == tail", self.follow)

    def test_idle_wait_rechecks_current_layout_without_ending_the_worker(self):
        self.assertIn("if (followDistance <= 0f)", self.follow)
        idle = self.follow.split("if (followDistance <= 0f)", 1)[1].split("val alignment =", 1)[0]
        self.assertIn("motion.reset()", idle)
        self.assertIn("return@collect", idle)
        self.assertNotIn("break", self.follow)
        self.assertIn("withFrameNanos { frameTimeNanos ->", self.follow)
        self.assertIn("if (delta > 0f && canFollowBottom())", self.follow)

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
                      "ui.mainClock.advanceTimeByFrame()", "positionInRoot().y", "composerBottomInset = 64.dp",
                      "Touch cancels momentum as well as later height updates",
                      'messageId = "u1", followBottom = false', "fixture.opens.get()"]:
            self.assertIn(token, device)


    def test_manual_clock_mutations_do_not_implicitly_advance_an_extra_frame(self):
        device = DEVICE.read_text()
        controlled = False
        mutations = 0
        for line in device.splitlines():
            if "ui.mainClock.autoAdvance = false" in line:
                controlled = True
            elif "ui.mainClock.autoAdvance = true" in line:
                controlled = False
            if controlled:
                self.assertNotIn("mainThread {", line)
                if "ui.runOnUiThread {" in line:
                    mutations += 1
        self.assertEqual(6, mutations)
        frame = device.split("private fun frame(): Float {", 1)[1].split("\n    }", 1)[0]
        self.assertEqual(1, frame.count("advanceTimeByFrame()"))
        self.assertIn("movement <= 14f * density", device)


if __name__ == "__main__":
    unittest.main()
