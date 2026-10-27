"""Source guards for composer-aware follow; not Android runtime evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageList.kt"
DEVICE = ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatStreamingOcclusionTest.kt"


class StreamingOcclusionContract(unittest.TestCase):
    def test_follow_protects_minimum_clearance_before_smoothing_remaining_spacing(self):
        source = CHAT.read_text()
        follow = source.split("// Keep a single motion", 1)[1].split("// Read confirmation", 1)[0]
        self.assertIn("val visibleBottom = layout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx", follow)
        self.assertIn("val currentFollowClearancePx by rememberUpdatedState(with(density) { 16.dp.roundToPx() })", source)
        self.assertIn("bottom = composerBottomInset + 24.dp", source)
        self.assertIn("val obscuredTail = if (currentOcclusionPx > 0)", follow)
        self.assertIn("(last.offset + last.size - visibleBottom).coerceAtLeast(0)", follow)
        self.assertIn("maxOf(motion.step(overflow.toFloat(), frameTime, currentDensity), obscuredTail.toFloat())", follow)
        self.assertLess(follow.index("if (!canFollowBottom())"), follow.index("val obscuredTail"))
        self.assertIn("if (delta > 0f) listState.scrollBy(delta)", follow)

    def test_real_streaming_text_is_checked_before_settling(self):
        source = (DEVICE.parent / "ChatMessageSpacingTest.kt").read_text()
        live = source.split("fun testRealStreamingTextKeepsClearanceDuringIntermediateFrames()", 1)[1].split("fun testShortReply", 1)[0]
        for token in ["ui.mainClock.autoAdvance = false", "ui.runOnUiThread", "SemanticsProperties.Text",
                      "hasAnyAncestor(hasTestTag(\"chat-turn:tail\"))", "useUnmergedTree = true",
                      "coordinates.positionInRoot().y + coordinates.size.height", "gap >= 24f",
                      "ui.mainClock.advanceTimeByFrame()", "sawNewestLine = assertLiveClearance(marker) || sawNewestLine",
                      "Newest streaming line must actually be displayed", "originalComposer.top, composer.top, 2f",
                      "originalComposer.bottom, composer.bottom, 2f", "finally", "ui.mainClock.autoAdvance = true"]:
            self.assertIn(token, live)
        self.assertNotIn("ui.waitUntil", live)
        self.assertNotIn("awaitVisualIdle", live)
        self.assertIn("phase = if (running) ComposerPhase.RUNNING else ComposerPhase.IDLE", source)

    def test_post_layout_clearance_uses_the_same_follow_ownership_and_exact_tail(self):
        source = CHAT.read_text()
        correction = source.split("fun correctFollowClearance() {", 1)[1].split("val requestedIndex", 1)[0]
        for token in ("!canFollowBottom()", "clearanceLayout.correcting", "clearanceLayout.active",
                      "it.index == currentTailIndex && it.key == currentTailKey",
                      "last.offset + last.size - visibleBottom", "dispatchRawDelta", "forceRemeasure()",
                      "finally", "clearanceLayout.correcting = false"):
            self.assertIn(token, correction)
        self.assertIn("coordinates.isAttached && turn.listKey == currentTailKey", source)
        self.assertIn("status = if (running) RunStatus.RUNNING else RunStatus.COMPLETED, isStreaming = running", (DEVICE.parent / "ChatMessageSpacingTest.kt").read_text())

    def test_device_sources_use_real_composer_measure_and_unclipped_tail_geometry(self):
        source = DEVICE.read_text()
        for token in ["ChatConversationLayers(", "composerBottomInset = bottomInset",
                      "positionInRoot().y", "coordinates.size.height", "boundsInRoot.top",
                      "testStreamingGrowthKeepsTailAboveComposer", "testComposerAndViewportChangesKeepFollowVisible",
                      "testTouchAndExactNavigationRetainScrollOwnership", "assertEquals(0, fixture.opens.get())",
                      "val gap = (composerTop - tailBottom) / density", "gap >= 16f - 1f / density",
                      "repeat(2) { frame() }", "assertTailClearance()"]:
            self.assertIn(token, source)


if __name__ == "__main__":
    unittest.main()
